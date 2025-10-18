package com.solanatappay.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.solanatappay.R
import com.solanatappay.solana.SolanaKeyManager

/**
 * Mode selection activity - allows user to choose between Buyer or Merchant mode.
 * Mode can be switched at any time.
 */
class ModeSelectionActivity : AppCompatActivity() {
    
    companion object {
        private const val PREFS_NAME = "AppMode"
        private const val PREFS_MODE_KEY = "selected_mode"
        private const val MODE_BUYER = "buyer"
        private const val MODE_MERCHANT = "merchant"
        private const val PERMISSION_REQUEST_CODE = 100
        
        /**
         * Get the selected mode, or null if not yet selected.
         */
        fun getSelectedMode(context: Context): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREFS_MODE_KEY, null)
        }
        
        /**
         * Check if mode has been selected.
         */
        fun isModeSelected(context: Context): Boolean {
            return getSelectedMode(context) != null
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if mode already selected - if so, navigate to appropriate activity
        if (isModeSelected(this)) {
            navigateToModeActivity()
            return
        }
        
        setContentView(R.layout.activity_mode_selection)
        
        // Initialize wallet (generate keypair if needed)
        SolanaKeyManager.getOrCreateKeypair(this)
        
        // Request permissions
        requestRequiredPermissions()
        
        // Set up button listeners
        findViewById<MaterialCardView>(R.id.buyerCard).setOnClickListener {
            selectMode(MODE_BUYER)
        }
        
        findViewById<MaterialCardView>(R.id.merchantCard).setOnClickListener {
            selectMode(MODE_MERCHANT)
        }
    }
    
    private fun selectMode(mode: String) {
        // Save mode (can be changed later)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_MODE_KEY, mode)
            .apply()
        
        // Navigate to appropriate activity
        navigateToModeActivity()
    }
    
    private fun navigateToModeActivity() {
        val mode = getSelectedMode(this)
        val intent = when (mode) {
            MODE_BUYER -> Intent(this, BuyerActivity::class.java)
            MODE_MERCHANT -> Intent(this, MerchantActivity::class.java)
            else -> return
        }
        
        startActivity(intent)
        finish()
    }
    
    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        
        // Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Older versions
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        // Location permission (required for BLE scanning on Android < 12)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        
        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Filter out already granted permissions
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val deniedPermissions = permissions.filterIndexed { index, _ ->
                grantResults[index] != PackageManager.PERMISSION_GRANTED
            }
            
            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Some permissions were denied. BLE functionality may not work.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

