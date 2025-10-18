package com.solanatappay.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import com.solanatappay.R
import com.solanatappay.solana.SolanaKeyManager
import com.solanatappay.solana.SolanaRpcClient
import kotlinx.coroutines.*

/**
 * Foreground service for BLE scanning (Buyer mode).
 * Scans for merchant advertisements and automatically executes payment.
 * 
 * NO USER APPROVAL REQUIRED - payments are executed automatically upon detection.
 */
class BuyerScannerService : Service() {
    
    companion object {
        private const val TAG = "BuyerScanner"
        private const val NOTIFICATION_CHANNEL_ID = "buyer_channel"
        private const val NOTIFICATION_ID = 2001
        
        const val ACTION_SCANNING_STARTED = "com.solanatappay.SCANNING_STARTED"
        const val ACTION_SCANNING_STOPPED = "com.solanatappay.SCANNING_STOPPED"
        const val ACTION_MERCHANT_DETECTED = "com.solanatappay.MERCHANT_DETECTED"
        const val ACTION_PAYMENT_PROCESSING = "com.solanatappay.PAYMENT_PROCESSING"
        const val ACTION_PAYMENT_SUCCESS = "com.solanatappay.PAYMENT_SUCCESS"
        const val ACTION_PAYMENT_FAILED = "com.solanatappay.PAYMENT_FAILED"
    }
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var bluetoothGatt: BluetoothGatt? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var rpcClient: SolanaRpcClient
    
    // Track processed order IDs to prevent duplicate payments
    private val processedOrders = mutableSetOf<Long>()
    
