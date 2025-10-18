package com.solanatappay.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.solanatappay.R
import com.solanatappay.data.ConversionRequestManager
import com.solanatappay.data.ConversionRequestManager.ConversionRequest
import com.solanatappay.data.ConversionRequestManager.RequestStatus
import com.solanatappay.solana.SolanaKeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Activity for Convert to Fiat P2P marketplace.
 * Shows different views for Merchants and Agents.
 */
class ConvertToFiatActivity : AppCompatActivity() {
    
    companion object {
        private const val COINGECKO_API_URL = "https://api.coingecko.com/api/v3/simple/price?ids=solana&vs_currencies=usd"
        private const val DISPUTE_TIMEOUT_MS = 2 * 60 * 1000L // 2 minutes
    }
    
    private lateinit var modeToggle: MaterialButtonToggleGroup
    private lateinit var merchantButton: MaterialButton
    private lateinit var agentButton: MaterialButton
    private lateinit var merchantContainer: LinearLayout
    private lateinit var agentContainer: LinearLayout
    private lateinit var backButton: MaterialButton
    
    // Merchant views
    private lateinit var createRequestButton: MaterialButton
    private lateinit var merchantRequestsList: RecyclerView
    
    // Agent views
    private lateinit var registerAgentButton: MaterialButton
    private lateinit var agentStatusText: TextView
    private lateinit var activeRequestsList: RecyclerView
    private lateinit var myAgentRequestsList: RecyclerView
    
    private var currentMode = Mode.MERCHANT
    private var solPriceUsd: Double = 150.0 // Default
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    enum class Mode {
        MERCHANT, AGENT
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_convert_to_fiat)
        
        // Initialize views
        modeToggle = findViewById(R.id.modeToggle)
        merchantButton = findViewById(R.id.merchantModeButton)
        agentButton = findViewById(R.id.agentModeButton)
        merchantContainer = findViewById(R.id.merchantContainer)
        agentContainer = findViewById(R.id.agentContainer)
        backButton = findViewById(R.id.backButton)
        
        // Merchant views
        createRequestButton = findViewById(R.id.createRequestButton)
        merchantRequestsList = findViewById(R.id.merchantRequestsList)
        
        // Agent views
        registerAgentButton = findViewById(R.id.registerAgentButton)
        agentStatusText = findViewById(R.id.agentStatusText)
        activeRequestsList = findViewById(R.id.activeRequestsList)
        myAgentRequestsList = findViewById(R.id.myAgentRequestsList)
        
