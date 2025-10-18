package com.solanatappay.arcium

import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Arcium Encryption Wrapper
 * 
 * Provides client-side encryption for payment data before submission to Arcium MPC network.
 * In production, this would use Arcium's TypeScript SDK, but for the hackathon we implement
 * a compatible encryption layer that demonstrates the privacy architecture.
 * 
 * Privacy Features:
 * - Payment amounts encrypted before BLE/NFC transmission
 * - Merchant IDs obfuscated during transaction broadcast
 * - Revenue totals encrypted at rest
 * 
 * Privacy-Enabled Payments, Powered by Arcium
 */
object ArciumEncryption {
    
    private const val TAG = "ArciumEncryption"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    
    /**
     * Encrypted payment data structure
     * Represents data that will be processed by Arcium MPC nodes
     */
    data class EncryptedPaymentData(
        val encryptedAmount: ByteArray,
        val encryptedMerchantId: ByteArray,
        val orderId: Long,
        val iv: ByteArray,
        val publicMetadata: PaymentMetadata
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EncryptedPaymentData
            return orderId == other.orderId
        }
        
        override fun hashCode(): Int = orderId.hashCode()
    }
    
    /**
     * Public metadata (non-sensitive)
     * Can be broadcast without privacy concerns
     */
    data class PaymentMetadata(
        val timestamp: Long,
        val network: String,
        val version: Int = 1
    )
    
    /**
     * Encrypted revenue record
     * Merchant revenue data encrypted at rest
     */
    data class EncryptedRevenue(
        val encryptedTotalLamports: ByteArray,
        val encryptedPaymentCount: ByteArray,
        val merchantPublicKey: String,
        val iv: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EncryptedRevenue
            return merchantPublicKey == other.merchantPublicKey
        }
        
        override fun hashCode(): Int = merchantPublicKey.hashCode()
    }
    
    /**
     * Generate ephemeral encryption key for payment session
     * In production, this would derive from Arcium's key management
     */
    fun generateSessionKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(KEY_SIZE, SecureRandom())
        return keyGen.generateKey()
    }
    
    /**
     * Encrypt payment amount for MPC processing
     * 
     * @param amountLamports Payment amount in lamports
     * @param merchantPubkey Merchant's Solana public key
     * @param orderId Unique order identifier
     * @param sessionKey Encryption key for this payment session
     * @return Encrypted payment data ready for Arcium MPC
     */
    fun encryptPaymentData(
        amountLamports: Long,
        merchantPubkey: String,
        orderId: Long,
        sessionKey: SecretKey
    ): EncryptedPaymentData {
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, sessionKey, gcmSpec)
            
            // Encrypt amount
            val amountBytes = longToBytes(amountLamports)
            val encryptedAmount = cipher.doFinal(amountBytes)
            
            // Re-initialize cipher for merchant ID encryption
            cipher.init(Cipher.ENCRYPT_MODE, sessionKey, gcmSpec)
            
            // Encrypt merchant ID (hash for privacy)
            val merchantIdHash = hashMerchantId(merchantPubkey)
            val encryptedMerchant = cipher.doFinal(merchantIdHash)
            
            Log.d(TAG, "Payment data encrypted for Arcium MPC processing")
            Log.d(TAG, "Order ID: $orderId, Encrypted size: ${encryptedAmount.size + encryptedMerchant.size} bytes")
            
            return EncryptedPaymentData(
                encryptedAmount = encryptedAmount,
                encryptedMerchantId = encryptedMerchant,
                orderId = orderId,
                iv = iv,
                publicMetadata = PaymentMetadata(
                    timestamp = System.currentTimeMillis(),
                    network = "devnet",
                    version = 1
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed: ${e.message}", e)
            throw ArciumEncryptionException("Failed to encrypt payment data", e)
        }
    }
    
    /**
     * Decrypt payment data (merchant side only)
     * 
     * In production, decryption happens within Arcium MPC nodes.
     * Only authorized parties (merchant) can decrypt their revenue.
     */
    fun decryptPaymentAmount(
        encryptedData: EncryptedPaymentData,
        sessionKey: SecretKey
    ): Long {
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.iv)
            cipher.init(Cipher.DECRYPT_MODE, sessionKey, gcmSpec)
            
            val decryptedBytes = cipher.doFinal(encryptedData.encryptedAmount)
            val amount = bytesToLong(decryptedBytes)
            
            Log.d(TAG, "Payment amount decrypted: $amount lamports")
            return amount
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed: ${e.message}", e)
            throw ArciumEncryptionException("Failed to decrypt payment data", e)
        }
    }
    
    /**
     * Encrypt merchant revenue totals
     * 
     * @param totalLamports Total revenue in lamports
     * @param paymentCount Number of payments received
     * @param merchantPubkey Merchant's public key
     * @param merchantKey Merchant's private encryption key
     */
    fun encryptRevenue(
        totalLamports: Long,
        paymentCount: Int,
        merchantPubkey: String,
        merchantKey: SecretKey
    ): EncryptedRevenue {
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, merchantKey, gcmSpec)
            val encryptedTotal = cipher.doFinal(longToBytes(totalLamports))
            
            cipher.init(Cipher.ENCRYPT_MODE, merchantKey, gcmSpec)
            val encryptedCount = cipher.doFinal(intToBytes(paymentCount))
            
            Log.d(TAG, "Revenue data encrypted for merchant: $merchantPubkey")
            
            return EncryptedRevenue(
                encryptedTotalLamports = encryptedTotal,
                encryptedPaymentCount = encryptedCount,
                merchantPublicKey = merchantPubkey,
                iv = iv
            )
        } catch (e: Exception) {
            Log.e(TAG, "Revenue encryption failed: ${e.message}", e)
            throw ArciumEncryptionException("Failed to encrypt revenue", e)
        }
    }
    
    /**
     * Decrypt merchant revenue (merchant only)
     */
    fun decryptRevenue(
        encryptedRevenue: EncryptedRevenue,
        merchantKey: SecretKey
    ): Pair<Long, Int> {
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, encryptedRevenue.iv)
            
            cipher.init(Cipher.DECRYPT_MODE, merchantKey, gcmSpec)
            val totalLamports = bytesToLong(cipher.doFinal(encryptedRevenue.encryptedTotalLamports))
            
            cipher.init(Cipher.DECRYPT_MODE, merchantKey, gcmSpec)
            val paymentCount = bytesToInt(cipher.doFinal(encryptedRevenue.encryptedPaymentCount))
            
            return Pair(totalLamports, paymentCount)
        } catch (e: Exception) {
            Log.e(TAG, "Revenue decryption failed: ${e.message}", e)
            throw ArciumEncryptionException("Failed to decrypt revenue", e)
        }
    }
    
    /**
     * Hash merchant ID for privacy
     * Prevents direct merchant identification on-chain
     */
    private fun hashMerchantId(merchantPubkey: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(merchantPubkey.toByteArray())
    }
    
    /**
     * Derive encryption key from seed (for persistent storage)
     */
    fun deriveKeyFromSeed(seed: ByteArray): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(seed)
        return SecretKeySpec(keyBytes, "AES")
    }
    
    // Utility functions
    private fun longToBytes(value: Long): ByteArray {
        return ByteArray(8) { i -> (value shr (56 - i * 8)).toByte() }
    }
    
    private fun bytesToLong(bytes: ByteArray): Long {
        var result = 0L
        for (i in bytes.indices) {
            result = (result shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return result
    }
    
    private fun intToBytes(value: Int): ByteArray {
        return ByteArray(4) { i -> (value shr (24 - i * 8)).toByte() }
    }
    
    private fun bytesToInt(bytes: ByteArray): Int {
        var result = 0
        for (i in bytes.indices) {
            result = (result shl 8) or (bytes[i].toInt() and 0xFF)
        }
        return result
    }
}

/**
 * Exception for Arcium encryption operations
 */
class ArciumEncryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

