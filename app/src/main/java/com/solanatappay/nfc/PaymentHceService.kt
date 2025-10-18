package com.solanatappay.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.ByteBuffer

/**
 * Host Card Emulation (HCE) Service for phone-to-phone NFC.
 * Merchant phone acts as an NFC tag, buyer phone reads from it.
 */
class PaymentHceService : HostApduService() {
    
    companion object {
        private const val TAG = "PaymentHceService"
        
        // ISO 7816-4 APDU commands
        private val SELECT_APDU_HEADER = byteArrayOf(
            0x00.toByte(), // CLA
            0xA4.toByte(), // INS (SELECT)
            0x04.toByte(), // P1
            0x00.toByte()  // P2
        )
        
        private val GET_DATA_APDU = byteArrayOf(
            0x00.toByte(), // CLA
            0xCA.toByte(), // INS (GET DATA)
            0x00.toByte(), // P1
            0x00.toByte()  // P2
        )
        
        // Response codes
        private val SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val ERROR = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        
        // Our Application ID (AID) - must match aid_list.xml
        private const val AID = "F0534F4C414E4150415900" // "SOLANAPAY" in hex + F0 prefix
        
        // Static payment data (set by MerchantActivity)
        @Volatile
        var currentPaymentData: NfcHelper.PaymentData? = null
    }
    
    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "HCE deactivated: reason=$reason")
    }
    
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) {
            Log.w(TAG, "Received null APDU")
            return ERROR
        }
        
        Log.d(TAG, "Received APDU: ${commandApdu.toHexString()}")
        
        // Check if it's a SELECT command for our AID
        if (isSelectAidApdu(commandApdu)) {
            Log.d(TAG, "SELECT AID command received")
            return SUCCESS
        }
        
        // Check if it's a GET DATA command
        if (isGetDataApdu(commandApdu)) {
            Log.d(TAG, "GET DATA command received")
            return handleGetData()
        }
        
        Log.w(TAG, "Unknown APDU command")
        return ERROR
    }
    
    private fun isSelectAidApdu(apdu: ByteArray): Boolean {
        if (apdu.size < 5) return false
        
        // Check APDU header
        return apdu[0] == SELECT_APDU_HEADER[0] &&
               apdu[1] == SELECT_APDU_HEADER[1] &&
               apdu[2] == SELECT_APDU_HEADER[2] &&
               apdu[3] == SELECT_APDU_HEADER[3]
    }
    
    private fun isGetDataApdu(apdu: ByteArray): Boolean {
        if (apdu.size < 4) return false
        
        return apdu[0] == GET_DATA_APDU[0] &&
               apdu[1] == GET_DATA_APDU[1] &&
               apdu[2] == GET_DATA_APDU[2] &&
               apdu[3] == GET_DATA_APDU[3]
    }
    
    private fun handleGetData(): ByteArray {
        val paymentData = currentPaymentData
        
        if (paymentData == null) {
            Log.e(TAG, "No payment data available")
            return ERROR
        }
        
        try {
            // Encode payment data as compact binary (48 bytes)
            val payload = encodePaymentData(paymentData)
            
            // Return payload + success code
            val response = ByteArray(payload.size + 2)
            System.arraycopy(payload, 0, response, 0, payload.size)
            response[payload.size] = SUCCESS[0]
            response[payload.size + 1] = SUCCESS[1]
            
            Log.d(TAG, "Returning payment data: ${payload.size} bytes")
            return response
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode payment data: ${e.message}", e)
            return ERROR
        }
    }
    
    private fun encodePaymentData(data: NfcHelper.PaymentData): ByteArray {
        val merchantBytes = com.solanatappay.solana.Base58.decode(data.merchantPubkey)
        
        return ByteBuffer.allocate(48).apply {
            put(merchantBytes) // 32 bytes
            putLong(data.amountLamports) // 8 bytes
            putLong(data.orderId) // 8 bytes
        }.array()
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}

