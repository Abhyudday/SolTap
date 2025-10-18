package com.solanatappay.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.util.Log
import com.solanatappay.solana.Base58
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * NFC Helper for encoding/decoding payment requests via NDEF messages.
 * 
 * Payload format:
 * - merchantPubkey: Base58 encoded Solana public key
 * - amountLamports: Long value
 * - orderId: Long value for replay protection
 */
object NfcHelper {
    
    private const val TAG = "NfcHelper"
    private const val MIME_TYPE = "application/solana-payment"
    
    /**
     * Payment data class matching BLE PaymentPayload structure.
     */
    data class PaymentData(
        val merchantPubkey: String,
        val amountLamports: Long,
        val orderId: Long
    )
    
    /**
     * Create NDEF message from payment data.
     * Uses a custom MIME type record with compact binary payload.
     * Includes an Android Application Record (AAR) to ensure Android launches our app.
     */
    fun createNdefMessage(data: PaymentData): NdefMessage {
        Log.d(TAG, "Creating NDEF message for ${data.amountLamports} lamports to ${data.merchantPubkey}")
        
        // Encode as binary for compact size (48 bytes)
        val payload = encodePayloadBinary(data)
        
        // Create MIME type record
        val mimeRecord = NdefRecord(
            NdefRecord.TNF_MIME_MEDIA,
            MIME_TYPE.toByteArray(Charset.forName("US-ASCII")),
            ByteArray(0), // No ID
            payload
        )
        
        // Also add a text record for debugging/fallback
        val textRecord = createTextRecord(data)
        
        // Add Android Application Record (AAR) to tell Android which app to launch
        // This ensures the tag will open our app even if it's not running
        val aarRecord = NdefRecord.createApplicationRecord("com.solanatappay")
        
        return NdefMessage(arrayOf(mimeRecord, textRecord, aarRecord))
    }
    
    /**
     * Parse NDEF message to extract payment data.
     */
    fun parseNdefMessage(message: NdefMessage): PaymentData? {
        Log.d(TAG, "Parsing NDEF message with ${message.records.size} records")
        
        for (record in message.records) {
            // Look for our custom MIME type record
            if (record.tnf == NdefRecord.TNF_MIME_MEDIA) {
                val mimeType = String(record.type, Charset.forName("US-ASCII"))
                Log.d(TAG, "Found MIME record: $mimeType")
                
                if (mimeType == MIME_TYPE) {
                    return try {
                        decodePayloadBinary(record.payload)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode binary payload: ${e.message}")
                        null
                    }
                }
            }
            
            // Also try parsing as text/JSON for backward compatibility
            if (record.tnf == NdefRecord.TNF_WELL_KNOWN && 
                record.type.contentEquals(NdefRecord.RTD_TEXT)) {
                try {
                    val text = parseTextRecord(record)
                    if (text?.contains("merchantPubkey") == true) {
                        return decodePayloadJson(text)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Not a JSON payment record")
                }
            }
        }
        
        Log.w(TAG, "No valid payment data found in NDEF message")
        return null
    }
    
    /**
     * Encode payment data as compact binary (48 bytes).
     * Format: merchantPubkey (32 bytes) + amountLamports (8 bytes) + orderId (8 bytes)
     */
    private fun encodePayloadBinary(data: PaymentData): ByteArray {
        val merchantBytes = Base58.decode(data.merchantPubkey)
        
        return ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(merchantBytes) // 32 bytes
            putLong(data.amountLamports) // 8 bytes
            putLong(data.orderId) // 8 bytes
        }.array()
    }
    
    /**
     * Decode binary payload to payment data.
     */
    private fun decodePayloadBinary(payload: ByteArray): PaymentData? {
        if (payload.size < 48) {
            Log.e(TAG, "Payload too small: ${payload.size} bytes")
            return null
        }
        
        return try {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            
            val merchantBytes = ByteArray(32)
            buffer.get(merchantBytes)
            val merchantPubkey = Base58.encode(merchantBytes)
            
            val amountLamports = buffer.getLong()
            val orderId = buffer.getLong()
            
            PaymentData(merchantPubkey, amountLamports, orderId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode binary: ${e.message}")
            null
        }
    }
    
    /**
     * Create a text record for debugging/fallback (human-readable JSON).
     */
    private fun createTextRecord(data: PaymentData): NdefRecord {
        val json = JSONObject().apply {
            put("merchantPubkey", data.merchantPubkey)
            put("amountLamports", data.amountLamports)
            put("orderId", data.orderId)
        }.toString()
        
        // Text record with language code
        val languageCode = "en"
        val languageCodeBytes = languageCode.toByteArray(Charset.forName("US-ASCII"))
        val textBytes = json.toByteArray(Charset.forName("UTF-8"))
        
        val payload = ByteArray(1 + languageCodeBytes.size + textBytes.size)
        payload[0] = languageCodeBytes.size.toByte() // Status byte with language code length
        System.arraycopy(languageCodeBytes, 0, payload, 1, languageCodeBytes.size)
        System.arraycopy(textBytes, 0, payload, 1 + languageCodeBytes.size, textBytes.size)
        
        return NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), payload)
    }
    
    /**
     * Parse text record to string.
     */
    private fun parseTextRecord(record: NdefRecord): String? {
        return try {
            val payload = record.payload
            val languageCodeLength = (payload[0].toInt() and 0x3F)
            val text = String(
                payload,
                1 + languageCodeLength,
                payload.size - 1 - languageCodeLength,
                Charset.forName("UTF-8")
            )
            text
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Decode JSON payload (fallback method).
     */
    private fun decodePayloadJson(json: String): PaymentData? {
        return try {
            val obj = JSONObject(json)
            PaymentData(
                merchantPubkey = obj.getString("merchantPubkey"),
                amountLamports = obj.getLong("amountLamports"),
                orderId = obj.getLong("orderId")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON: ${e.message}")
            null
        }
    }
    
    /**
     * Check if device supports NFC.
     */
    fun isNfcSupported(context: android.content.Context): Boolean {
        val nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(context)
        return nfcAdapter != null
    }
    
    /**
     * Check if NFC is enabled.
     */
    fun isNfcEnabled(context: android.content.Context): Boolean {
        val nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(context)
        return nfcAdapter?.isEnabled == true
    }
}

