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
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// GpsDataクラスをService内に定義
data class GpsData(
    val timestamp: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val heading: Double? = null,
    val rtkStatus: String? = null,
    val rawNmea: String
)

class GpsRtkService : Service() {

    private val TAG = "GpsRtkService"
    private val executor = Executors.newSingleThreadExecutor()
    private var usbGpsConnection: UsbGpsConnection? = null
    private var ntripClient: NtripClient? = null
    private var writer: FileWriter? = null
    private val isRunning = AtomicBoolean(false)
    private val isFileWriterOpen = AtomicBoolean(false)

    // 日本時間フォーマッター
    private val jstFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("Asia/Tokyo")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service created")

        // ファイルライターを初期化
        initFileWriter()
    }

    private fun initFileWriter() {
        try {
            val logFile = File(getExternalFilesDir(null), "gps_rtk_raw_log.csv")
            val fileExists = logFile.exists()
            writer = FileWriter(logFile, true) // 追記モード
            isFileWriterOpen.set(true)

            // ファイルが存在しない場合のみヘッダーを書き込む
            if (!fileExists) {
                writer?.append("Time,Lat,Lon,Alt,Heading,Rtk,Raw\n")
                writer?.flush()
            }
            Log.d(TAG, "File writer initialized successfully")
        } catch (e: IOException) {
            Log.e(TAG, "File open error: ${e.message}", e)
            isFileWriterOpen.set(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning.get()) {
                    val input = intent.getStringExtra("inputExtra") ?: "GPSデータ処理中..."
                    startForeground(NOTIFICATION_ID, createNotification(input))

                    // IntentからNTRIPとUSBデバイスの情報を取得
                    val server = intent.getStringExtra("ntrip_server")
                    val port = intent.getIntExtra("ntrip_port", 2101)
                    val mountPoint = intent.getStringExtra("ntrip_mountpoint")
                    val username = intent.getStringExtra("ntrip_user")
                    val password = intent.getStringExtra("ntrip_password")
                    val usbDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("usb_device", UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("usb_device")
                    }

                    if (usbDevice != null && server != null && mountPoint != null && username != null && password != null) {
                        startGpsAndNtripConnection(usbDevice, server, port, mountPoint, username, password)
                    } else {
                        logToActivity("エラー: 接続パラメータが不足しています。")
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                stopGpsAndNtripConnection()
                stopSelf() // サービスを停止
            }
        }
        return START_NOT_STICKY // 予期せぬ終了時に自動再起動しない
    }

    private fun startGpsAndNtripConnection(
        usbDevice: UsbDevice,
        server: String, port: Int, mountPoint: String, username: String, password: String
    ) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        try {
            // USB GPS接続を開始
            usbGpsConnection = UsbGpsConnection(usbManager, usbDevice).apply {
                setDataListener(object : UsbGpsConnection.GpsDataListener {
                    override fun onGpsDataReceived(data: String) {
                        parseGpsData(data)
                    }
                    override fun onError(error: String) {
                        logToActivity("GPSエラー: $error")
                        // GPSエラーでも接続を維持する場合はコメントアウト
                        // stopGpsAndNtripConnection()
                    }
                })
                openConnection()
            }

            // NTRIPクライアント接続を開始
            ntripClient = NtripClient(server, port, mountPoint, username, password,
                object : NtripClient.NtripClientListener {
                    override fun onRtcmDataReceived(data: ByteArray) {
                        usbGpsConnection?.write(data)
                    }
                    override fun onStatusChanged(message: String) {
                        logToActivity(message)
                    }
                    override fun onError(error: String) {
                        logToActivity("NTRIPエラー: $error")
                        // NTRIPエラーでも接続を維持する場合はコメントアウト
                        // stopGpsAndNtripConnection()
                    }
                })

            executor.execute { ntripClient?.connect() }

            isRunning.set(true)
            logToActivity("接続を開始しました")
            broadcastConnectionStatus(true)

        } catch (e: Exception) {
            logToActivity("接続エラー: ${e.message}")
            stopGpsAndNtripConnection()
        }
    }

    private fun stopGpsAndNtripConnection() {
        if (!isRunning.get()) return

        try {
            ntripClient?.disconnect()
            usbGpsConnection?.closeConnection()
            logToActivity("接続を停止しました")
        } catch (e: Exception) {
            logToActivity("切断エラー: ${e.message}")
        } finally {
            ntripClient = null
            usbGpsConnection = null
            isRunning.set(false)
            broadcastConnectionStatus(false)
        }
    }

    private fun parseGpsData(data: String) {
        data.lines().forEach { line ->
            if (line.isNotBlank()) {
                val parsedData = parseNmeaSentence(line)
                if (parsedData != null) {
                    logParsedGpsDataToFile(parsedData)
                    broadcastGpsData(parsedData)
                }
            }
        }
    }

    private fun logParsedGpsDataToFile(gpsData: GpsData) {
        if (!isFileWriterOpen.get()) {
            Log.w(TAG, "File writer is not open, attempting to reinitialize")
            initFileWriter()
        }

        executor.execute {
            try {
                if (writer != null && isFileWriterOpen.get()) {
                    val csvLine = buildString {
                        append(gpsData.timestamp)
                        append(",")
                        append(gpsData.latitude?.let { "%.6f".format(it) } ?: "")
                        append(",")
                        append(gpsData.longitude?.let { "%.6f".format(it) } ?: "")
                        append(",")
                        append(gpsData.altitude?.let { "%.3f".format(it) } ?: "")
                        append(",")
                        append(gpsData.heading?.let { "%.2f".format(it) } ?: "")
                        append(",")
                        append(gpsData.rtkStatus ?: "")
                        append(",")
                        append("\"${gpsData.rawNmea.replace("\"", "\"\"").trim()}\"")
                        append("\n")
                    }

                    writer?.append(csvLine)
                    writer?.flush()

                    // 定期的にファイルを同期（オプション）
                    if (System.currentTimeMillis() % 10000 < 100) { // 約10秒ごと
                        try {
                            writer?.close()
                            initFileWriter()
                        } catch (e: IOException) {
                            Log.w(TAG, "File sync error: ${e.message}")
                        }
                    }
                } else {
                    Log.w(TAG, "Writer is null or file not open, data not logged: ${gpsData.timestamp}")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Data write error: ${e.message}", e)
                isFileWriterOpen.set(false)
                // ファイル書き込みエラー時の再初期化を試行
                try {
                    writer?.close()
                } catch (closeError: IOException) {
                    Log.e(TAG, "Error closing writer: ${closeError.message}")
                }
                writer = null
                // 次回のwriteで再初期化が試行される
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in file logging: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopGpsAndNtripConnection()

        // ExecutorServiceを適切にシャットダウン
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        // ファイルライターを確実にクローズ
        try {
            writer?.close()
            isFileWriterOpen.set(false)
        } catch (e: IOException) {
            Log.e(TAG, "File close error: ${e.message}", e)
        }
        Log.d(TAG, "Service destroyed")
    }

    // --- Activityとの通信用メソッド ---
    private fun logToActivity(message: String) {
        SharedData.logMessage.tryEmit("$message [${System.currentTimeMillis()}]")
    }

    private fun broadcastGpsData(data: GpsData) {
        SharedData.gpsData.tryEmit(data)
    }

    private fun broadcastConnectionStatus(isConnected: Boolean) {
        SharedData.connectionStatus.tryEmit(isConnected)
    }

    // --- Notification ---
    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS-RTK サービス実行中")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "フォアグラウンドサービスチャンネル",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- NMEA Parsing Logic ---
    private fun parseNmeaSentence(nmeaSentence: String): GpsData? {
        // デフォルトの受信時刻（JST）
        val defaultTimestamp = jstFormatter.format(Date())

        if (nmeaSentence.startsWith("\$GPGGA") || nmeaSentence.startsWith("\$GNGGA")) {
            val parts = nmeaSentence.split(",")
            if (parts.size >= 10) {
                try {
                    // UTC時刻の取得（フィールド1）
                    val utcTimeString = parts[1]
                    val timestamp = parseUtcTimeFromGGA(utcTimeString) ?: defaultTimestamp

                    val latitude = convertNmeaToDecimalDegrees(parts[2], parts[3])
                    val longitude = convertNmeaToDecimalDegrees(parts[4], parts[5])
                    val quality = parts[6].toIntOrNull()
                    val altitude = parts[9].toDoubleOrNull()
                    val rtkStatus = when (quality) {
                        4 -> "FIX"
                        5 -> "FLOAT"
                        else -> "NONE"
                    }
                    return GpsData(timestamp, latitude, longitude, altitude, rtkStatus = rtkStatus, rawNmea = nmeaSentence)
                } catch (e: Exception) {
                    Log.e("NMEA_PARSE", "GPGGA parse error: ${e.message} (Data: $nmeaSentence)")
                    return null
                }
            }
        } else if (nmeaSentence.startsWith("\$GPHDT") || nmeaSentence.startsWith("\$GNHDT")) {
            val parts = nmeaSentence.split(",")
            if (parts.size >= 2) {
                try {
                    val heading = parts[1].toDoubleOrNull()
                    // HDTメッセージにはUTC時刻が含まれないので、受信時刻（JST）を使用
                    return GpsData(defaultTimestamp, heading = heading, rawNmea = nmeaSentence)
                } catch (e: Exception) {
                    Log.e("NMEA_PARSE", "HDT parse error: ${e.message} (Data: $nmeaSentence)")
                    return null
                }
            }
        }
        return null
    }

    /**
     * GGAメッセージからUTC時刻を解析して日本時間（JST）に変換
     * @param utcTimeString GGAメッセージの時刻フィールド (例: "123456.789")
     * @return JST時刻文字列 (例: "21:34:56.789") または null
     */
    private fun parseUtcTimeFromGGA(utcTimeString: String): String? {
        if (utcTimeString.isBlank()) return null

        try {
            // UTC時刻の形式: HHMMSS.SSS または HHMMSS
            val timeValue = utcTimeString.toDouble()
            val hours = (timeValue / 10000).toInt()
            val minutes = ((timeValue % 10000) / 100).toInt()
            val seconds = timeValue % 100

            // 時刻の妥当性チェック
            if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59 || seconds < 0 || seconds >= 60) {
                Log.w("NMEA_PARSE", "Invalid UTC time values: $utcTimeString")
                return null
            }

            // UTCから日本時間（JST）に変換（+9時間）
            val jstHours = (hours + 9) % 24

            // フォーマット: HH:MM:SS.SSS（JST）
            return String.format(Locale.US, "%02d:%02d:%06.3f", jstHours, minutes, seconds)
        } catch (e: Exception) {
            Log.e("NMEA_PARSE", "UTC time parse error: ${e.message} (Data: $utcTimeString)")
            return null
        }
    }

    private fun convertNmeaToDecimalDegrees(nmeaCoord: String, direction: String): Double? {
        if (nmeaCoord.isBlank() || direction.isBlank()) return null
        return try {
            val dotIndex = nmeaCoord.indexOf('.')
            if (dotIndex == -1 || dotIndex < 2) return null
            val degrees = nmeaCoord.substring(0, dotIndex - 2).toDouble()
            val minutes = nmeaCoord.substring(dotIndex - 2).toDouble()
            var decimalDegrees = degrees + (minutes / 60.0)
            if (direction == "S" || direction == "W") {
                decimalDegrees *= -1.0
            }
            decimalDegrees
        } catch (e: Exception) {
            Log.e("NMEA_CONVERT", "Coordinate conversion error: ${e.message} (Data: $nmeaCoord $direction)")
            null
        }
    }

    companion object {
        const val ACTION_START = "com.example.gps_rtk_droid2.ACTION_START"
        const val ACTION_STOP = "com.example.gps_rtk_droid2.ACTION_STOP"
        private const val CHANNEL_ID = "ForegroundServiceChannel"
        private const val NOTIFICATION_ID = 1
    }
}