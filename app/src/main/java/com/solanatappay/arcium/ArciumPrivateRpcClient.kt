package com.solanatappay.arcium

import android.content.Context
import android.util.Log
import com.solanatappay.solana.SolanaRpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

/**
 * Arcium-Enhanced RPC Client
 * 
 * Connects to TypeScript bridge service for Arcium MPC integration.
 * Falls back to standard Solana transactions to ensure payments always go through.
 * 
 * Architecture:
 * 1. Android app → TypeScript bridge service
 * 2. Bridge encrypts data with Arcium SDK
 * 3. Submits to Arcium program or fallback to standard transfer
 * 4. Bridge tracks callbacks and returns results
 * 5. App receives confirmation (transaction always completes)
 * 
 * Privacy-Enabled Payments, Powered by Arcium
 */
class ArciumPrivateRpcClient(context: Context) {
    
    private val baseRpcClient = SolanaRpcClient(context)
    private val context = context
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    companion object {
        private const val TAG = "ArciumPrivateRpc"
        private const val PREFS_NAME = "ArciumSettings"
        private const val PREFS_PRIVACY_ENABLED = "privacy_enabled"
        private const val PREFS_BRIDGE_URL = "bridge_url"
        
        // Arcium program ID (would be updated with deployed program address)
        const val ARCIUM_PROGRAM_ID = "ArcPay11111111111111111111111111111111111"
        
        // Default bridge service URL (can be configured)
        private const val DEFAULT_BRIDGE_URL = "http://localhost:3000"
        
        /**
         * Check if privacy mode is enabled
         */
        fun isPrivacyEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREFS_PRIVACY_ENABLED, true) // Default enabled
        }
        
        /**
         * Enable/disable privacy mode
         */
        fun setPrivacyEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREFS_PRIVACY_ENABLED, enabled)
                .apply()
            Log.i(TAG, "Privacy mode ${if (enabled) "enabled" else "disabled"}")
        }
        
        /**
         * Get bridge service URL
         */
        fun getBridgeUrl(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREFS_BRIDGE_URL, DEFAULT_BRIDGE_URL) ?: DEFAULT_BRIDGE_URL
        }
        
        /**
         * Set bridge service URL
         */
        fun setBridgeUrl(context: Context, url: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREFS_BRIDGE_URL, url)
                .apply()
            Log.i(TAG, "Bridge URL updated: $url")
        }
    }
    
    /**
     * Send private payment transaction
     * 
     * Routes through TypeScript bridge for proper Arcium integration.
     * Falls back to standard transaction if bridge unavailable.
     * 
     * @param fromPrivateKey Buyer's private key
     * @param fromPubkey Buyer's public key
     * @param toPubkey Merchant's public key
     * @param amountLamports Payment amount (will be encrypted)
     * @param orderId Unique order identifier
     * @return Transaction signature
     */
    suspend fun sendPrivatePayment(
        fromPrivateKey: ByteArray,
        fromPubkey: String,
        toPubkey: String,
        amountLamports: Long,
        orderId: Long
    ): PrivatePaymentResult = withContext(Dispatchers.IO) {
        
        if (!isPrivacyEnabled(context)) {
            Log.w(TAG, "Privacy mode disabled, using standard transaction")
            return@withContext executeStandardPayment(fromPrivateKey, fromPubkey, toPubkey, amountLamports, orderId)
        }
        
        try {
            Log.i(TAG, "🔒 Connecting to Arcium bridge service")
            val bridgeUrl = getBridgeUrl(context)
            
            // Step 1: Check bridge health
            val healthCheck = checkBridgeHealth(bridgeUrl)
            if (!healthCheck) {
                Log.w(TAG, "Bridge service unavailable, falling back to standard transaction")
                return@withContext executeStandardPayment(fromPrivateKey, fromPubkey, toPubkey, amountLamports, orderId)
            }
            
            // Step 2: Encrypt payment data via bridge
            Log.i(TAG, "🔐 Encrypting payment data via Arcium SDK")
            val encryptedData = encryptViaBridge(bridgeUrl, amountLamports, toPubkey, orderId, fromPubkey)
            
            // Step 3: Submit private payment
            Log.i(TAG, "📤 Submitting encrypted payment to Arcium program")
            val result = submitPrivatePaymentViaBridge(
                bridgeUrl,
                fromPrivateKey,
                fromPubkey,
                toPubkey,
                amountLamports,
                orderId,
                encryptedData
            )
            
            Log.i(TAG, "✅ Private payment completed: ${result.signature}")
            Log.i(TAG, if (result.encrypted) {
                "🔒 Amount and merchant ID encrypted via Arcium MPC"
            } else {
                "💸 Standard payment (fallback mode)"
            })
            
            return@withContext result
            
        } catch (e: Exception) {
            Log.e(TAG, "Arcium bridge error: ${e.message}", e)
            Log.w(TAG, "Falling back to standard transaction to ensure payment completes")
            
            // CRITICAL: Always fallback to ensure transaction goes through
            return@withContext executeStandardPayment(fromPrivateKey, fromPubkey, toPubkey, amountLamports, orderId)
        }
    }
    
    /**
     * Check if bridge service is healthy
     */
    private fun checkBridgeHealth(bridgeUrl: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$bridgeUrl/health")
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            val isHealthy = response.isSuccessful
            response.close()
            
            if (isHealthy) {
                Log.d(TAG, "✅ Bridge service healthy")
            } else {
                Log.w(TAG, "⚠️  Bridge service returned ${response.code}")
            }
            
            isHealthy
        } catch (e: Exception) {
            Log.w(TAG, "⚠️  Bridge health check failed: ${e.message}")
            false
        }
    }
    
    /**
     * Encrypt payment data via bridge service
     */
    private fun encryptViaBridge(
        bridgeUrl: String,
        amountLamports: Long,
        merchantPubkey: String,
        orderId: Long,
        buyerPubkey: String
    ): EncryptedDataResult {
        val jsonRequest = JSONObject().apply {
            put("amountLamports", amountLamports)
            put("merchantPubkey", merchantPubkey)
            put("orderId", orderId)
            put("buyerPubkey", buyerPubkey)
        }
        
        val requestBody = jsonRequest.toString()
            .toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$bridgeUrl/encrypt-payment")
            .post(requestBody)
            .build()
        
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Encryption failed: ${response.code}")
            }
            
            val body = response.body?.string() ?: throw Exception("Empty response")
            val json = JSONObject(body)
            
            return EncryptedDataResult(
                encryptedAmount = json.getString("encryptedAmount"),
                encryptedMerchantId = json.getString("encryptedMerchantId"),
                nonce = json.getString("nonce")
            )
        }
    }
    
    /**
     * Submit private payment via bridge
     */
    private fun submitPrivatePaymentViaBridge(
        bridgeUrl: String,
        fromPrivateKey: ByteArray,
        fromPubkey: String,
        toPubkey: String,
        amountLamports: Long,
        orderId: Long,
        encryptedData: EncryptedDataResult
    ): PrivatePaymentResult {
        val privateKeyBase58 = android.util.Base64.encodeToString(fromPrivateKey, android.util.Base64.NO_WRAP)
        
        val jsonRequest = JSONObject().apply {
            put("buyerPrivateKey", privateKeyBase58)
            put("buyerPubkey", fromPubkey)
            put("merchantPubkey", toPubkey)
            put("amountLamports", amountLamports)
            put("orderId", orderId)
            put("encryptedAmount", encryptedData.encryptedAmount)
            put("encryptedMerchantId", encryptedData.encryptedMerchantId)
            put("nonce", encryptedData.nonce)
        }
        
        val requestBody = jsonRequest.toString()
            .toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$bridgeUrl/submit-private-payment")
            .post(requestBody)
            .build()
        
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Payment submission failed: ${response.code}")
            }
            
            val body = response.body?.string() ?: throw Exception("Empty response")
            val json = JSONObject(body)
            
            return PrivatePaymentResult(
                signature = json.getString("signature"),
                encrypted = json.getBoolean("encrypted"),
                orderId = orderId,
                computationId = json.optString("computationId", null)
            )
        }
    }
    
    /**
     * Execute standard payment (fallback)
     * ENSURES TRANSACTION ALWAYS GOES THROUGH
     */
    private suspend fun executeStandardPayment(
        fromPrivateKey: ByteArray,
        fromPubkey: String,
        toPubkey: String,
        amountLamports: Long,
        orderId: Long
    ): PrivatePaymentResult {
        val signature = baseRpcClient.sendSolTransfer(
            fromPrivateKey,
            fromPubkey,
            toPubkey,
            amountLamports
        )
        
        return PrivatePaymentResult(
            signature = signature,
            encrypted = false,
            orderId = orderId
        )
    }
    
    /**
     * Get merchant's encrypted revenue
     * 
     * Returns revenue data that only the merchant can decrypt
     */
    suspend fun getMerchantEncryptedRevenue(
        merchantPubkey: String,
        merchantKey: SecretKey
    ): ArciumEncryption.EncryptedRevenue = withContext(Dispatchers.IO) {
        
        Log.d(TAG, "Fetching encrypted revenue for merchant: ${merchantPubkey.substring(0, 8)}...")
        
        // In production, query Arcium program account
        // For demo, calculate from local history with encryption
        
        val paymentHistory = com.solanatappay.data.PaymentHistoryManager.getMerchantPayments(context)
        val totalLamports = paymentHistory.sumOf { it.amount }
        val paymentCount = paymentHistory.size
        
        // Encrypt revenue data (only merchant can decrypt)
        val encryptedRevenue = ArciumEncryption.encryptRevenue(
            totalLamports = totalLamports,
            paymentCount = paymentCount,
            merchantPubkey = merchantPubkey,
            merchantKey = merchantKey
        )
        
        Log.i(TAG, "✅ Revenue data encrypted for merchant")
        Log.d(TAG, "Encrypted revenue size: ${encryptedRevenue.encryptedTotalLamports.size} bytes")
        
        return@withContext encryptedRevenue
    }
    
    /**
     * Decrypt merchant revenue (merchant only)
     */
    suspend fun decryptMerchantRevenue(
        encryptedRevenue: ArciumEncryption.EncryptedRevenue,
        merchantKey: SecretKey
    ): MerchantRevenue = withContext(Dispatchers.IO) {
        
        Log.d(TAG, "Decrypting revenue data...")
        
        val (totalLamports, paymentCount) = ArciumEncryption.decryptRevenue(
            encryptedRevenue,
            merchantKey
        )
        
        Log.i(TAG, "✅ Revenue decrypted successfully")
        
        return@withContext MerchantRevenue(
            totalLamports = totalLamports,
            paymentCount = paymentCount,
            merchantPubkey = encryptedRevenue.merchantPublicKey
        )
    }
    
    /**
     * Store encrypted payment session for later merchant access
     */
    private fun storePaymentSession(orderId: Long, sessionKey: SecretKey, merchantPubkey: String) {
        val prefs = context.getSharedPreferences("ArciumSessions", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("session_$orderId", android.util.Base64.encodeToString(sessionKey.encoded, android.util.Base64.NO_WRAP))
            .putString("merchant_$orderId", merchantPubkey)
            .apply()
    }
    
    /**
     * Get base RPC client for non-private operations
     */
    fun getBaseClient(): SolanaRpcClient = baseRpcClient
    
    // Data classes
    data class PrivatePaymentResult(
        val signature: String,
        val encrypted: Boolean,
        val orderId: Long,
        val encryptedData: ArciumEncryption.EncryptedPaymentData? = null,
        val computationId: String? = null
    )
    
    data class MerchantRevenue(
        val totalLamports: Long,
        val paymentCount: Int,
        val merchantPubkey: String
    )
    
    data class EncryptedDataResult(
        val encryptedAmount: String,
        val encryptedMerchantId: String,
        val nonce: String
    )
}

/**
 * Exception for Arcium payment operations
 */
class ArciumPaymentException(message: String, cause: Throwable? = null) : Exception(message, cause)

