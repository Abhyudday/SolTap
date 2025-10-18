package com.solanatappay.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simple payment history manager using SharedPreferences.
 */
object PaymentHistoryManager {
    
    private const val PREFS_NAME = "PaymentHistory"
    private const val KEY_MERCHANT_PAYMENTS = "merchant_payments"
    private const val KEY_BUYER_PAYMENTS = "buyer_payments"
    private const val MAX_HISTORY_SIZE = 100
    
    data class PaymentRecord(
        val signature: String,
        val amount: Long,
        val otherParty: String,
        val timestamp: Long,
        val type: PaymentType
    )
    
    enum class PaymentType {
        RECEIVED, // Merchant received payment
        SENT      // Buyer sent payment
    }
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Add a merchant payment (received from buyer).
     */
    fun addMerchantPayment(
        context: Context,
        signature: String,
        amountLamports: Long,
        buyerPubkey: String
    ) {
        val record = PaymentRecord(
            signature = signature,
            amount = amountLamports,
            otherParty = buyerPubkey,
            timestamp = System.currentTimeMillis(),
            type = PaymentType.RECEIVED
        )
        addRecord(context, KEY_MERCHANT_PAYMENTS, record)
    }
    
    /**
     * Add a buyer payment (sent to merchant).
     */
    fun addBuyerPayment(
        context: Context,
        signature: String,
        amountLamports: Long,
        merchantPubkey: String
    ) {
        val record = PaymentRecord(
            signature = signature,
            amount = amountLamports,
            otherParty = merchantPubkey,
            timestamp = System.currentTimeMillis(),
            type = PaymentType.SENT
        )
        addRecord(context, KEY_BUYER_PAYMENTS, record)
    }
    
    /**
     * Get merchant payment history (newest first).
     */
    fun getMerchantPayments(context: Context): List<PaymentRecord> {
        return getRecords(context, KEY_MERCHANT_PAYMENTS)
    }
    
    /**
     * Get buyer payment history (newest first).
     */
    fun getBuyerPayments(context: Context): List<PaymentRecord> {
        return getRecords(context, KEY_BUYER_PAYMENTS)
    }
    
    /**
     * Clear all payment history.
     */
    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
    
    private fun addRecord(context: Context, key: String, record: PaymentRecord) {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(key, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        
        // Add new record at the beginning
        val recordJson = JSONObject().apply {
            put("signature", record.signature)
            put("amount", record.amount)
            put("otherParty", record.otherParty)
            put("timestamp", record.timestamp)
            put("type", record.type.name)
        }
        
        // Create new array with new record first
        val newArray = JSONArray()
        newArray.put(recordJson)
        
        // Add existing records (up to MAX_HISTORY_SIZE - 1)
        for (i in 0 until minOf(array.length(), MAX_HISTORY_SIZE - 1)) {
            newArray.put(array.getJSONObject(i))
        }
        
        prefs.edit().putString(key, newArray.toString()).apply()
    }
    
    private fun getRecords(context: Context, key: String): List<PaymentRecord> {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(key, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        
        val records = mutableListOf<PaymentRecord>()
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                records.add(
                    PaymentRecord(
                        signature = obj.getString("signature"),
                        amount = obj.getLong("amount"),
                        otherParty = obj.getString("otherParty"),
                        timestamp = obj.getLong("timestamp"),
                        type = PaymentType.valueOf(obj.getString("type"))
                    )
                )
            } catch (e: Exception) {
                // Skip invalid records
            }
        }
        
        return records
    }
    
    /**
     * Format timestamp to readable date.
     */
    fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    /**
     * Format lamports to SOL.
     */
    fun formatAmount(lamports: Long): String {
        val sol = lamports / 1_000_000_000.0
        return "%.4f SOL".format(sol)
    }
}

