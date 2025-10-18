package com.solanatappay.solana

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.Cipher
import kotlin.random.Random

/**
 * Manages Solana keypair generation and secure storage using Android Keystore.
 * 
 * SECURITY NOTE: This is a PROTOTYPE implementation. In production:
 * - Use server-side custody or HSM
 * - Implement proper authentication before key access
 * - Never auto-sign transactions without user approval
 */
object SolanaKeyManager {
    private const val KEYSTORE_ALIAS = "SolanaTapPayKey"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val PREFS_NAME = "SolanaWallet"
    private const val PREFS_ENCRYPTED_SEED = "encrypted_seed"
    private const val PREFS_PUBLIC_KEY = "public_key"
    
    /**
     * Initialize or retrieve existing Solana keypair.
     * Returns the base58-encoded public key (address).
     */
    fun getOrCreateKeypair(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingPubKey = prefs.getString(PREFS_PUBLIC_KEY, null)
        
        if (existingPubKey != null) {
            return existingPubKey
        }
        
        // Generate new keypair
        val seed = ByteArray(32)
        Random.nextBytes(seed)
        
        // Encrypt seed with Android Keystore
        val encryptedSeed = encryptSeed(context, seed)
        
        // Generate Ed25519 keypair from seed
        val keypair = ed25519KeypairFromSeed(seed)
        val publicKey = Base58.encode(keypair.publicKey)
        
        // Save encrypted seed and public key
        prefs.edit()
            .putString(PREFS_ENCRYPTED_SEED, Base64.encodeToString(encryptedSeed, Base64.DEFAULT))
            .putString(PREFS_PUBLIC_KEY, publicKey)
            .apply()
        
        return publicKey
    }
    
    /**
     * Get the stored public key (address).
     */
    fun getPublicKey(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREFS_PUBLIC_KEY, null)
    }
    
    /**
     * Get the private key for signing transactions.
     * Returns raw 32-byte private key (seed).
     */
    fun getPrivateKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedSeed = prefs.getString(PREFS_ENCRYPTED_SEED, null)
            ?: throw IllegalStateException("No keypair found")
        
        val encryptedBytes = Base64.decode(encryptedSeed, Base64.DEFAULT)
        return decryptSeed(context, encryptedBytes)
    }
    
    private fun encryptSeed(context: Context, seed: ByteArray): ByteArray {
        // Ensure keystore key exists
        ensureKeystoreKey()
        
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.PrivateKeyEntry
        
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, entry.certificate.publicKey)
        
        return cipher.doFinal(seed)
    }
    
    private fun decryptSeed(context: Context, encryptedSeed: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.PrivateKeyEntry
        
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, entry.privateKey)
        
        return cipher.doFinal(encryptedSeed)
    }
    
    private fun ensureKeystoreKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                ANDROID_KEYSTORE
            )
            
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setKeySize(2048)
                .build()
            
            keyPairGenerator.initialize(spec)
            keyPairGenerator.generateKeyPair()
        }
    }
    
    /**
     * Simple Ed25519 keypair structure.
     */
    data class Ed25519Keypair(
        val publicKey: ByteArray,
        val privateKey: ByteArray
    )
    
    /**
     * Generate Ed25519 keypair from 32-byte seed.
     * Uses the seed directly as the private key for signing.
     * Public key is derived using Ed25519 algorithm.
     */
    private fun ed25519KeypairFromSeed(seed: ByteArray): Ed25519Keypair {
        require(seed.size == 32) { "Seed must be 32 bytes" }
        
        // For Solana, we use the seed as private key and derive public key
        // This is a simplified implementation using TweetNaCl port
        val publicKey = Ed25519.publicKeyFromPrivate(seed)
        
        return Ed25519Keypair(
            publicKey = publicKey,
            privateKey = seed
        )
    }
}

