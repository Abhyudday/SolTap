package com.solanatappay.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * Manager for Convert to Fiat conversion requests.
 */
object ConversionRequestManager {
    
    private const val PREFS_NAME = "ConversionRequests"
    private const val KEY_REQUESTS = "requests"
    private const val KEY_AGENTS = "agents"
    private const val KEY_CURRENT_USER_AGENT = "is_agent"
    
    // Security deposit address
    const val SECURITY_DEPOSIT_ADDRESS = "DB3NZgGPsANwp5RBBMEK2A9ehWeN41QCELRt8WYyL8d8"
    const val SECURITY_DEPOSIT_AMOUNT = 100.0 // USD
    
    data class ConversionRequest(
        val id: String,
        val merchantPubkey: String,
        val merchantName: String,
        val solAmount: Double,
        val fiatAmount: Double,
        val currency: String,
        val timestamp: Long,
        val status: RequestStatus,
        val agentPubkey: String? = null,
        val agentName: String? = null,
        val acceptedTimestamp: Long? = null,
        val disputeRaised: Boolean = false,
        val disputeReason: String? = null
    )
    
    enum class RequestStatus {
        ACTIVE,      // Waiting for agent
        ACCEPTED,    // Agent accepted, waiting for completion/dispute
        COMPLETED,   // Successfully completed
        DISPUTED,    // Merchant raised dispute
        CANCELLED    // Cancelled by merchant
    }
    
