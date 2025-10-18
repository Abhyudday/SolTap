package com.solanatappay.ble

import com.solanatappay.solana.Base58
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * BLE payment request payload structure.
 * 
 * Payload format (compact for fast BLE transmission):
 * - merchantPubkey: 32 bytes (Solana public key)
 * - amountLamports: 8 bytes (long, little-endian)
 * - orderId: 8 bytes (random nonce for replay protection)
 * 
 * Total: 48 bytes (fits in BLE manufacturer data with 4-byte prefix)
 * 
 * Alternative: Could use shorter 16-byte merchant ID + amount for < 31 bytes
 * if we implement a lookup service.
 */
data class PaymentPayload(
    val merchantPubkey: String,  // Base58 encoded
    val amountLamports: Long,
    val orderId: Long
) {
    
    companion object {
        // BLE Service UUID for payment requests
        // CONFIGURABLE: Change this UUID if needed, but keep it consistent across merchant/buyer
        val SERVICE_UUID: UUID = UUID.fromString("0000FEE0-0000-1000-8000-00805F9B34FB")
        
        // GATT Characteristic UUID for payment data
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000FEE1-0000-1000-8000-00805F9B34FB")
        
        // Manufacturer ID for payload (using a test ID)
        const val MANUFACTURER_ID = 0xFFFF
        
        /**
         * Serialize payload to bytes for BLE transmission.
         */
        fun serialize(payload: PaymentPayload): ByteArray {
            val merchantBytes = Base58.decode(payload.merchantPubkey)
            
            return ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(merchantBytes) // 32 bytes
                putLong(payload.amountLamports) // 8 bytes
                putLong(payload.orderId) // 8 bytes
            }.array()
        }
        
        /**
         * Deserialize payload from BLE advertisement data.
         */
        fun deserialize(data: ByteArray): PaymentPayload? {
            if (data.size < 48) {
                return null
            }
            
            return try {
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                
                val merchantBytes = ByteArray(32)
                buffer.get(merchantBytes)
                val merchantPubkey = Base58.encode(merchantBytes)
                
                val amountLamports = buffer.getLong()
                val orderId = buffer.getLong()
                
                PaymentPayload(merchantPubkey, amountLamports, orderId)
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * Generate random order ID for replay protection.
         */
        fun generateOrderId(): Long {
            return Random().nextLong()
        }
    }
    
    /**
     * Convert lamports to SOL (1 SOL = 1 billion lamports).
     */
    fun toSol(): Double {
        return amountLamports / 1_000_000_000.0
    }
}

/**
 * BLE Configuration constants.
 * CONFIGURABLE: Adjust these for optimal performance in your environment.
 */
object BleConfig {
    // Advertise mode: LOW_LATENCY for fastest discovery
    const val ADVERTISE_MODE_LOW_LATENCY = 0 // AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    
    // TX power: HIGH for maximum range
    const val ADVERTISE_TX_POWER_HIGH = 3 // AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
    
    // Scan mode: LOW_LATENCY for fastest detection
    const val SCAN_MODE_LOW_LATENCY = 2 // ScanSettings.SCAN_MODE_LOW_LATENCY
    
    // Scan report delay: 0 for immediate callback
    const val SCAN_REPORT_DELAY_MS = 0L
    
    // Advertise timeout: 0 for indefinite (controlled by foreground service)
    const val ADVERTISE_TIMEOUT_MS = 0
}

