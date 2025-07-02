package com.example.gps_rtk_droid2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.IOException
import android.os.PowerManager

import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

import androidx.core.app.ActivityCompat

class GpsRtkService : Service(), UsbGpsConnection.GpsDataListener {

    private val TAG = "GpsRtkService"
    private val CHANNEL_ID = "ForegroundServiceChannel" // この定数はcreateNotificationChannelの戻り値を使用するため不要になる可能性
    private val NOTIFICATION_ID = 1

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private var usbManager: UsbManager? = null
    private var usbGpsConnection: UsbGpsConnection? = null

    private lateinit var wakeLock: PowerManager.WakeLock

    // サービス内で使用するアクションとエクストラの定数
    companion object {
        const val ACTION_START_FOREGROUND_SERVICE = "com.example.gps_rtk_droid2.ACTION_START_FOREGROUND_SERVICE"
        const val ACTION_STOP_FOREGROUND_SERVICE = "com.example.gps_rtk_droid2.ACTION_STOP_FOREGROUND_SERVICE"
        const val EXTRA_USB_DEVICE = "extra_usb_device"

        // MainActivityへのブロードキャストアクションとエクストラ
        const val ACTION_GPS_CONNECTION_STATUS_CHANGED = "com.example.gps_rtk_droid2.GPS_CONNECTION_STATUS_CHANGED"
        const val EXTRA_IS_CONNECTED = "is_connected"
        const val ACTION_GPS_DATA_UPDATE = "com.example.gps_rtk_droid2.GPS_DATA_UPDATE"
        const val EXTRA_NMEA_DATA = "nmea_data"
    }

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GpsRtkService::WakeLock"
        )
        wakeLock.acquire() // サービス開始時にWakeLockを取得

        Log.d(TAG, "Service created")
        // 通知チャンネルはonStartCommandで初回呼び出し時に作成される
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand: ${intent?.action}")

        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Required permissions not granted. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        // 通知チャンネルの作成はここで行う
        val notificationChannelId = createNotificationChannel()
        startForegroundServiceWithNotification("GPS-RTK サービス実行中", notificationChannelId)


        when (intent?.action) {
            ACTION_START_FOREGROUND_SERVICE -> {
                val receivedUsbDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_USB_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_USB_DEVICE)
                }

                if (receivedUsbDevice != null) {
                    startForegroundServiceWithNotification(receivedUsbDevice.productName ?: "GPSデバイス", notificationChannelId)
                    // UsbGpsConnectionのインスタンス化と接続開始
                    usbManager?.let { manager ->
                        // UsbGpsConnectionのコンストラクタがUsbManagerとUsbDeviceを受け取るように修正されていることを前提とする
                        usbGpsConnection = UsbGpsConnection(manager, receivedUsbDevice)
                        usbGpsConnection?.setDataListener(this) // このサービスをリスナーとして設定
                        serviceScope.launch {
                            try {
                                usbGpsConnection?.openConnection()
                                Log.d(TAG, "UsbGpsConnection opened.")
                                sendConnectionStatusBroadcast(true) // 接続成功をブロードキャスト
                            } catch (e: IOException) {
                                Log.e(TAG, "USB接続エラー: ${e.message}", e)
                                sendConnectionStatusBroadcast(false)
                                stopSelf()
                            }
                        }
                    } ?: run {
                        Log.e(TAG, "UsbManagerがnullです。")
                        sendConnectionStatusBroadcast(false)
                        stopSelf()
                    }
                } else {
                    Log.e(TAG, "USBデバイスがIntentから取得できませんでした。")
                    sendConnectionStatusBroadcast(false)
                    stopSelf()
                }
            }
            ACTION_STOP_FOREGROUND_SERVICE -> {
                Log.d(TAG, "サービス停止要求を受信しました。")
                stopSelf() // サービスを停止
            }
            else -> {
                startForegroundServiceWithNotification("GPS-RTK サービス実行中", notificationChannelId)
                Log.d(TAG, "未知のIntentアクションを受信しました。")
            }
        }

        return START_STICKY
    }

    // UsbGpsConnection.GpsDataListener の実装
    override fun onGpsDataReceived(data: String) {
        Log.d(TAG, "Received NMEA from UsbGpsConnection: $data")
        sendGpsDataBroadcast(data)
    }

    override fun onError(error: String) {
        Log.e(TAG, "UsbGpsConnection Error: $error")
        sendConnectionStatusBroadcast(false) // エラー発生時は切断状態をブロードキャスト
        // 必要に応じてサービスを停止することも検討
        // stopSelf()
    }

    private fun sendConnectionStatusBroadcast(isConnected: Boolean) {
        val intent = Intent(ACTION_GPS_CONNECTION_STATUS_CHANGED)
        intent.putExtra(EXTRA_IS_CONNECTED, isConnected)
        sendBroadcast(intent)
        Log.d(TAG, "接続状態をブロードキャストしました: $isConnected")
    }

    private fun sendGpsDataBroadcast(nmeaData: String) {
        val intent = Intent(ACTION_GPS_DATA_UPDATE)
        intent.putExtra(EXTRA_NMEA_DATA, nmeaData)
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release() // サービス終了時にWakeLockを解放
        }
        super.onDestroy()
        serviceScope.cancel()
        usbGpsConnection?.closeConnection() // USB接続をクローズ
        sendConnectionStatusBroadcast(false) // サービス終了時に切断を通知
        Log.d(TAG, "Service destroyed")
    }

    private fun startForegroundServiceWithNotification(content: String, channelId: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("GPS-RTK サービス")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification) // 適切なアイコンを設定
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "gps_service_channel"
            val channelName = "GPSサービスチャンネル"
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "GPS RTKサービスの通知チャンネル"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            return channelId
        }
        return "" // Android O未満ではチャンネルIDは不要
    }

    private fun hasRequiredPermissions(): Boolean {
        // ACCESS_FINE_LOCATION または ACCESS_COARSE_LOCATION のいずれかがあればOKとする
        val locationPermissionGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundServiceLocationGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
            locationPermissionGranted && foregroundServiceLocationGranted
        } else {
            locationPermissionGranted
        }
    }
}