    data class Agent(
        val pubkey: String,
        val name: String,
        val depositTxSignature: String,
        val registeredTimestamp: Long,
        val completedTrades: Int = 0,
        val rating: Double = 5.0
    )
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Check if current user is registered as agent.
     */
    fun isAgent(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CURRENT_USER_AGENT, false)
    }
    
    /**
     * Register current user as agent.
     */
    fun registerAsAgent(context: Context, agent: Agent) {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_AGENTS, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        
        val agentJson = JSONObject().apply {
            put("pubkey", agent.pubkey)
            put("name", agent.name)
            put("depositTxSignature", agent.depositTxSignature)
            put("registeredTimestamp", agent.registeredTimestamp)
            put("completedTrades", agent.completedTrades)
            put("rating", agent.rating)
        }
        
        array.put(agentJson)
        
        prefs.edit()
            .putString(KEY_AGENTS, array.toString())
            .putBoolean(KEY_CURRENT_USER_AGENT, true)
            .apply()
    }
    
    /**
     * Get current user's agent profile.
     */
    fun getCurrentAgent(context: Context): Agent? {
        val myPubkey = com.solanatappay.solana.SolanaKeyManager.getPublicKey(context) ?: return null
        return getAgentByPubkey(context, myPubkey)
    }
    
    /**
     * Get agent by public key.
     */
    fun getAgentByPubkey(context: Context, pubkey: String): Agent? {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_AGENTS, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                if (obj.getString("pubkey") == pubkey) {
                    return Agent(
                        pubkey = obj.getString("pubkey"),
                        name = obj.getString("name"),
                        depositTxSignature = obj.getString("depositTxSignature"),
                        registeredTimestamp = obj.getLong("registeredTimestamp"),
                        completedTrades = obj.optInt("completedTrades", 0),
                        rating = obj.optDouble("rating", 5.0)
                    )
                }
            } catch (e: Exception) {
                // Skip invalid records
            }
        }
        
        return null
    }
    
    /**
     * Create a new conversion request.
     */
    fun createRequest(context: Context, request: ConversionRequest) {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_REQUESTS, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        
        val requestJson = JSONObject().apply {
            put("id", request.id)
            put("merchantPubkey", request.merchantPubkey)
            put("merchantName", request.merchantName)
            put("solAmount", request.solAmount)
            put("fiatAmount", request.fiatAmount)
            put("currency", request.currency)
            put("timestamp", request.timestamp)
            put("status", request.status.name)
            put("agentPubkey", request.agentPubkey)
            put("agentName", request.agentName)
            put("acceptedTimestamp", request.acceptedTimestamp)
            put("disputeRaised", request.disputeRaised)
            put("disputeReason", request.disputeReason)
        }
        
        array.put(requestJson)
        prefs.edit().putString(KEY_REQUESTS, array.toString()).apply()
    }
    
    /**
     * Update an existing request.
     */
    fun updateRequest(context: Context, request: ConversionRequest) {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_REQUESTS, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val newArray = JSONArray()
        
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                if (obj.getString("id") == request.id) {
                    // Replace with updated request
                    newArray.put(JSONObject().apply {
                        put("id", request.id)
                        put("merchantPubkey", request.merchantPubkey)
                        put("merchantName", request.merchantName)
                        put("solAmount", request.solAmount)
                        put("fiatAmount", request.fiatAmount)
                        put("currency", request.currency)
                        put("timestamp", request.timestamp)
                        put("status", request.status.name)
                        put("agentPubkey", request.agentPubkey)
                        put("agentName", request.agentName)
                        put("acceptedTimestamp", request.acceptedTimestamp)
                        put("disputeRaised", request.disputeRaised)
                        put("disputeReason", request.disputeReason)
                    })
                } else {
                    newArray.put(obj)
                }
            } catch (e: Exception) {
                // Skip invalid records
            }
        }
        
        prefs.edit().putString(KEY_REQUESTS, newArray.toString()).apply()
    }
    
    /**
     * Get all active requests (for agents to view).
     */
    fun getActiveRequests(context: Context): List<ConversionRequest> {
        return getAllRequests(context).filter { it.status == RequestStatus.ACTIVE }
    }
    
    /**
     * Get all requests created by current user.
     */
    fun getMyMerchantRequests(context: Context): List<ConversionRequest> {
        val myPubkey = com.solanatappay.solana.SolanaKeyManager.getPublicKey(context) ?: return emptyList()
        return getAllRequests(context).filter { it.merchantPubkey == myPubkey }
    }
    
    /**
     * Get all requests accepted by current user (as agent).
     */
    fun getMyAgentRequests(context: Context): List<ConversionRequest> {
        val myPubkey = com.solanatappay.solana.SolanaKeyManager.getPublicKey(context) ?: return emptyList()
        return getAllRequests(context).filter { it.agentPubkey == myPubkey }
    }
    
    /**
     * Get request by ID.
     */
    fun getRequestById(context: Context, id: String): ConversionRequest? {
        return getAllRequests(context).find { it.id == id }
    }
    
    /**
     * Get all requests.
     */
    private fun getAllRequests(context: Context): List<ConversionRequest> {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_REQUESTS, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        
        val requests = mutableListOf<ConversionRequest>()
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                requests.add(
                    ConversionRequest(
                        id = obj.getString("id"),
                        merchantPubkey = obj.getString("merchantPubkey"),
                        merchantName = obj.getString("merchantName"),
                        solAmount = obj.getDouble("solAmount"),
                        fiatAmount = obj.getDouble("fiatAmount"),
                        currency = obj.getString("currency"),
                        timestamp = obj.getLong("timestamp"),
                        status = RequestStatus.valueOf(obj.getString("status")),
                        agentPubkey = obj.optString("agentPubkey").takeIf { it.isNotEmpty() },
                        agentName = obj.optString("agentName").takeIf { it.isNotEmpty() },
                        acceptedTimestamp = if (obj.has("acceptedTimestamp") && !obj.isNull("acceptedTimestamp")) 
                            obj.getLong("acceptedTimestamp") else null,
                        disputeRaised = obj.optBoolean("disputeRaised", false),
                        disputeReason = obj.optString("disputeReason").takeIf { it.isNotEmpty() }
                    )
                )
            } catch (e: Exception) {
                // Skip invalid records
            }
        }
        
        // Sort by timestamp (newest first)
        return requests.sortedByDescending { it.timestamp }
    }
    
    /**
     * Format timestamp to readable string.
     */
    fun formatTimestamp(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    /**
     * Get time elapsed since timestamp in minutes.
     */
    fun getMinutesElapsed(timestamp: Long): Long {
        return (System.currentTimeMillis() - timestamp) / (60 * 1000)
    }
}

