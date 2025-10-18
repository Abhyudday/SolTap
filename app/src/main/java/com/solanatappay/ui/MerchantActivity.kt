package com.solanatappay.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.solanatappay.R
import com.solanatappay.ble.MerchantAdvertiserService
import com.solanatappay.data.PaymentHistoryManager
import com.solanatappay.nfc.NfcHelper
import com.solanatappay.nfc.PaymentHceService
import com.solanatappay.solana.SolanaKeyManager
import com.solanatappay.solana.SolanaRpcClient
import com.solanatappay.arcium.ArciumPrivateRpcClient
import com.solanatappay.arcium.ArciumEncryption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Merchant activity - creates BLE advertisement or NFC write for payment requests.
 */
class MerchantActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MerchantActivity"
        private const val COINGECKO_API_URL = "https://api.coingecko.com/api/v3/simple/price?ids=solana&vs_currencies=usd"
    }
    
    // Transport mode
    enum class TransportMode {
        BLE, NFC
    }
    
    private lateinit var amountInput: TextInputEditText
    private lateinit var solAmountText: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var txSignatureText: TextView
    private lateinit var viewExplorerButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var dashboardButton: MaterialButton
    private lateinit var convertToFiatButton: MaterialButton
    private lateinit var dashboardRevenueText: TextView
    private lateinit var dashboardCountText: TextView
    private lateinit var transportToggle: MaterialButtonToggleGroup
    private lateinit var bleButton: MaterialButton
    private lateinit var nfcButton: MaterialButton
    private lateinit var switchModeButton: MaterialButton
    
    private var isAdvertising = false
    private var currentOrderId: Long = 0
    private var currentTransportMode = TransportMode.BLE
    
    private lateinit var rpcClient: SolanaRpcClient
    private lateinit var arciumClient: ArciumPrivateRpcClient
    private var nfcAdapter: NfcAdapter? = null
    private var pendingPaymentData: NfcHelper.PaymentData? = null
    
    // Dynamic USD to SOL conversion rate (fetched from CoinGecko)
    private var usdToSolRate: Double = 0.01 // Default fallback rate
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    // Receiver for payment confirmations from service
    private val paymentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MerchantAdvertiserService.ACTION_ADVERTISING_STARTED -> {
                    updateUIAdvertising()
                }
                MerchantAdvertiserService.ACTION_ADVERTISING_STOPPED -> {
                    updateUIReady()
                }
                MerchantAdvertiserService.ACTION_ADVERTISING_FAILED -> {
                    val error = intent.getStringExtra("error")
                    updateUIError(error ?: "Unknown error")
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_merchant)
        
        // Initialize RPC client
        rpcClient = SolanaRpcClient(this)
        
        // Initialize Arcium private payment client
        arciumClient = ArciumPrivateRpcClient(this)
        android.util.Log.i(TAG, "🔒 Arcium privacy layer initialized")
        android.util.Log.i(TAG, "Privacy mode: ${if (ArciumPrivateRpcClient.isPrivacyEnabled(this)) "ENABLED" else "DISABLED"}")
        
        // Initialize NFC adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        // Initialize views
        amountInput = findViewById(R.id.amountInput)
        solAmountText = findViewById(R.id.solAmountText)
        startButton = findViewById(R.id.startButton)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        txSignatureText = findViewById(R.id.txSignatureText)
        viewExplorerButton = findViewById(R.id.viewExplorerButton)
        settingsButton = findViewById(R.id.settingsButton)
        dashboardButton = findViewById(R.id.dashboardButton)
        convertToFiatButton = findViewById(R.id.convertToFiatButton)
        dashboardRevenueText = findViewById(R.id.dashboardRevenueText)
        dashboardCountText = findViewById(R.id.dashboardCountText)
        transportToggle = findViewById(R.id.transportToggle)
        bleButton = findViewById(R.id.bleButton)
        nfcButton = findViewById(R.id.nfcButton)
        switchModeButton = findViewById(R.id.switchModeButton)
        
        // Set up mode switch button
        switchModeButton.setOnClickListener {
            startActivity(Intent(this, BuyerActivity::class.java))
            finish()
        }
        
        // Check NFC availability
        if (nfcAdapter == null || !NfcHelper.isNfcSupported(this)) {
            nfcButton.isEnabled = false
            Toast.makeText(this, R.string.nfc_not_supported, Toast.LENGTH_SHORT).show()
        }
        
        // Set up transport toggle listener
        transportToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentTransportMode = when (checkedId) {
                    R.id.nfcButton -> TransportMode.NFC
                    else -> TransportMode.BLE
                }
                updateUIForTransportMode()
            }
        }
        
        // Set up amount input listener
        amountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSolAmount()
            }
        })
        
        // Set up button listeners
        startButton.setOnClickListener {
            if (isAdvertising) {
                stopRequest()
            } else {
                startRequest()
            }
        }
        
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        dashboardButton.setOnClickListener {
            startActivity(Intent(this, MerchantDashboardActivity::class.java))
        }
        
        convertToFiatButton.setOnClickListener {
            startActivity(Intent(this, ConvertToFiatActivity::class.java))
        }
        
        // Load dashboard stats
        loadDashboardStats()
        
        // Fetch real-time SOL price
        fetchSolPrice()
        
        // Register broadcast receiver
        val filter = IntentFilter().apply {
            addAction(MerchantAdvertiserService.ACTION_ADVERTISING_STARTED)
            addAction(MerchantAdvertiserService.ACTION_ADVERTISING_STOPPED)
            addAction(MerchantAdvertiserService.ACTION_ADVERTISING_FAILED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(paymentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(paymentReceiver, filter)
        }
        
        updateUIForTransportMode()
    }
    
    override fun onResume() {
        super.onResume()
        
        // Refresh dashboard stats
        loadDashboardStats()
        
        // Refresh SOL price
        fetchSolPrice()
        
        // Enable NFC foreground dispatch for NFC mode
        if (currentTransportMode == TransportMode.NFC && pendingPaymentData != null) {
            enableNfcForegroundDispatch()
        }
    }
    
    override fun onPause() {
        super.onPause()
        
        // Disable NFC foreground dispatch
        if (currentTransportMode == TransportMode.NFC) {
            disableNfcForegroundDispatch()
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        
        // Handle NFC tag detected
        if (currentTransportMode == TransportMode.NFC && 
            intent?.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
            handleNfcTag(intent)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(paymentReceiver)
        if (isAdvertising) {
            stopRequest()
        }
    }
    
    private fun updateSolAmount() {
        val usdAmount = amountInput.text.toString().toDoubleOrNull() ?: 0.0
        val solAmount = usdAmount / usdToSolRate
        solAmountText.text = "≈ %.4f SOL".format(solAmount)
    }
    
    private fun startRequest() {
        val usdAmount = amountInput.text.toString().toDoubleOrNull()
        if (usdAmount == null || usdAmount <= 0) {
            Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }
        
        val solAmount = usdAmount / usdToSolRate
        val lamports = (solAmount * 1_000_000_000).toLong()
        
        val publicKey = SolanaKeyManager.getPublicKey(this)
        if (publicKey == null) {
            Toast.makeText(this, "Wallet not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Generate order ID
        currentOrderId = System.currentTimeMillis()
        
        when (currentTransportMode) {
            TransportMode.BLE -> startBleAdvertising(publicKey, lamports, currentOrderId)
            TransportMode.NFC -> startNfcWrite(publicKey, lamports, currentOrderId)
        }
        
        isAdvertising = true
        startButton.text = getString(R.string.stop_request)
    }
    
    private fun stopRequest() {
        when (currentTransportMode) {
            TransportMode.BLE -> stopBleAdvertising()
            TransportMode.NFC -> stopNfcWrite()
        }
        
        isAdvertising = false
        startButton.text = getString(R.string.start_request)
        updateUIReady()
    }
    
    private fun startBleAdvertising(publicKey: String, lamports: Long, orderId: Long) {
        // Start advertising service
        val intent = Intent(this, MerchantAdvertiserService::class.java).apply {
            putExtra("merchantPubkey", publicKey)
            putExtra("amountLamports", lamports)
            putExtra("orderId", orderId)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    private fun stopBleAdvertising() {
        val intent = Intent(this, MerchantAdvertiserService::class.java)
        stopService(intent)
    }
    
    private fun startNfcWrite(publicKey: String, lamports: Long, orderId: Long) {
        // Check NFC enabled
        if (!NfcHelper.isNfcEnabled(this)) {
            Toast.makeText(this, R.string.nfc_disabled, Toast.LENGTH_LONG).show()
            isAdvertising = false
            startButton.text = getString(R.string.start_request)
            return
        }
        
        // Create payment data
        pendingPaymentData = NfcHelper.PaymentData(
            merchantPubkey = publicKey,
            amountLamports = lamports,
            orderId = orderId
        )
        
        // Set payment data in HCE service (merchant acts as NFC tag)
        PaymentHceService.currentPaymentData = pendingPaymentData
        
        // Update UI
        statusText.text = "Hold buyer phone to this device"
        progressBar.visibility = View.VISIBLE
        amountInput.isEnabled = false
        transportToggle.isEnabled = false
        
        Toast.makeText(this, "Ready for buyer tap (HCE mode)", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopNfcWrite() {
        pendingPaymentData = null
        PaymentHceService.currentPaymentData = null
        transportToggle.isEnabled = true
    }
    
    private fun enableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE
        )
        
        adapter.enableForegroundDispatch(this, pendingIntent, null, null)
    }
    
    private fun disableNfcForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }
    
    private fun handleNfcTag(intent: Intent) {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        } ?: return
        
        val paymentData = pendingPaymentData ?: return
        
        // Write NDEF message to tag
        val success = writeNdefMessage(tag, paymentData)
        
        if (success) {
            Toast.makeText(this, R.string.nfc_write_success, Toast.LENGTH_SHORT).show()
            statusText.text = "NFC tag written successfully!"
            progressBar.visibility = View.GONE
        } else {
            Toast.makeText(this, "Failed to write NFC tag", Toast.LENGTH_SHORT).show()
            statusText.text = "Error writing NFC tag"
            progressBar.visibility = View.GONE
        }
    }
    
    private fun writeNdefMessage(tag: Tag, paymentData: NfcHelper.PaymentData): Boolean {
        val message = NfcHelper.createNdefMessage(paymentData)
        
        return try {
            // Try Ndef format first
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                android.util.Log.d(TAG, "Tag is NDEF formatted")
                ndef.connect()
                
                if (!ndef.isWritable) {
                    android.util.Log.e(TAG, "Tag is not writable")
                    runOnUiThread {
                        Toast.makeText(this, "NFC tag is read-only", Toast.LENGTH_SHORT).show()
                    }
                    ndef.close()
                    return false
                }
                
                val size = message.toByteArray().size
                val maxSize = ndef.maxSize
                android.util.Log.d(TAG, "Message size: $size bytes, Max size: $maxSize bytes")
                
                if (size > maxSize) {
                    android.util.Log.e(TAG, "Message too large for tag")
                    runOnUiThread {
                        Toast.makeText(this, "Payment data too large for this NFC tag", Toast.LENGTH_SHORT).show()
                    }
                    ndef.close()
                    return false
                }
                
                ndef.writeNdefMessage(message)
                ndef.close()
                android.util.Log.d(TAG, "Successfully wrote NDEF message")
                return true
            }
            
            // Try NdefFormatable
            val formatable = NdefFormatable.get(tag)
            if (formatable != null) {
                android.util.Log.d(TAG, "Tag is formatable, formatting now...")
                formatable.connect()
                formatable.format(message)
                formatable.close()
                android.util.Log.d(TAG, "Successfully formatted and wrote NDEF message")
                return true
            }
            
            android.util.Log.e(TAG, "Tag is not NDEF compatible")
            runOnUiThread {
                Toast.makeText(this, "This NFC tag is not compatible", Toast.LENGTH_SHORT).show()
            }
            false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error writing NFC: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Error writing NFC: ${e.message}", Toast.LENGTH_LONG).show()
            }
            false
        }
    }
    
    private fun updateUIForTransportMode() {
        when (currentTransportMode) {
            TransportMode.BLE -> {
                statusText.text = "Ready to start"
            }
            TransportMode.NFC -> {
                statusText.text = getString(R.string.nfc_ready_merchant)
            }
        }
    }
    
    private fun updateUIAdvertising() {
        statusText.text = getString(R.string.advertising)
        progressBar.visibility = View.VISIBLE
        txSignatureText.visibility = View.GONE
        viewExplorerButton.visibility = View.GONE
        amountInput.isEnabled = false
    }
    
    private fun updateUIReady() {
        statusText.text = "Ready to start"
        progressBar.visibility = View.GONE
        amountInput.isEnabled = true
    }
    
    private fun updateUIError(error: String) {
        statusText.text = "Error: $error"
        progressBar.visibility = View.GONE
        isAdvertising = false
        startButton.text = getString(R.string.start_request)
        amountInput.isEnabled = true
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
    }
    
    /**
     * Load and display dashboard stats.
     */
    private fun loadDashboardStats() {
        val payments = PaymentHistoryManager.getMerchantPayments(this)
        
        val totalLamports = payments.sumOf { it.amount }
        val totalSol = totalLamports / 1_000_000_000.0
        
        dashboardRevenueText.text = "%.4f SOL".format(totalSol)
        dashboardCountText.text = "${payments.size}"
    }
    
    /**
     * Called when payment is received (merchant can poll or be notified).
     * For this prototype, merchant needs to check explorer manually or implement
     * a websocket subscription to Solana.
     */
    private fun onPaymentReceived(signature: String, buyerPubkey: String, amountLamports: Long) {
        runOnUiThread {
            statusText.text = "Payment received!"
            progressBar.visibility = View.GONE
            txSignatureText.text = "TX: $signature"
            txSignatureText.visibility = View.VISIBLE
            viewExplorerButton.visibility = View.VISIBLE
            
            viewExplorerButton.setOnClickListener {
                val url = rpcClient.getExplorerUrl(signature)
                val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                startActivity(browserIntent)
            }
            
            // Save to payment history
            PaymentHistoryManager.addMerchantPayment(
                this,
                signature,
                amountLamports,
                buyerPubkey
            )
            
            // Update dashboard stats
            loadDashboardStats()
            
            Toast.makeText(this, "Payment successful!", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Fetch real-time Solana price from CoinGecko API.
     * Updates usdToSolRate and refreshes SOL amount display.
     */
    private fun fetchSolPrice() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(COINGECKO_API_URL)
                    .build()
                
                val response = httpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val json = JSONObject(responseBody)
                        val solPrice = json.getJSONObject("solana").getDouble("usd")
                        
                        // Update the rate on main thread
                        withContext(Dispatchers.Main) {
                            usdToSolRate = solPrice
                            updateSolAmount() // Refresh the displayed SOL amount
                            android.util.Log.d(TAG, "Updated SOL price: $solPrice USD")
                        }
                    }
                } else {
                    android.util.Log.e(TAG, "Failed to fetch SOL price: ${response.code}")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error fetching SOL price: ${e.message}", e)
                // Keep using the fallback rate
            }
        }
    }
}

