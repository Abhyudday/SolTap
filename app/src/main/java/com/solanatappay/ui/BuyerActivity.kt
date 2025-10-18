package com.solanatappay.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.material.button.MaterialButton
import com.solanatappay.R
import com.solanatappay.data.PaymentHistoryManager
import com.solanatappay.nfc.HceReader
import com.solanatappay.nfc.NfcHelper
import com.solanatappay.solana.SolanaKeyManager
import com.solanatappay.solana.SolanaRpcClient
import com.solanatappay.arcium.ArciumPrivateRpcClient
import android.nfc.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * Buyer activity - scans NFC tags and auto-pays.
 * Simplified version with NFC only (BLE removed).
 */
class BuyerActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "BuyerActivity"
    }
    
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var scanCard: CardView
    private lateinit var resultCard: CardView
    private lateinit var txSignatureText: TextView
    private lateinit var viewExplorerButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var historyButton: MaterialButton
    private lateinit var switchModeButton: MaterialButton
    
    private lateinit var rpcClient: SolanaRpcClient
    private lateinit var arciumClient: ArciumPrivateRpcClient
    private var nfcAdapter: NfcAdapter? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Track processed order IDs to prevent duplicate payments
    private val processedOrders = mutableSetOf<Long>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_buyer)
        
        // Initialize RPC client
        rpcClient = SolanaRpcClient(this)
        
        // Initialize Arcium private payment client
        arciumClient = ArciumPrivateRpcClient(this)
        Log.i(TAG, "🔒 Arcium privacy layer initialized for buyer")
        
        // Initialize wallet (if not already initialized)
        try {
            SolanaKeyManager.getOrCreateKeypair(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize wallet: ${e.message}", e)
            Toast.makeText(this, "Failed to initialize wallet. Please try again.", Toast.LENGTH_LONG).show()
        }
        
        // Initialize NFC adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        // Initialize views
        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        progressBar = findViewById(R.id.progressBar)
        scanCard = findViewById(R.id.scanCard)
        resultCard = findViewById(R.id.resultCard)
        txSignatureText = findViewById(R.id.txSignatureText)
        viewExplorerButton = findViewById(R.id.viewExplorerButton)
        settingsButton = findViewById(R.id.settingsButton)
        historyButton = findViewById(R.id.historyButton)
        switchModeButton = findViewById(R.id.switchModeButton)
        
        // Set up button listeners (do this BEFORE NFC checks so they work even if NFC fails)
        switchModeButton.setOnClickListener {
            startActivity(Intent(this, MerchantActivity::class.java))
            finish()
        }
        
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        historyButton.setOnClickListener {
            startActivity(Intent(this, BuyerHistoryActivity::class.java))
        }
        
        // Check NFC availability
        if (nfcAdapter == null || !NfcHelper.isNfcSupported(this)) {
            Toast.makeText(this, R.string.nfc_not_supported, Toast.LENGTH_LONG).show()
            updateUIError("NFC not supported on this device")
            return
        }
        
        // Check if NFC is enabled
        if (!NfcHelper.isNfcEnabled(this)) {
            Toast.makeText(this, R.string.nfc_disabled, Toast.LENGTH_LONG).show()
            updateUIError("NFC is disabled. Please enable it in Settings.")
            return
        }
        
        // Check if launched via NFC intent
        val isNfcLaunch = intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
                          intent?.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
                          intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED
        
        if (isNfcLaunch) {
            // Handle NFC intent
            handleNfcIntent(intent)
        } else {
            // Show ready state
            updateUIForNfc()
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Enable NFC foreground dispatch
        enableNfcForegroundDispatch()
    }
    
    override fun onPause() {
        super.onPause()
        
        // Disable NFC foreground dispatch
        disableNfcForegroundDispatch()
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Handle NFC intent
        handleNfcIntent(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
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
        
        try {
            adapter.enableForegroundDispatch(this, pendingIntent, null, null)
            Log.d(TAG, "NFC foreground dispatch enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable NFC foreground dispatch: ${e.message}")
        }
    }
    
    private fun disableNfcForegroundDispatch() {
        try {
            nfcAdapter?.disableForegroundDispatch(this)
            Log.d(TAG, "NFC foreground dispatch disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable NFC foreground dispatch: ${e.message}")
        }
    }
    
    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return
        
        val action = intent.action
        Log.d(TAG, "Handling NFC intent: $action")
        
        when (action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED -> {
                processNfcTag(intent)
            }
        }
    }
    
    private fun processNfcTag(intent: Intent) {
        // First try to read from HCE (phone-to-phone)
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        
        if (tag != null) {
            Log.d(TAG, "Attempting to read from HCE service")
            val paymentData = HceReader.readPaymentData(tag)
            
            if (paymentData != null) {
                Log.d(TAG, "Successfully read from HCE: ${paymentData.amountLamports} lamports to ${paymentData.merchantPubkey}")
                processPaymentData(paymentData)
                return
            }
            
            Log.d(TAG, "HCE read failed, trying NDEF messages")
        }
        
        // Fallback: Try to extract NDEF messages (physical tags)
        val rawMessages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }
        
        if (rawMessages == null || rawMessages.isEmpty()) {
            Log.w(TAG, "No payment data found (neither HCE nor NDEF)")
            Toast.makeText(this, "No payment data found on tag", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Parse first NDEF message
        val message = rawMessages[0] as? NdefMessage
        if (message == null) {
            Log.w(TAG, "Invalid NDEF message")
            return
        }
        
        val paymentData = NfcHelper.parseNdefMessage(message)
        if (paymentData == null) {
            Log.w(TAG, "Failed to parse payment data")
            Toast.makeText(this, "Invalid payment data", Toast.LENGTH_SHORT).show()
            return
        }
        
        Log.d(TAG, "Parsed NDEF payment data: ${paymentData.amountLamports} lamports to ${paymentData.merchantPubkey}")
        processPaymentData(paymentData)
    }
    
    private fun processPaymentData(paymentData: NfcHelper.PaymentData) {
        // Check if already processed
        if (processedOrders.contains(paymentData.orderId)) {
            Log.d(TAG, "Order already processed: ${paymentData.orderId}")
            Toast.makeText(this, "Payment already processed", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Mark as processed
        processedOrders.add(paymentData.orderId)
        
        // Show merchant detected
        onMerchantDetected(paymentData.merchantPubkey, paymentData.amountLamports)
        
        // Execute payment automatically
        executeNfcPayment(paymentData)
    }
    
    private fun executeNfcPayment(paymentData: NfcHelper.PaymentData) {
        Log.d(TAG, "AUTO-EXECUTING NFC payment (no user approval)")
        
        updateUIProcessing()
        
        activityScope.launch(Dispatchers.IO) {
            try {
                // Get buyer's private key from Android Keystore
                val privateKey = SolanaKeyManager.getPrivateKey(this@BuyerActivity)
                val publicKey = SolanaKeyManager.getPublicKey(this@BuyerActivity)
                    ?: throw Exception("No public key found")
                
                Log.d(TAG, "Sending ${paymentData.amountLamports} lamports from $publicKey to ${paymentData.merchantPubkey}")
                
                // Build, sign, and send transaction
                val signature = rpcClient.sendSolTransfer(
                    fromPrivateKey = privateKey,
                    fromPubkey = publicKey,
                    toPubkey = paymentData.merchantPubkey,
                    amountLamports = paymentData.amountLamports
                )
                
                Log.d(TAG, "Payment successful: $signature")
                
                // Save to payment history
                PaymentHistoryManager.addBuyerPayment(
                    this@BuyerActivity,
                    signature,
                    paymentData.amountLamports,
                    paymentData.merchantPubkey
                )
                
                // Notify success
                launch(Dispatchers.Main) {
                    onPaymentSuccess(signature)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Payment failed: ${e.message}", e)
                
                // Remove from processed orders so it can be retried
                processedOrders.remove(paymentData.orderId)
                
                // Notify failure
                launch(Dispatchers.Main) {
                    onPaymentFailed(e.message ?: "Unknown error")
                }
            }
        }
    }
    
    private fun updateUIForNfc() {
        statusText.text = getString(R.string.nfc_ready_buyer)
        detailText.text = "Tap phone to NFC tag or merchant device"
        progressBar.visibility = View.VISIBLE
    }
    
    private fun onMerchantDetected(merchantPubkey: String, amountLamports: Long) {
        val sol = amountLamports / 1_000_000_000.0
        val merchantShort = merchantPubkey.take(8) + "..." + merchantPubkey.takeLast(8)
        statusText.text = "Payment detected"
        detailText.text = "%.4f SOL to %s".format(sol, merchantShort)
    }
    
    private fun updateUIProcessing() {
        statusText.text = getString(R.string.payment_processing)
        detailText.text = "Sending transaction..."
        progressBar.visibility = View.VISIBLE
    }
    
    private fun onPaymentSuccess(signature: String) {
        runOnUiThread {
            // Hide scanning card, show result card
            resultCard.visibility = View.VISIBLE
            
            // Update result details
            txSignatureText.text = "Transaction: $signature"
            
            viewExplorerButton.setOnClickListener {
                val url = rpcClient.getExplorerUrl(signature)
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(browserIntent)
            }
            
            Toast.makeText(this, "Payment successful!", Toast.LENGTH_LONG).show()
            
            // Reset scanning after delay
            statusText.text = "Payment completed!"
            detailText.text = "Transaction sent successfully"
            progressBar.visibility = View.GONE
        }
    }
    
    private fun onPaymentFailed(error: String) {
        runOnUiThread {
            statusText.text = "Payment failed"
            detailText.text = error
            progressBar.visibility = View.GONE
            
            Toast.makeText(this, "Payment failed: $error", Toast.LENGTH_LONG).show()
            
            // Resume ready state after a delay
            statusText.postDelayed({
                updateUIForNfc()
            }, 3000)
        }
    }
    
    private fun updateUIError(error: String) {
        statusText.text = "Error"
        detailText.text = error
        progressBar.visibility = View.GONE
    }
}
