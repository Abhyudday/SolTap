package com.solanatappay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.solanatappay.R
import com.google.android.material.button.MaterialButtonToggleGroup
import com.solanatappay.solana.SolanaKeyManager
import com.solanatappay.solana.SolanaRpcClient
import kotlinx.coroutines.launch

/**
 * Settings activity - shows wallet info and funding instructions.
 */
class SettingsActivity : AppCompatActivity() {
    
    private lateinit var publicKeyText: TextView
    private lateinit var balanceText: TextView
    private lateinit var copyAddressButton: MaterialButton
    private lateinit var refreshBalanceButton: MaterialButton
    private lateinit var networkToggle: MaterialButtonToggleGroup
    private lateinit var devnetButton: MaterialButton
    private lateinit var mainnetButton: MaterialButton
    
    private lateinit var rpcClient: SolanaRpcClient
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Initialize RPC client
        rpcClient = SolanaRpcClient(this)
        
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // Initialize views
        publicKeyText = findViewById(R.id.publicKeyText)
        balanceText = findViewById(R.id.balanceText)
        copyAddressButton = findViewById(R.id.copyAddressButton)
        refreshBalanceButton = findViewById(R.id.refreshBalanceButton)
        networkToggle = findViewById(R.id.networkToggle)
        devnetButton = findViewById(R.id.devnetButton)
        mainnetButton = findViewById(R.id.mainnetButton)
        
        // Set up network toggle
        val currentNetwork = SolanaRpcClient.getSelectedNetwork(this)
        networkToggle.check(if (currentNetwork == SolanaRpcClient.NETWORK_MAINNET) R.id.mainnetButton else R.id.devnetButton)
        
        networkToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val network = when (checkedId) {
                    R.id.mainnetButton -> SolanaRpcClient.NETWORK_MAINNET
                    else -> SolanaRpcClient.NETWORK_DEVNET
                }
                SolanaRpcClient.setSelectedNetwork(this, network)
                Toast.makeText(this, "Network switched to ${network.uppercase()}", Toast.LENGTH_SHORT).show()
                
                // Reload balance for new network
                loadBalance()
            }
        }
        
        // Display wallet info
        val publicKey = SolanaKeyManager.getPublicKey(this)
        publicKeyText.text = publicKey ?: "No wallet found"
        
        // Load balance
        loadBalance()
        
        // Set up button listeners
        copyAddressButton.setOnClickListener {
            copyAddress()
        }
        
        refreshBalanceButton.setOnClickListener {
            loadBalance()
        }
    }
    
    private fun loadBalance() {
        val publicKey = SolanaKeyManager.getPublicKey(this) ?: return
        
        balanceText.text = "Balance: Loading..."
        
        lifecycleScope.launch {
            try {
                val lamports = rpcClient.getBalance(publicKey)
                val sol = lamports / 1_000_000_000.0
                balanceText.text = "Balance: %.4f SOL (%,d lamports)".format(sol, lamports)
            } catch (e: Exception) {
                balanceText.text = "Balance: Error loading"
                Toast.makeText(
                    this@SettingsActivity,
                    "Failed to load balance: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun copyAddress() {
        val publicKey = SolanaKeyManager.getPublicKey(this) ?: return
        
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Solana Address", publicKey)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(this, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

