package com.solanatappay.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.solanatappay.R
import com.solanatappay.data.PaymentHistoryManager
import com.solanatappay.solana.SolanaKeyManager
import com.solanatappay.solana.SolanaRpcClient

/**
 * Merchant dashboard showing all received payments.
 */
class MerchantDashboardActivity : AppCompatActivity() {
    
    companion object {
        private const val ONRAMP_APP_ID = "1" // Replace with your actual Onramp App ID
        private const val ONRAMP_OFFRAMP_BASE_URL = "https://onramp.money/main/sell/"
    }
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var totalRevenueText: TextView
    private lateinit var paymentCountText: TextView
    private lateinit var convertToFiatButton: MaterialButton
    
    private lateinit var rpcClient: SolanaRpcClient
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_merchant_dashboard)
        
        // Initialize RPC client
        rpcClient = SolanaRpcClient(this)
        
        // Set up toolbar
        findViewById<MaterialButton>(R.id.backButton).setOnClickListener {
            finish()
        }
        
        // Initialize views
        recyclerView = findViewById(R.id.paymentsRecyclerView)
        emptyView = findViewById(R.id.emptyView)
        totalRevenueText = findViewById(R.id.totalRevenueText)
        paymentCountText = findViewById(R.id.paymentCountText)
        convertToFiatButton = findViewById(R.id.convertToFiatButton)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        // Set up Convert to Fiat button
        convertToFiatButton.setOnClickListener {
            openOnrampOfframp()
        }
        
        loadPayments()
    }
    
    private fun loadPayments() {
        val payments = PaymentHistoryManager.getMerchantPayments(this)
        
        if (payments.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            
            // Calculate total revenue
            val totalLamports = payments.sumOf { it.amount }
            val totalSol = totalLamports / 1_000_000_000.0
            totalRevenueText.text = "%.4f SOL".format(totalSol)
            paymentCountText.text = "%d payments".format(payments.size)
            
            // Set up adapter
            recyclerView.adapter = PaymentAdapter(payments) { payment ->
                // Open in explorer
                val url = rpcClient.getExplorerUrl(payment.signature)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }
    
    /**
     * Simple RecyclerView adapter for payments.
     */
    private class PaymentAdapter(
        private val payments: List<PaymentHistoryManager.PaymentRecord>,
        private val onItemClick: (PaymentHistoryManager.PaymentRecord) -> Unit
    ) : RecyclerView.Adapter<PaymentAdapter.ViewHolder>() {
        
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val amountText: TextView = view.findViewById(R.id.amountText)
            val fromText: TextView = view.findViewById(R.id.fromText)
            val dateText: TextView = view.findViewById(R.id.dateText)
            val signatureText: TextView = view.findViewById(R.id.signatureText)
        }
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_payment, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val payment = payments[position]
            
            holder.amountText.text = PaymentHistoryManager.formatAmount(payment.amount)
            holder.fromText.text = "From: ${payment.otherParty.take(8)}...${payment.otherParty.takeLast(8)}"
            holder.dateText.text = PaymentHistoryManager.formatTimestamp(payment.timestamp)
            holder.signatureText.text = "${payment.signature.take(12)}...${payment.signature.takeLast(12)}"
            
            holder.itemView.setOnClickListener {
                onItemClick(payment)
            }
        }
        
        override fun getItemCount() = payments.size
    }
    
    /**
     * Opens the Onramp offramp widget to allow merchants to convert crypto to fiat.
     * Pre-fills the wallet address and sets SOL as the default coin.
     */
    private fun openOnrampOfframp() {
        // Get merchant's wallet address
        val walletAddress = SolanaKeyManager.getPublicKey(this)
        
        if (walletAddress == null) {
            Toast.makeText(this, "Wallet not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Build the Onramp offramp URL with pre-filled parameters
        val urlBuilder = Uri.parse(ONRAMP_OFFRAMP_BASE_URL).buildUpon()
            .appendQueryParameter("appId", ONRAMP_APP_ID)
            .appendQueryParameter("walletAddress", walletAddress)
            .appendQueryParameter("coinCode", "sol")
            .appendQueryParameter("network", "mainnet")
        
        // Optional: Pre-fill the amount if there's revenue
        val payments = PaymentHistoryManager.getMerchantPayments(this)
        if (payments.isNotEmpty()) {
            val totalLamports = payments.sumOf { it.amount }
            val totalSol = totalLamports / 1_000_000_000.0
            // Only pre-fill if there's a reasonable amount
            if (totalSol > 0.01) {
                urlBuilder.appendQueryParameter("coinAmount", totalSol.toString())
            }
        }
        
        val url = urlBuilder.build().toString()
        
        // Open in browser
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open Onramp widget: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

