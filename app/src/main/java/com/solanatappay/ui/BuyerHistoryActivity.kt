package com.solanatappay.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.solanatappay.R
import com.solanatappay.data.PaymentHistoryManager
import com.solanatappay.solana.SolanaRpcClient

/**
 * Buyer payment history showing all sent payments.
 */
class BuyerHistoryActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var totalSpentText: TextView
    private lateinit var paymentCountText: TextView
    
    private lateinit var rpcClient: SolanaRpcClient
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_buyer_history)
        
        // Initialize RPC client
        rpcClient = SolanaRpcClient(this)
        
        // Set up toolbar
        findViewById<MaterialButton>(R.id.backButton).setOnClickListener {
            finish()
        }
        
        // Initialize views
        recyclerView = findViewById(R.id.paymentsRecyclerView)
        emptyView = findViewById(R.id.emptyView)
        totalSpentText = findViewById(R.id.totalSpentText)
        paymentCountText = findViewById(R.id.paymentCountText)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        loadPayments()
    }
    
    private fun loadPayments() {
        val payments = PaymentHistoryManager.getBuyerPayments(this)
        
        if (payments.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            
            // Calculate total spent
            val totalLamports = payments.sumOf { it.amount }
            val totalSol = totalLamports / 1_000_000_000.0
            totalSpentText.text = "%.4f SOL".format(totalSol)
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
            val toText: TextView = view.findViewById(R.id.toText)
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
            holder.toText.text = "To: ${payment.otherParty.take(8)}...${payment.otherParty.takeLast(8)}"
            holder.dateText.text = PaymentHistoryManager.formatTimestamp(payment.timestamp)
            holder.signatureText.text = "${payment.signature.take(12)}...${payment.signature.takeLast(12)}"
            
            holder.itemView.setOnClickListener {
                onItemClick(payment)
            }
        }
        
        override fun getItemCount() = payments.size
    }
}