    // Track connecting/connected devices to prevent duplicate connections
    private val connectingDevices = mutableSetOf<String>()
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleScanResult(it) }
        }
        
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            sendBroadcast(Intent(ACTION_PAYMENT_FAILED).apply {
                putExtra("error", "Scan failed: $errorCode")
            })
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceAddress = gatt?.device?.address ?: "unknown"
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server: $deviceAddress")
                    try {
                        gatt?.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception discovering services: ${e.message}")
                        connectingDevices.remove(deviceAddress)
                        gatt?.close()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server: $deviceAddress")
                    connectingDevices.remove(deviceAddress)
                    gatt?.close()
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")
                val service = gatt?.getService(PaymentPayload.SERVICE_UUID)
                val characteristic = service?.getCharacteristic(PaymentPayload.CHARACTERISTIC_UUID)
                
                if (characteristic != null) {
                    try {
                        gatt.readCharacteristic(characteristic)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception reading characteristic: ${e.message}")
                        gatt?.close()
                    }
                } else {
                    Log.e(TAG, "Payment characteristic not found")
                    gatt?.close()
                }
            } else {
                Log.e(TAG, "Service discovery failed: $status")
                gatt?.close()
            }
        }
        
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic?.value
                Log.d(TAG, "Characteristic read: ${data?.size} bytes")
                
                if (data != null) {
                    // Parse payment payload
                    val payload = PaymentPayload.deserialize(data)
                    if (payload != null) {
                        Log.d(TAG, "Valid payment request: ${payload.amountLamports} lamports to ${payload.merchantPubkey}")
                        
                        // Check if already processed
                        if (!processedOrders.contains(payload.orderId)) {
                            processedOrders.add(payload.orderId)
                            
                            // Notify merchant detected
                            sendBroadcast(Intent(ACTION_MERCHANT_DETECTED).apply {
                                putExtra("merchantPubkey", payload.merchantPubkey)
                                putExtra("amountLamports", payload.amountLamports)
                            })
                            
                            // Execute payment
                            executePayment(payload)
                        } else {
                            Log.d(TAG, "Order already processed: ${payload.orderId}")
                        }
                    } else {
                        Log.w(TAG, "Invalid payload")
                    }
                }
            } else {
                Log.e(TAG, "Characteristic read failed: $status")
            }
            
            // Close connection
            try {
                gatt?.disconnect()
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception disconnecting: ${e.message}")
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize RPC client
        rpcClient = SolanaRpcClient(this)
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner
        
        if (scanner == null) {
            Log.e(TAG, "BLE scanning not supported on this device")
            stopSelf()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        // Create notification channel
        createNotificationChannel()
        
        // Start foreground service with notification
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_buyer_title))
            .setContentText(getString(R.string.foreground_buyer_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Start scanning
        startScanning()
        
        return START_STICKY
    }
    
    private fun startScanning() {
        if (scanner == null) {
            Log.e(TAG, "Scanner not available")
            stopSelf()
            return
        }
        
        // Build scan filter for merchant service UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(PaymentPayload.SERVICE_UUID))
            .build()
        
        // Build scan settings (LOW_LATENCY, immediate callback)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(BleConfig.SCAN_REPORT_DELAY_MS)
            .build()
        
        // Start scanning
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            sendBroadcast(Intent(ACTION_SCANNING_STARTED))
            Log.d(TAG, "Scanning started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
            sendBroadcast(Intent(ACTION_PAYMENT_FAILED).apply {
                putExtra("error", "Permission denied")
            })
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan: ${e.message}")
            sendBroadcast(Intent(ACTION_PAYMENT_FAILED).apply {
                putExtra("error", e.message ?: "Unknown error")
            })
            stopSelf()
        }
    }
    
    private fun handleScanResult(result: ScanResult) {
        Log.d(TAG, "Scan result received")
        
        val device = result.device
        val deviceAddress = device?.address ?: return
        
        // Check if already connecting to this device
        if (connectingDevices.contains(deviceAddress)) {
            Log.d(TAG, "Already connecting to device: $deviceAddress")
            return
        }
        
        // Mark as connecting
        connectingDevices.add(deviceAddress)
        
        Log.d(TAG, "Connecting to GATT server: $deviceAddress")
        
        // Connect to GATT server to read payment data
        try {
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception connecting to GATT: ${e.message}")
            connectingDevices.remove(deviceAddress)
        }
    }
    
    /**
     * CRITICAL: Auto-executes payment without user approval.
     * This is for PROTOTYPE/DEMO purposes only.
     * 
     * In production:
     * - Require user authentication (biometric, PIN)
     * - Show confirmation dialog
     * - Implement spending limits
     * - Use server-side signing
     */
    private fun executePayment(payload: PaymentPayload) {
        Log.d(TAG, "AUTO-EXECUTING payment (no user approval)")
        
        sendBroadcast(Intent(ACTION_PAYMENT_PROCESSING))
        
        serviceScope.launch {
            try {
                // Get buyer's private key from Android Keystore
                val privateKey = SolanaKeyManager.getPrivateKey(this@BuyerScannerService)
                val publicKey = SolanaKeyManager.getPublicKey(this@BuyerScannerService)
                    ?: throw Exception("No public key found")
                
                Log.d(TAG, "Sending ${payload.amountLamports} lamports from $publicKey to ${payload.merchantPubkey}")
                
                // Build, sign, and send transaction
                val signature = rpcClient.sendSolTransfer(
                    fromPrivateKey = privateKey,
                    fromPubkey = publicKey,
                    toPubkey = payload.merchantPubkey,
                    amountLamports = payload.amountLamports
                )
                
                Log.d(TAG, "Payment successful: $signature")
                
                // Notify success
                sendBroadcast(Intent(ACTION_PAYMENT_SUCCESS).apply {
                    putExtra("signature", signature)
                })
                
            } catch (e: Exception) {
                Log.e(TAG, "Payment failed: ${e.message}", e)
                
                // Remove from processed orders so it can be retried
                processedOrders.remove(payload.orderId)
                
                // Notify failure
                sendBroadcast(Intent(ACTION_PAYMENT_FAILED).apply {
                    putExtra("error", e.message ?: "Unknown error")
                })
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        if (isScanning) {
            try {
                scanner?.stopScan(scanCallback)
                isScanning = false
                sendBroadcast(Intent(ACTION_SCANNING_STOPPED))
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping scan: ${e.message}")
            }
        }
        
        // Close GATT connection
        try {
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT: ${e.message}")
        }
        
        serviceScope.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Buyer Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BLE scanning for payment requests"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}

