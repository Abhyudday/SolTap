package com.solanatappay.ui

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.solanatappay.R
import com.solanatappay.data.ConversionRequestManager
import com.solanatappay.solana.SolanaKeyManager
import com.solanatappay.solana.SolanaRpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity for agents to register by making a security deposit.
 */
class AgentRegistrationActivity : AppCompatActivity() {
    
    private lateinit var nameInput: TextInputEditText
    private lateinit var depositAddressText: TextView
    private lateinit var depositAmountText: TextView
    private lateinit var txSignatureInput: TextInputEditText
    private lateinit var registerButton: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var backButton: MaterialButton
    
    private lateinit var rpcClient: SolanaRpcClient
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_registration)
        
        rpcClient = SolanaRpcClient(this)
        
        // Initialize views
        nameInput = findViewById(R.id.nameInput)
        depositAddressText = findViewById(R.id.depositAddressText)
        depositAmountText = findViewById(R.id.depositAmountText)
        txSignatureInput = findViewById(R.id.txSignatureInput)
        registerButton = findViewById(R.id.registerButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        backButton = findViewById(R.id.backButton)
        
        // Set deposit info
        depositAddressText.text = ConversionRequestManager.SECURITY_DEPOSIT_ADDRESS
        depositAmountText.text = "$${ConversionRequestManager.SECURITY_DEPOSIT_AMOUNT}"
        
        // Set up button listeners
        registerButton.setOnClickListener {
            validateAndRegister()
        }
        
        backButton.setOnClickListener {
            finish()
        }
        
        depositAddressText.setOnClickListener {
            copyToClipboard(ConversionRequestManager.SECURITY_DEPOSIT_ADDRESS)
        }
    }
    
    private fun validateAndRegister() {
        val name = nameInput.text.toString().trim()
        val txSignature = txSignatureInput.text.toString().trim()
        
        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (txSignature.isEmpty()) {
            Toast.makeText(this, "Please enter transaction signature", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Show loading
        progressBar.visibility = View.VISIBLE
        registerButton.isEnabled = false
        statusText.text = "Verifying deposit transaction..."
        statusText.visibility = View.VISIBLE
        
        // Verify transaction (simplified for hackathon)
        // In production, you would verify the transaction on-chain
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Simulate verification delay
                kotlinx.coroutines.delay(2000)
                
                // For hackathon: just check if signature looks valid (base58, reasonable length)
                val isValid = txSignature.length in 80..90 && txSignature.all { 
                    it.isLetterOrDigit() 
                }
                
                withContext(Dispatchers.Main) {
                    if (isValid) {
                        // Register as agent
                        val pubkey = SolanaKeyManager.getPublicKey(this@AgentRegistrationActivity)
                        if (pubkey != null) {
                            val agent = ConversionRequestManager.Agent(
                                pubkey = pubkey,
                                name = name,
                                depositTxSignature = txSignature,
                                registeredTimestamp = System.currentTimeMillis(),
                                completedTrades = 0,
                                rating = 5.0
                            )
                            
                            ConversionRequestManager.registerAsAgent(
                                this@AgentRegistrationActivity,
                                agent
                            )
                            
                            statusText.text = "Registration successful!"
                            Toast.makeText(
                                this@AgentRegistrationActivity,
                                "You are now registered as an agent!",
                                Toast.LENGTH_LONG
                            ).show()
                            
                            // Return to previous screen
                            kotlinx.coroutines.delay(1500)
                            finish()
                        } else {
                            statusText.text = "Error: Wallet not initialized"
                            registerButton.isEnabled = true
                        }
                    } else {
                        statusText.text = "Invalid transaction signature"
                        Toast.makeText(
                            this@AgentRegistrationActivity,
                            "Please enter a valid transaction signature",
                            Toast.LENGTH_SHORT
                        ).show()
                        registerButton.isEnabled = true
                    }
                    
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                    progressBar.visibility = View.GONE
                    registerButton.isEnabled = true
                    Toast.makeText(
                        this@AgentRegistrationActivity,
                        "Error verifying transaction",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Deposit Address", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

