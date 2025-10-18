package com.solanatappay.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Helper class for buyer to read payment data from merchant's HCE service.
 */
object HceReader {
    
    private const val TAG = "HceReader"
    
    // ISO 7816-4 APDU commands
    private val SELECT_APDU = byteArrayOf(
        0x00.toByte(), // CLA
        0xA4.toByte(), // INS (SELECT)
        0x04.toByte(), // P1
        0x00.toByte(), // P2
        0x0B.toByte(), // LC (length of AID)
        // AID: F0534F4C414E4150415900 (11 bytes)
        0xF0.toByte(), 0x53.toByte(), 0x4F.toByte(), 0x4C.toByte(),
        0x41.toByte(), 0x4E.toByte(), 0x41.toByte(), 0x50.toByte(),
        0x41.toByte(), 0x59.toByte(), 0x00.toByte(),
        0x00.toByte()  // LE (expected response length)
    )
    
    private val GET_DATA_APDU = byteArrayOf(
        0x00.toByte(), // CLA
        0xCA.toByte(), // INS (GET DATA)
        0x00.toByte(), // P1
        0x00.toByte(), // P2
        0x00.toByte()  // LE (max expected length)
    )
    
    /**
     * Read payment data from merchant's HCE service via NFC.
     * 
     * @param tag The NFC tag (merchant phone acting as tag via HCE)
     * @return PaymentData if successful, null otherwise
     */
    fun readPaymentData(tag: Tag): NfcHelper.PaymentData? {
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.e(TAG, "IsoDep not available for this tag")
            return null
        }
        
        try {
            isoDep.connect()
            Log.d(TAG, "Connected to HCE service")
            
            // Step 1: Select the application by AID
            Log.d(TAG, "Sending SELECT APDU")
            val selectResponse = isoDep.transceive(SELECT_APDU)
            
            if (!isSuccess(selectResponse)) {
                Log.e(TAG, "SELECT failed: ${selectResponse.toHexString()}")
                return null
            }
            Log.d(TAG, "SELECT successful")
            
            // Step 2: Get payment data
            Log.d(TAG, "Sending GET DATA APDU")
            val dataResponse = isoDep.transceive(GET_DATA_APDU)
            
            if (!isSuccess(dataResponse)) {
                Log.e(TAG, "GET DATA failed: ${dataResponse.toHexString()}")
                return null
            }
            Log.d(TAG, "GET DATA successful: ${dataResponse.size} bytes")
            
            // Parse response (remove status bytes at end)
            val payload = dataResponse.copyOfRange(0, dataResponse.size - 2)
            return parsePaymentData(payload)
            
        } catch (e: IOException) {
            Log.e(TAG, "Communication error: ${e.message}", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading payment data: ${e.message}", e)
            return null
        } finally {
            try {
                isoDep.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing IsoDep: ${e.message}")
            }
        }
    }
    
    private fun isSuccess(response: ByteArray): Boolean {
        // Check for 0x9000 status (success)
        return response.size >= 2 &&
               response[response.size - 2] == 0x90.toByte() &&
               response[response.size - 1] == 0x00.toByte()
    }
    
    private fun parsePaymentData(payload: ByteArray): NfcHelper.PaymentData? {
        if (payload.size < 48) {
            Log.e(TAG, "Payload too small: ${payload.size} bytes")
            return null
        }
        
        return try {
            val buffer = ByteBuffer.wrap(payload)
            
            // Read merchant public key (32 bytes)
            val merchantBytes = ByteArray(32)
            buffer.get(merchantBytes)
            val merchantPubkey = com.solanatappay.solana.Base58.encode(merchantBytes)
            
            // Read amount (8 bytes)
            val amountLamports = buffer.getLong()
            
            // Read order ID (8 bytes)
            val orderId = buffer.getLong()
            
            NfcHelper.PaymentData(merchantPubkey, amountLamports, orderId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse payment data: ${e.message}", e)
            null
        }
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}

