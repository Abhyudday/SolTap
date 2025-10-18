package com.solanatappay.solana

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Solana RPC client for devnet transactions.
 * Handles transaction building, signing, and submission.
 * 
 * RPC endpoint is configurable - default is devnet.
 * Change SOLANA_RPC_URL constant for mainnet (NOT recommended for this prototype).
 */
class SolanaRpcClient(private val context: android.content.Context) {
    
    companion object {
        private const val TAG = "SolanaRPC"
        private const val PREFS_NAME = "NetworkSettings"
        private const val PREFS_NETWORK_KEY = "selected_network"
        const val NETWORK_DEVNET = "devnet"
        const val NETWORK_MAINNET = "mainnet"
        
        // System Program ID (for transfers)
        private val SYSTEM_PROGRAM_ID = Base58.decode("11111111111111111111111111111111")
        
        /**
         * Get the selected network (devnet or mainnet).
         */
        fun getSelectedNetwork(context: android.content.Context): String {
            return context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(PREFS_NETWORK_KEY, NETWORK_DEVNET) ?: NETWORK_DEVNET
        }
        
        /**
         * Set the selected network (devnet or mainnet).
         */
        fun setSelectedNetwork(context: android.content.Context, network: String) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(PREFS_NETWORK_KEY, network)
                .apply()
        }
    }
    
    private fun getRpcUrl(): String {
        val network = getSelectedNetwork(context)
        return when (network) {
            NETWORK_MAINNET -> "https://api.mainnet-beta.solana.com"
            else -> "https://api.devnet.solana.com"
        }
    }
    
    private fun isDevnet(): Boolean {
        return getSelectedNetwork(context) == NETWORK_DEVNET
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    /**
     * Get recent blockhash required for transaction.
     */
    suspend fun getLatestBlockhash(): BlockhashResult = withContext(Dispatchers.IO) {
        val jsonRequest = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "getLatestBlockhash")
            put("params", JSONArray().apply {
                put(JSONObject().apply {
                    put("commitment", "confirmed")
                })
            })
        }
        
        val response = makeRpcCall(jsonRequest)
        val result = response.getJSONObject("result")
        val value = result.getJSONObject("value")
        
        BlockhashResult(
            blockhash = value.getString("blockhash"),
            lastValidBlockHeight = value.getLong("lastValidBlockHeight")
        )
    }
    
    /**
     * Build, sign, and send a SOL transfer transaction.
     * 
     * @param fromPrivateKey 32-byte private key (seed) of sender
     * @param fromPubkey Base58 public key of sender
     * @param toPubkey Base58 public key of recipient
     * @param amountLamports Amount in lamports (1 SOL = 1_000_000_000 lamports)
     * @return Transaction signature (base58)
     */
    suspend fun sendSolTransfer(
        fromPrivateKey: ByteArray,
        fromPubkey: String,
        toPubkey: String,
        amountLamports: Long
    ): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Building transfer: $amountLamports lamports from $fromPubkey to $toPubkey")
        
        // Get recent blockhash
        val blockhash = getLatestBlockhash()
        Log.d(TAG, "Got blockhash: ${blockhash.blockhash}")
        
        // Build transaction
        val transaction = buildTransferTransaction(
            fromPubkey = fromPubkey,
            toPubkey = toPubkey,
            amountLamports = amountLamports,
            recentBlockhash = blockhash.blockhash
        )
        
        // Sign transaction
        val signedTransaction = signTransaction(transaction, fromPrivateKey)
        Log.d(TAG, "Transaction signed, size: ${signedTransaction.size} bytes")
        
        // Send transaction
        val signature = sendTransaction(signedTransaction)
        Log.d(TAG, "Transaction sent: $signature")
        
        signature
    }
    
    /**
     * Get account balance in lamports.
     */
    suspend fun getBalance(pubkey: String): Long = withContext(Dispatchers.IO) {
        val jsonRequest = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "getBalance")
            put("params", JSONArray().apply {
                put(pubkey)
            })
        }
        
        val response = makeRpcCall(jsonRequest)
        response.getJSONObject("result").getLong("value")
    }
    
    /**
     * Build a transfer transaction (unsigned).
     */
    private fun buildTransferTransaction(
        fromPubkey: String,
        toPubkey: String,
        amountLamports: Long,
        recentBlockhash: String
    ): ByteArray {
        val fromPubkeyBytes = Base58.decode(fromPubkey)
        val toPubkeyBytes = Base58.decode(toPubkey)
        val blockhashBytes = Base58.decode(recentBlockhash)
        
        // Build transfer instruction
        val instruction = buildTransferInstruction(
            from = fromPubkeyBytes,
            to = toPubkeyBytes,
            lamports = amountLamports
        )
        
        // Build message
        val message = buildMessage(
            instructions = listOf(instruction),
            feePayer = fromPubkeyBytes,
            recentBlockhash = blockhashBytes,
            signers = listOf(fromPubkeyBytes)
        )
        
        return message
    }
    
    /**
     * Build a SystemProgram Transfer instruction.
     */
    private fun buildTransferInstruction(
        from: ByteArray,
        to: ByteArray,
        lamports: Long
    ): TransactionInstruction {
        // Transfer instruction data: [instruction_index: u32, lamports: u64]
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(2) // Transfer instruction index
            putLong(lamports)
        }.array()
        
        return TransactionInstruction(
            programId = SYSTEM_PROGRAM_ID,
            accounts = listOf(
                AccountMeta(from, isSigner = true, isWritable = true),
                AccountMeta(to, isSigner = false, isWritable = true)
            ),
            data = data
        )
    }
    
    /**
     * Build transaction message.
     */
    private fun buildMessage(
        instructions: List<TransactionInstruction>,
        feePayer: ByteArray,
        recentBlockhash: ByteArray,
        signers: List<ByteArray>
    ): ByteArray {
        // Collect all account keys
        val accountKeys = mutableListOf<ByteArray>()
        accountKeys.add(feePayer)
        
        instructions.forEach { ix ->
            ix.accounts.forEach { acc ->
                if (!accountKeys.any { it.contentEquals(acc.pubkey) }) {
                    accountKeys.add(acc.pubkey)
                }
            }
            if (!accountKeys.any { it.contentEquals(ix.programId) }) {
                accountKeys.add(ix.programId)
            }
        }
        
        // Build message
        val buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        
        // Message header
        buffer.put(signers.size.toByte()) // numRequiredSignatures
        buffer.put(0.toByte()) // numReadonlySignedAccounts
        buffer.put(0.toByte()) // numReadonlyUnsignedAccounts
        
        // Account keys
        compactU16Encode(buffer, accountKeys.size)
        accountKeys.forEach { buffer.put(it) }
        
        // Recent blockhash
        buffer.put(recentBlockhash)
        
        // Instructions
        compactU16Encode(buffer, instructions.size)
        instructions.forEach { ix ->
            val programIdIndex = accountKeys.indexOfFirst { it.contentEquals(ix.programId) }
            buffer.put(programIdIndex.toByte())
            
            // Account indices
            compactU16Encode(buffer, ix.accounts.size)
            ix.accounts.forEach { acc ->
                val accIndex = accountKeys.indexOfFirst { it.contentEquals(acc.pubkey) }
                buffer.put(accIndex.toByte())
            }
            
            // Instruction data
            compactU16Encode(buffer, ix.data.size)
            buffer.put(ix.data)
        }
        
        val messageSize = buffer.position()
        return ByteArray(messageSize).apply {
            buffer.rewind()
            buffer.get(this)
        }
    }
    
    /**
     * Sign transaction with private key.
     * Returns serialized signed transaction.
     */
    private fun signTransaction(message: ByteArray, privateKey: ByteArray): ByteArray {
        // Sign message
        val signature = Ed25519.sign(message, privateKey)
        
        // Build signed transaction: [numSignatures: compactU16, signature, message]
        val buffer = ByteBuffer.allocate(1 + signature.size + message.size)
        compactU16Encode(buffer, 1) // One signature
        buffer.put(signature)
        buffer.put(message)
        
        return buffer.array()
    }
    
    /**
     * Send signed transaction to Solana network.
     */
    private fun sendTransaction(signedTransaction: ByteArray): String {
        val base64Tx = Base64.encodeToString(signedTransaction, Base64.NO_WRAP)
        
        val jsonRequest = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "sendTransaction")
            put("params", JSONArray().apply {
                put(base64Tx)
                put(JSONObject().apply {
                    put("encoding", "base64")
                    put("skipPreflight", false)
                    put("preflightCommitment", "confirmed")
                })
            })
        }
        
        val response = makeRpcCall(jsonRequest)
        return response.getString("result")
    }
    
    /**
     * Make RPC call to Solana node.
     */
    private fun makeRpcCall(jsonRequest: JSONObject): JSONObject {
        val requestBody = jsonRequest.toString()
            .toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(getRpcUrl())
            .post(requestBody)
            .build()
        
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("Empty response")
            
            if (!response.isSuccessful) {
                throw Exception("RPC call failed: ${response.code} - $body")
            }
            
            val json = JSONObject(body)
            
            if (json.has("error")) {
                val error = json.getJSONObject("error")
                throw Exception("RPC error: ${error.getString("message")}")
            }
            
            return json
        }
    }
    
    /**
     * Compact-u16 encoding (used by Solana for variable-length integers).
     */
    private fun compactU16Encode(buffer: ByteBuffer, value: Int) {
        when {
            value < 128 -> buffer.put(value.toByte())
            value < 16384 -> {
                buffer.put((value and 0x7F or 0x80).toByte())
                buffer.put((value shr 7).toByte())
            }
            else -> {
                buffer.put((value and 0x7F or 0x80).toByte())
                buffer.put((value shr 7 and 0x7F or 0x80).toByte())
                buffer.put((value shr 14).toByte())
            }
        }
    }
    
    // Data classes
    data class BlockhashResult(
        val blockhash: String,
        val lastValidBlockHeight: Long
    )
    
    data class TransactionInstruction(
        val programId: ByteArray,
        val accounts: List<AccountMeta>,
        val data: ByteArray
    )
    
    data class AccountMeta(
        val pubkey: ByteArray,
        val isSigner: Boolean,
        val isWritable: Boolean
    )
    
    /**
     * Get Solana Explorer URL for a transaction.
     */
    fun getExplorerUrl(signature: String): String {
        val cluster = if (isDevnet()) "?cluster=devnet" else ""
        return "https://explorer.solana.com/tx/$signature$cluster"
    }
}

