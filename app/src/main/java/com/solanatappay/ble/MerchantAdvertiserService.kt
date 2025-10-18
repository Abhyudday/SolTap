package com.solanatappay.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import com.solanatappay.R
import com.solanatappay.ui.MerchantActivity

/**
 * Foreground service for BLE advertising (Merchant mode).
 * Advertises payment request with low latency settings.
 */
class MerchantAdvertiserService : Service() {
    
    companion object {
        private const val TAG = "MerchantAdvertiser"
        private const val NOTIFICATION_CHANNEL_ID = "merchant_channel"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_ADVERTISING_STARTED = "com.solanatappay.ADVERTISING_STARTED"
        const val ACTION_ADVERTISING_STOPPED = "com.solanatappay.ADVERTISING_STOPPED"
        const val ACTION_ADVERTISING_FAILED = "com.solanatappay.ADVERTISING_FAILED"
    }
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var isAdvertising = false
    private var payloadData: ByteArray? = null
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising started successfully")
            isAdvertising = true
            sendBroadcast(Intent(ACTION_ADVERTISING_STARTED))
        }
        
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            isAdvertising = false
            
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error: $errorCode"
            }
            
            val intent = Intent(ACTION_ADVERTISING_FAILED).apply {
                putExtra("error", errorMessage)
            }
            sendBroadcast(intent)
            
            stopSelf()
        }
    }
    
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Device connected: ${device?.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Device disconnected: ${device?.address}")
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.d(TAG, "Characteristic read request from ${device?.address}")
            
            if (characteristic?.uuid == PaymentPayload.CHARACTERISTIC_UUID) {
                payloadData?.let { data ->
                    val responseData = if (offset >= data.size) {
                        byteArrayOf()
                    } else {
                        data.copyOfRange(offset, data.size)
                    }
                    
                    try {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            responseData
                        )
                        Log.d(TAG, "Sent payload data: ${responseData.size} bytes from offset $offset")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception sending response: ${e.message}")
                    }
                } ?: run {
                    try {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            null
                        )
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception: ${e.message}")
                    }
                }
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported on this device")
            stopSelf()
        }
    }
    
    private fun setupGattServer() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        
        // Create GATT server
        try {
            gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
            if (gattServer == null) {
                Log.e(TAG, "Failed to create GATT server")
                return
            }
            
            // Add service with payment characteristic
            val service = BluetoothGattService(
                PaymentPayload.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            
            val characteristic = BluetoothGattCharacteristic(
                PaymentPayload.CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            
            service.addCharacteristic(characteristic)
            gattServer?.addService(service)
            
            Log.d(TAG, "GATT server created with payment service")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception creating GATT server: ${e.message}")
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        // Extract payment parameters
        val merchantPubkey = intent?.getStringExtra("merchantPubkey") ?: return START_NOT_STICKY
        val amountLamports = intent.getLongExtra("amountLamports", 0)
        val orderId = intent.getLongExtra("orderId", 0)
        
        if (amountLamports <= 0) {
            Log.e(TAG, "Invalid amount")
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Create notification channel
        createNotificationChannel()
        
        // Start foreground service with notification
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_merchant_title))
            .setContentText(getString(R.string.foreground_merchant_text))
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
        
        // Setup GATT server after starting foreground
        setupGattServer()
        
        // Start advertising
        startAdvertising(merchantPubkey, amountLamports, orderId)
        
        return START_STICKY
    }
    
    private fun startAdvertising(merchantPubkey: String, amountLamports: Long, orderId: Long) {
        if (advertiser == null) {
            Log.e(TAG, "Advertiser not available")
            stopSelf()
            return
        }
        
        // Build payment payload and store it for GATT server
        val payload = PaymentPayload(merchantPubkey, amountLamports, orderId)
        payloadData = PaymentPayload.serialize(payload)
        
        Log.d(TAG, "Starting advertising with GATT server (payload size: ${payloadData?.size} bytes)")
        
        // Build advertise settings (LOW_LATENCY, HIGH_POWER, CONNECTABLE)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)  // Changed to true for GATT server
            .setTimeout(BleConfig.ADVERTISE_TIMEOUT_MS)
            .build()
        
        // Build advertise data with only service UUID (no manufacturer data)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(PaymentPayload.SERVICE_UUID))
            .build()
        
        // Start advertising
        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.d(TAG, "Advertise command sent (connectable mode with GATT server)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
            sendBroadcast(Intent(ACTION_ADVERTISING_FAILED).apply {
                putExtra("error", "Permission denied")
            })
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising: ${e.message}")
            sendBroadcast(Intent(ACTION_ADVERTISING_FAILED).apply {
                putExtra("error", e.message ?: "Unknown error")
            })
            stopSelf()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        if (isAdvertising) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
                sendBroadcast(Intent(ACTION_ADVERTISING_STOPPED))
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping advertising: ${e.message}")
            }
        }
        
        // Close GATT server
        try {
            gattServer?.close()
            gattServer = null
            Log.d(TAG, "GATT server closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT server: ${e.message}")
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Merchant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BLE advertising for payment requests"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}