        // Set up mode toggle
        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentMode = when (checkedId) {
                    R.id.agentModeButton -> Mode.AGENT
                    else -> Mode.MERCHANT
                }
                updateUIForMode()
            }
        }
        
        // Set up RecyclerViews
        merchantRequestsList.layoutManager = LinearLayoutManager(this)
        activeRequestsList.layoutManager = LinearLayoutManager(this)
        myAgentRequestsList.layoutManager = LinearLayoutManager(this)
        
        // Set up buttons
        createRequestButton.setOnClickListener {
            showCreateRequestDialog()
        }
        
        registerAgentButton.setOnClickListener {
            startActivity(Intent(this, AgentRegistrationActivity::class.java))
        }
        
        backButton.setOnClickListener {
            finish()
        }
        
        // Fetch SOL price
        fetchSolPrice()
        
        // Initial update
        updateUIForMode()
    }
    
    override fun onResume() {
        super.onResume()
        updateUIForMode()
        refreshData()
    }
    
    private fun updateUIForMode() {
        when (currentMode) {
            Mode.MERCHANT -> {
                merchantContainer.visibility = View.VISIBLE
                agentContainer.visibility = View.GONE
                loadMerchantRequests()
            }
            Mode.AGENT -> {
                merchantContainer.visibility = View.GONE
                agentContainer.visibility = View.VISIBLE
                updateAgentStatus()
                loadAgentRequests()
            }
        }
    }
    
    private fun updateAgentStatus() {
        val isAgent = ConversionRequestManager.isAgent(this)
        if (isAgent) {
            val agent = ConversionRequestManager.getCurrentAgent(this)
            registerAgentButton.visibility = View.GONE
            agentStatusText.visibility = View.VISIBLE
            agentStatusText.text = "✓ Verified Agent: ${agent?.name ?: "Unknown"}\n" +
                    "Completed Trades: ${agent?.completedTrades ?: 0} | Rating: ${"%.1f".format(agent?.rating ?: 5.0)}"
        } else {
            registerAgentButton.visibility = View.VISIBLE
            agentStatusText.visibility = View.VISIBLE
            agentStatusText.text = "You must register as an agent to view and accept requests"
        }
    }
    
    private fun loadMerchantRequests() {
        val requests = ConversionRequestManager.getMyMerchantRequests(this)
        merchantRequestsList.adapter = MerchantRequestsAdapter(requests)
    }
    
    private fun loadAgentRequests() {
        if (!ConversionRequestManager.isAgent(this)) {
            activeRequestsList.adapter = EmptyAdapter("Register as agent to view requests")
            myAgentRequestsList.adapter = EmptyAdapter()
            return
        }
        
        val activeRequests = ConversionRequestManager.getActiveRequests(this)
        val myRequests = ConversionRequestManager.getMyAgentRequests(this)
        
        activeRequestsList.adapter = ActiveRequestsAdapter(activeRequests)
        myAgentRequestsList.adapter = AgentRequestsAdapter(myRequests)
    }
    
    private fun refreshData() {
        when (currentMode) {
            Mode.MERCHANT -> loadMerchantRequests()
            Mode.AGENT -> {
                updateAgentStatus()
                loadAgentRequests()
            }
        }
    }
    
    private fun showCreateRequestDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_request, null)
        val solAmountInput = dialogView.findViewById<TextInputEditText>(R.id.solAmountInput)
        val fiatAmountText = dialogView.findViewById<TextView>(R.id.fiatAmountText)
        val createButton = dialogView.findViewById<MaterialButton>(R.id.createButton)
        
        // Update fiat amount as user types
        solAmountInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val solAmount = s.toString().toDoubleOrNull() ?: 0.0
                val fiatAmount = solAmount * solPriceUsd
                fiatAmountText.text = "≈ ${"%.2f".format(fiatAmount)} USD"
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Create Conversion Request")
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        createButton.setOnClickListener {
            val solAmount = solAmountInput.text.toString().toDoubleOrNull()
            if (solAmount == null || solAmount <= 0) {
                Toast.makeText(this, "Please enter a valid SOL amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val pubkey = SolanaKeyManager.getPublicKey(this)
            if (pubkey == null) {
                Toast.makeText(this, "Wallet not initialized", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val fiatAmount = solAmount * solPriceUsd
            val request = ConversionRequest(
                id = "CR${System.currentTimeMillis()}",
                merchantPubkey = pubkey,
                merchantName = "User ${pubkey.substring(0, 8)}",
                solAmount = solAmount,
                fiatAmount = fiatAmount,
                currency = "USD",
                timestamp = System.currentTimeMillis(),
                status = RequestStatus.ACTIVE
            )
            
            ConversionRequestManager.createRequest(this, request)
            Toast.makeText(this, "Request created successfully!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            loadMerchantRequests()
        }
        
        dialog.show()
    }
    
    private fun fetchSolPrice() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(COINGECKO_API_URL)
                    .build()
                
                val response = httpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val json = JSONObject(responseBody)
                        solPriceUsd = json.getJSONObject("solana").getDouble("usd")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ConvertToFiat", "Error fetching SOL price: ${e.message}")
            }
        }
    }
    
    // Adapter for merchant's own requests
    inner class MerchantRequestsAdapter(
        private val requests: List<ConversionRequest>
    ) : RecyclerView.Adapter<MerchantRequestsAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val statusBadge: TextView = view.findViewById(R.id.statusBadge)
            val solAmountText: TextView = view.findViewById(R.id.solAmountText)
            val fiatAmountText: TextView = view.findViewById(R.id.fiatAmountText)
            val timestampText: TextView = view.findViewById(R.id.timestampText)
            val agentInfoText: TextView = view.findViewById(R.id.agentInfoText)
            val actionButton: MaterialButton = view.findViewById(R.id.actionButton)
            val timerText: TextView = view.findViewById(R.id.timerText)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_merchant_request, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val request = requests[position]
            
            holder.statusBadge.text = request.status.name
            holder.solAmountText.text = "${"%.4f".format(request.solAmount)} SOL"
            holder.fiatAmountText.text = "${"%.2f".format(request.fiatAmount)} ${request.currency}"
            holder.timestampText.text = ConversionRequestManager.formatTimestamp(request.timestamp)
            
            when (request.status) {
                RequestStatus.ACTIVE -> {
                    holder.statusBadge.setBackgroundColor(getColor(R.color.status_active))
                    holder.agentInfoText.text = "Waiting for agent..."
                    holder.agentInfoText.visibility = View.VISIBLE
                    holder.actionButton.text = "Cancel"
                    holder.actionButton.visibility = View.VISIBLE
                    holder.actionButton.setOnClickListener {
                        cancelRequest(request)
                    }
                    holder.timerText.visibility = View.GONE
                }
                RequestStatus.ACCEPTED -> {
                    holder.statusBadge.setBackgroundColor(getColor(R.color.status_pending))
                    holder.agentInfoText.text = "Agent: ${request.agentName}\nPayment in progress..."
                    holder.agentInfoText.visibility = View.VISIBLE
                    holder.actionButton.text = "Raise Dispute"
                    holder.actionButton.visibility = View.VISIBLE
                    
                    // Show countdown timer
                    val acceptedTime = request.acceptedTimestamp ?: System.currentTimeMillis()
                    val elapsed = System.currentTimeMillis() - acceptedTime
                    val remaining = DISPUTE_TIMEOUT_MS - elapsed
                    
                    if (remaining > 0) {
                        holder.timerText.visibility = View.VISIBLE
                        startCountdownTimer(holder.timerText, remaining, request)
                        holder.actionButton.setOnClickListener {
                            showDisputeDialog(request)
                        }
                    } else {
                        holder.timerText.visibility = View.GONE
                        holder.actionButton.visibility = View.GONE
                        // Auto-complete if no dispute raised
                        autoCompleteRequest(request)
                    }
                }
                RequestStatus.COMPLETED -> {
                    holder.statusBadge.setBackgroundColor(getColor(R.color.status_completed))
                    holder.agentInfoText.text = "Completed with: ${request.agentName}"
                    holder.agentInfoText.visibility = View.VISIBLE
                    holder.actionButton.visibility = View.GONE
                    holder.timerText.visibility = View.GONE
                }
                RequestStatus.DISPUTED -> {
                    holder.statusBadge.setBackgroundColor(getColor(R.color.status_disputed))
                    holder.agentInfoText.text = "Disputed: ${request.disputeReason}"
                    holder.agentInfoText.visibility = View.VISIBLE
                    holder.actionButton.visibility = View.GONE
                    holder.timerText.visibility = View.GONE
                }
                RequestStatus.CANCELLED -> {
                    holder.statusBadge.setBackgroundColor(getColor(R.color.status_cancelled))
                    holder.agentInfoText.visibility = View.GONE
                    holder.actionButton.visibility = View.GONE
                    holder.timerText.visibility = View.GONE
                }
            }
        }
        
        override fun getItemCount() = requests.size
    }
    
    // Adapter for active requests (for agents to view)
    inner class ActiveRequestsAdapter(
        private val requests: List<ConversionRequest>
    ) : RecyclerView.Adapter<ActiveRequestsAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val merchantNameText: TextView = view.findViewById(R.id.merchantNameText)
            val solAmountText: TextView = view.findViewById(R.id.solAmountText)
            val fiatAmountText: TextView = view.findViewById(R.id.fiatAmountText)
            val timestampText: TextView = view.findViewById(R.id.timestampText)
            val acceptButton: MaterialButton = view.findViewById(R.id.acceptButton)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_active_request, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val request = requests[position]
            
            holder.merchantNameText.text = request.merchantName
            holder.solAmountText.text = "${"%.4f".format(request.solAmount)} SOL"
            holder.fiatAmountText.text = "${"%.2f".format(request.fiatAmount)} ${request.currency}"
            holder.timestampText.text = ConversionRequestManager.formatTimestamp(request.timestamp)
            
            holder.acceptButton.setOnClickListener {
                acceptRequest(request)
            }
        }
        
        override fun getItemCount() = requests.size
    }
    
    // Adapter for agent's accepted requests
    inner class AgentRequestsAdapter(
        private val requests: List<ConversionRequest>
    ) : RecyclerView.Adapter<AgentRequestsAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val statusBadge: TextView = view.findViewById(R.id.statusBadge)
            val merchantNameText: TextView = view.findViewById(R.id.merchantNameText)
            val solAmountText: TextView = view.findViewById(R.id.solAmountText)
            val fiatAmountText: TextView = view.findViewById(R.id.fiatAmountText)
            val timestampText: TextView = view.findViewById(R.id.timestampText)
            val timerText: TextView = view.findViewById(R.id.timerText)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_agent_request, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val request = requests[position]
            
            holder.statusBadge.text = request.status.name
            holder.merchantNameText.text = "To: ${request.merchantName}"
            holder.solAmountText.text = "${"%.4f".format(request.solAmount)} SOL"
            holder.fiatAmountText.text = "${"%.2f".format(request.fiatAmount)} ${request.currency}"
            holder.timestampText.text = ConversionRequestManager.formatTimestamp(request.timestamp)
            
            when (request.status) {
                RequestStatus.ACCEPTED -> {
                    holder.statusBadge.setBackgroundColor(getColor(R.color.status_pending))
                    holder.timerText.visibility = View.VISIBLE
                    val acceptedTime = request.acceptedTimestamp ?: System.currentTimeMillis()
                    val elapsed = System.currentTimeMillis() - acceptedTime
                    val remaining = DISPUTE_TIMEOUT_MS - elapsed
                    
                    if (remaining > 0) {
                        startCountdownTimer(holder.timerText, remaining, request)
                    } else {
                        holder.timerText.text = "Auto-completing..."
                        autoCompleteRequest(request)
                    }
                }
                RequestStatus.COMPLETED -> {
                    holder.statusBadge.setBackgroundColor(getColor(R.color.status_completed))
                    holder.timerText.visibility = View.GONE
                }
                RequestStatus.DISPUTED -> {
                    holder.statusBadge.setBackgroundColor(getColor(R.color.status_disputed))
                    holder.timerText.visibility = View.VISIBLE
                    holder.timerText.text = "Disputed: ${request.disputeReason}"
                }
                else -> {
                    holder.timerText.visibility = View.GONE
                }
            }
        }
        
        override fun getItemCount() = requests.size
    }
    
    // Empty adapter for empty states
    inner class EmptyAdapter(
        private val message: String = "No requests yet"
    ) : RecyclerView.Adapter<EmptyAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val messageText: TextView = view.findViewById(R.id.emptyMessageText)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_empty, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.messageText.text = message
        }
        
        override fun getItemCount() = 1
    }
    
    private fun startCountdownTimer(timerText: TextView, remainingMs: Long, request: ConversionRequest) {
        object : CountDownTimer(remainingMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val minutes = millisUntilFinished / 60000
                val seconds = (millisUntilFinished % 60000) / 1000
                timerText.text = "Dispute window: %d:%02d".format(minutes, seconds)
            }
            
            override fun onFinish() {
                timerText.text = "Auto-completing..."
                autoCompleteRequest(request)
            }
        }.start()
    }
    
    private fun acceptRequest(request: ConversionRequest) {
        val agent = ConversionRequestManager.getCurrentAgent(this) ?: return
        
        AlertDialog.Builder(this)
            .setTitle("Accept Request")
            .setMessage("You will send ${"%.2f".format(request.fiatAmount)} ${request.currency} to ${request.merchantName}.\n\n" +
                    "After sending, the merchant has 2 minutes to raise a dispute.")
            .setPositiveButton("Accept") { _, _ ->
                val updatedRequest = request.copy(
                    status = RequestStatus.ACCEPTED,
                    agentPubkey = agent.pubkey,
                    agentName = agent.name,
                    acceptedTimestamp = System.currentTimeMillis()
                )
                ConversionRequestManager.updateRequest(this, updatedRequest)
                Toast.makeText(this, "Request accepted! Please send fiat payment.", Toast.LENGTH_LONG).show()
                refreshData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun cancelRequest(request: ConversionRequest) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Request")
            .setMessage("Are you sure you want to cancel this request?")
            .setPositiveButton("Yes") { _, _ ->
                val updatedRequest = request.copy(status = RequestStatus.CANCELLED)
                ConversionRequestManager.updateRequest(this, updatedRequest)
                Toast.makeText(this, "Request cancelled", Toast.LENGTH_SHORT).show()
                refreshData()
            }
            .setNegativeButton("No", null)
            .show()
    }
    
    private fun showDisputeDialog(request: ConversionRequest) {
        val input = EditText(this)
        input.hint = "Reason for dispute"
        
        AlertDialog.Builder(this)
            .setTitle("Raise Dispute")
            .setMessage("Explain why you are disputing this transaction:")
            .setView(input)
            .setPositiveButton("Submit") { _, _ ->
                val reason = input.text.toString().trim()
                if (reason.isNotEmpty()) {
                    val updatedRequest = request.copy(
                        status = RequestStatus.DISPUTED,
                        disputeRaised = true,
                        disputeReason = reason
                    )
                    ConversionRequestManager.updateRequest(this, updatedRequest)
                    Toast.makeText(this, "Dispute raised", Toast.LENGTH_SHORT).show()
                    refreshData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun autoCompleteRequest(request: ConversionRequest) {
        if (request.status == RequestStatus.ACCEPTED && !request.disputeRaised) {
            CoroutineScope(Dispatchers.IO).launch {
                val updatedRequest = request.copy(status = RequestStatus.COMPLETED)
                ConversionRequestManager.updateRequest(this@ConvertToFiatActivity, updatedRequest)
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ConvertToFiatActivity,
                        "Request completed successfully!",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshData()
                }
            }
        }
    }
}

