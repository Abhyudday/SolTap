package com.solanatappay.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.solanatappay.R
import com.solanatappay.data.PaymentHistoryManager
import com.solanatappay.nfc.HceReader
import com.solanatappay.nfc.NfcHelper
import com.solanatappay.solana.SolanaKeyManager
import com.solanatappay.solana.SolanaRpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * Background NFC handler - processes payments silently without showing UI.
 * Launches automatically when NFC tag is detected (even when app is closed).
 * Shows notifications for payment status.
 */
class NfcBackgroundHandler : AppCompatActivity() {
    
    companion object {
        private const val TAG = "NfcBackgroundHandler"
        private const val CHANNEL_ID = "solana_payments"
        private const val NOTIFICATION_ID_PROCESSING = 1001
        private const val NOTIFICATION_ID_SUCCESS = 1002
        private const val NOTIFICATION_ID_FAILED = 1003
        
        // Track processed orders to prevent duplicate payments
        private val processedOrders = mutableSetOf<Long>()
    }
    
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var rpcClient: SolanaRpcClient
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize RPC client
        rpcClient = SolanaRpcClient(this)
        
        // Create notification channel
        createNotificationChannel()
        
        // Check if this is an NFC intent
        val isNfcIntent = intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
                          intent?.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
                          intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED
        
        if (isNfcIntent) {
            Log.d(TAG, "Processing NFC payment in background")
            handleNfcIntent(intent)
        } else {
            Log.w(TAG, "Launched without NFC intent")
            finish()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
    
    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }
        
        // Try to read payment data from HCE or NDEF
        val paymentData = readPaymentData(intent)
        
        if (paymentData == null) {
            Log.w(TAG, "No payment data found")
            showNotification(
                "Payment Error",
                "No payment data found on tag",
                isError = true
            )
            finish()
            return
        }
        
        // Check if already processed
        if (processedOrders.contains(paymentData.orderId)) {
            Log.d(TAG, "Order already processed: ${paymentData.orderId}")
            showNotification(
                "Payment Skipped",
                "This payment was already processed",
                isError = true
            )
            finish()
            return
        }
        
        // Mark as processed
        processedOrders.add(paymentData.orderId)
        
        // Process payment
        processPayment(paymentData)
    }
    
    private fun readPaymentData(intent: Intent): NfcHelper.PaymentData? {
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
                Log.d(TAG, "Successfully read from HCE")
                return paymentData
            }
        }
        
        // Fallback: Try to extract NDEF messages (physical tags)
        val rawMessages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }
        
        if (rawMessages != null && rawMessages.isNotEmpty()) {
            val message = rawMessages[0] as? NdefMessage
            if (message != null) {
                return NfcHelper.parseNdefMessage(message)
            }
        }
        
        return null
    }
    
    private fun processPayment(paymentData: NfcHelper.PaymentData) {
        val sol = paymentData.amountLamports / 1_000_000_000.0
        val merchantShort = paymentData.merchantPubkey.take(8) + "..." + paymentData.merchantPubkey.takeLast(8)
        
        // Show processing notification
        showNotification(
            "Processing Payment",
            "Sending %.4f SOL to %s".format(sol, merchantShort),
            isProcessing = true
        )
        
        activityScope.launch(Dispatchers.IO) {
            try {
                // Get buyer's private key from Android Keystore
                val privateKey = SolanaKeyManager.getPrivateKey(this@NfcBackgroundHandler)
                val publicKey = SolanaKeyManager.getPublicKey(this@NfcBackgroundHandler)
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
                    this@NfcBackgroundHandler,
                    signature,
                    paymentData.amountLamports,
                    paymentData.merchantPubkey
                )
                
                // Show success notification with explorer link
                launch(Dispatchers.Main) {
                    showSuccessNotification(signature, sol, merchantShort)
                    finish()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Payment failed: ${e.message}", e)
                
                // Remove from processed orders so it can be retried
                processedOrders.remove(paymentData.orderId)
                
                // Show error notification
                launch(Dispatchers.Main) {
                    showNotification(
                        "Payment Failed",
                        e.message ?: "Unknown error",
                        isError = true
                    )
                    finish()
                }
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Solana Payments"
            val descriptionText = "Payment processing notifications"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun showNotification(
        title: String,
        message: String,
        isProcessing: Boolean = false,
        isError: Boolean = false
    ) {
        val notificationId = when {
            isProcessing -> NOTIFICATION_ID_PROCESSING
            isError -> NOTIFICATION_ID_FAILED
            else -> NOTIFICATION_ID_SUCCESS
        }
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(!isProcessing)
        
        if (isProcessing) {
            builder.setProgress(0, 0, true)
        }
        
        try {
            NotificationManagerCompat.from(this).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission not granted: ${e.message}")
        }
    }
    
    private fun showSuccessNotification(signature: String, sol: Double, merchantShort: String) {
        // Create intent to view transaction in explorer
        val explorerUrl = rpcClient.getExplorerUrl(signature)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(explorerUrl))
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("✓ Payment Successful")
            .setContentText("Sent %.4f SOL to %s".format(sol, merchantShort))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Sent %.4f SOL to %s\n\nTransaction: %s".format(sol, merchantShort, signature)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "View in Explorer", pendingIntent)
        
        try {
            // Cancel processing notification
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_PROCESSING)
            // Show success notification
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_SUCCESS, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission not granted: ${e.message}")
        }
    }
}

