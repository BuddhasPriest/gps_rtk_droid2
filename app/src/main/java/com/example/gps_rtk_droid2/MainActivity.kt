package com.example.gps_rtk_droid2

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.gps_rtk_droid2.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

import android.os.PowerManager
import android.os.Build
import android.util.Log // Logクラスのインポートを追加

import java.io.File // ★追加
import java.io.FileWriter // ★追加
import java.io.IOException // ★追加


data class GpsData(
    val timestamp: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val heading: Double? = null,
    val rtkStatus: String? = null, // ★RTKステータスを追加
    val rawNmea: String // 元のNMEAメッセージも保持
)

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbGpsConnection: UsbGpsConnection? = null
    private var ntripClient: NtripClient? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    // PowerManagerとWakeLockのインスタンス
    private lateinit var wakeLock: PowerManager.WakeLock

    // USB接続のブロードキャストレシーバー
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // checkUsbDeviceによって既にデバイスが見つかっているかを確認し、
                    // 見つかっていない場合のみ新しいデバイスを設定してチェックを続行
                    if (usbDevice == null) {
                        usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        checkUsbDevice() // 接続されたデバイスが目的のGPSデバイスか確認
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        logMessage("USB Permission granted.")
                        // パーミッションが付与された後、ここで自動的に接続を開始することも可能ですが、
                        // 現在のコードではユーザーが「接続」ボタンを押すことを想定しています。
                        // 例: startConnection()
                    } else {
                        logMessage("USB Permission denied.")
                        usbDevice = null // パーミッションがないためusbDeviceをリセット
                        Toast.makeText(context, "USBパーミッションが拒否されました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        binding.logTextView.movementMethod = ScrollingMovementMethod()

        binding.connectButton.setOnClickListener { startConnection() }
        binding.disconnectButton.setOnClickListener { stopConnection() }

        updateUiState(false)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gps_rtk_droid2::MyWakeLockTag")

        startMyForegroundService()

        // デバッグ用: 接続されているUSBデバイスのVID/PIDをログに出力
        // 実際のデバイスのVID/PIDを確認するために一時的に使用できます
        // for (deviceEntry in usbManager.deviceList.entries) {
        //     val device = deviceEntry.value
        //     Log.d("USB_DEBUG", "Found USB Device: ${device.deviceName}, VID: 0x${device.vendorId.toString(16)}, PID: 0x${device.productId.toString(16)}")
        // }
    }

    override fun onResume() {
        super.onResume()
        checkUsbDevice()
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(ACTION_USB_PERMISSION) // USBパーミッションのアクションを追加

        // Android 14 (API 34) 以降の対応
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // RECEIVER_NOT_EXPORTED フラグを追加
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // それ以前のAndroidバージョン
            registerReceiver(usbReceiver, filter)
        }
    }


    override fun onPause() {
        super.onPause()
        unregisterReceiver(usbReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopConnection()
        stopMyForegroundService()
    }

    private fun checkUsbDevice() {
        val deviceList = usbManager.deviceList
        // YOUR_VENDOR_ID と YOUR_PRODUCT_ID はXMLから読み込む想定
        // ここでは仮の値を設定しています。実際のアプリケーションではXMLから取得してください。
        // 例: val YOUR_VENDOR_ID = resources.getInteger(R.integer.gps_vendor_id)
        // 例: val YOUR_PRODUCT_ID = resources.getInteger(R.integer.gps_product_id)
        val YOUR_VENDOR_ID = 1027// 仮のベンダーID。XMLから読み込む値に置き換えてください。
        val YOUR_PRODUCT_ID = 24577 // 仮のプロダクトID。XMLから読み込む値に置き換えてください。

        usbDevice = deviceList.values.firstOrNull { device ->
            device.vendorId == YOUR_VENDOR_ID && device.productId == YOUR_PRODUCT_ID
        }

        if (usbDevice == null) {
            logMessage("USB GPSデバイスが接続されていません")
            Toast.makeText(this, "USB GPSデバイスが見つかりません", Toast.LENGTH_LONG).show()
        } else {
            logMessage("USB GPSデバイスを検出: ${usbDevice?.deviceName}")
            requestUsbPermission()
        }
    }

    private fun requestUsbPermission() {
        usbDevice?.let { device ->
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun startConnection() {
        if (isRunning) {
            logMessage("既に接続中です")
            return
        }

        if (usbDevice == null) {
            logMessage("USB GPSデバイスが選択されていません。デバイスを確認してください。")
            Toast.makeText(this, "USB GPSデバイスが未接続です", Toast.LENGTH_SHORT).show()
            return
        }

        val server = binding.ntripServerEditText.text.toString()
        val port = binding.ntripPortEditText.text.toString().toIntOrNull() ?: 2101
        val mountPoint = binding.ntripMountpointEditText.text.toString()
        val username = binding.ntripUserEditText.text.toString()
        val password = binding.ntripPasswordEditText.text.toString()

        if (server.isEmpty() || mountPoint.isEmpty()) {
            logMessage("NTRIPサーバーとマウントポイントを入力してください")
            Toast.makeText(this, "NTRIP設定が不完全です", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // UsbGpsConnectionの初期化時にusbDeviceがnullでないことを保証
            usbGpsConnection = UsbGpsConnection(usbManager, usbDevice!!).apply {
                setDataListener(object : UsbGpsConnection.GpsDataListener {
                    override fun onGpsDataReceived(data: String) {
                        parseGpsData(data) // ★ここに修正を加え、ファイル保存ロジックを追加
                    }

                    override fun onError(error: String) {
                        logMessage("GPSエラー: $error")
                        handler.post { Toast.makeText(this@MainActivity, "GPSエラー: $error", Toast.LENGTH_LONG).show() }
                        stopConnection()
                    }
                })
                openConnection()
            }

            ntripClient = NtripClient(server, port, mountPoint, username, password,
                object : NtripClient.NtripClientListener {
                    override fun onRtcmDataReceived(data: ByteArray) {
                        usbGpsConnection?.write(data)
                    }

                    override fun onStatusChanged(message: String) {
                        logMessage(message)
                        handler.post { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() } // 追加
                    }

                    override fun onError(error: String) {
                        logMessage("NTRIPエラー: $error")
                        handler.post { Toast.makeText(this@MainActivity, "NTRIPエラー: $error", Toast.LENGTH_LONG).show() } // 追加
                        stopConnection()
                    }
                })

            executor.execute {
                ntripClient?.connect()
            }

            isRunning = true
            updateUiState(true)
            logMessage("接続を開始しました")
            Toast.makeText(this, "接続を開始しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logMessage("接続エラー: ${e.message}")
            handler.post { Toast.makeText(this, "接続エラー: ${e.message}", Toast.LENGTH_LONG).show() }
            stopConnection()
        }
    }

    private fun stopConnection() {
        if (!isRunning) return

        try {
            ntripClient?.disconnect()
            usbGpsConnection?.closeConnection()
            logMessage("接続を停止しました")
            Toast.makeText(this, "接続を停止しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logMessage("切断エラー: ${e.message}")
            handler.post { Toast.makeText(this, "切断エラー: ${e.message}", Toast.LENGTH_LONG).show() }
        } finally {
            ntripClient = null
            usbGpsConnection = null
            isRunning = false
            updateUiState(false)
        }
    }

    // ★この関数を修正し、GpsDataオブジェクトをログファイルに保存するように変更
    private fun parseGpsData(data: String) {
        val parsedData = parseNmeaSentence(data) // NMEAセンテンスをパースしてGpsDataオブジェクトを取得

        if (parsedData != null) {
            // UI更新
            handler.post {
                parsedData.latitude?.let { lat ->
                    val latHemi = if (lat < 0) "S" else "N"
                    binding.latitudeTextView.text = "%.6f %s".format(Math.abs(lat), latHemi)
                } ?: run { binding.latitudeTextView.text = "N/A" }

                parsedData.longitude?.let { lon ->
                    val lonHemi = if (lon < 0) "W" else "E"
                    binding.longitudeTextView.text = "%.6f %s".format(Math.abs(lon), lonHemi)
                } ?: run { binding.longitudeTextView.text = "N/A" }

                parsedData.altitude?.let { alt ->
                    binding.altitudeTextView.text = "%.2f m".format(alt)
                } ?: run { binding.altitudeTextView.text = "N/A" }

                parsedData.rtkStatus?.let { status ->
                    updateRtkStatus(status)
                } ?: run { updateRtkStatus("NONE") } // RTKステータスがない場合はNONEを表示
            }

            // ★パースされたGPSデータをファイルにログ保存
            logParsedGpsDataToFile(parsedData)
        } else {
            // パースできなかったNMEAセンテンスもログに残す
            logMessage("NMEAパース失敗: $data")
        }
    }


    private fun updateRtkStatus(status: String) {
        val (color, text) = when (status) {
            "FIX" -> Pair(android.graphics.Color.GREEN, "RTK: FIX")
            "FLOAT" -> Pair(android.graphics.Color.RED, "RTK: FLOAT")
            else -> Pair(android.graphics.Color.BLACK, "RTK: NONE")
        }

        binding.rtkStatusTextView.setTextColor(color)
        binding.rtkStatusTextView.text = text
        binding.rtkIndicatorView.setBackgroundColor(color)
    }

    private fun logMessage(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logMessage = "$timestamp $message\n"

        handler.post {
            binding.logTextView.append(logMessage)
            val layout = binding.logTextView.layout
            if (layout != null) {
                val scrollAmount = layout.getLineTop(binding.logTextView.lineCount) - binding.logTextView.height
                if (scrollAmount > 0) {
                    binding.logTextView.scrollTo(0, scrollAmount)
                } else {
                    binding.logTextView.scrollTo(0, 0)
                }
            }
        }
    }

    private fun updateUiState(connected: Boolean) {
        binding.connectButton.isEnabled = !connected
        binding.disconnectButton.isEnabled = connected

        val fields = listOf(
            binding.ntripServerEditText,
            binding.ntripPortEditText,
            binding.ntripMountpointEditText,
            binding.ntripUserEditText,
            binding.ntripPasswordEditText
        )

        fields.forEach { it.isEnabled = !connected }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.gps_rtk_droid2.USB_PERMISSION"
        // XMLから読み込むためのプレースホルダー。実際の値に置き換えるか、XML読み込み処理を実装してください。
        // const val YOUR_VENDOR_ID = 0x1234
        // const val YOUR_PRODUCT_ID = 0x5678
    }

    private fun startMyForegroundService() {
        val serviceIntent = Intent(this, GpsRtkService::class.java)
        serviceIntent.putExtra("inputExtra", "バックグラウンド処理を開始しました")

        // Android 8.0 (API レベル 26) 以降では startForegroundService を使用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopMyForegroundService() {
        val serviceIntent = Intent(this, GpsRtkService::class.java)
        stopService(serviceIntent)
    }

    // NMEAセンテンスをパースしてGpsDataオブジェクトを生成する関数
    private fun parseNmeaSentence(nmeaSentence: String): GpsData? {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

        if (nmeaSentence.startsWith("\$GPGGA") || nmeaSentence.startsWith("\$GNGGA")) {
            // GPGGAセンテンスのパース
            val parts = nmeaSentence.split(",")
            if (parts.size >= 10) { // GPGGAに必要な最低限のパート数
                try {
                    val latitudeStr = parts[2]
                    val latDir = parts[3]
                    val longitudeStr = parts[4]
                    val lonDir = parts[5]
                    val quality = parts[6].toIntOrNull() // 品質インジケータ
                    val altitudeStr = parts[9] // 海抜からの高さ

                    val latitude = convertNmeaToDecimalDegrees(latitudeStr, latDir)
                    val longitude = convertNmeaToDecimalDegrees(longitudeStr, lonDir)
                    val altitude = altitudeStr.toDoubleOrNull()

                    val rtkStatus = when (quality) { // RTKステータスを決定
                        4 -> "FIX"
                        5 -> "FLOAT"
                        else -> "NONE"
                    }

                    return GpsData(
                        timestamp,
                        latitude,
                        longitude,
                        altitude,
                        rtkStatus = rtkStatus, // ★RTKステータスをGpsDataに含める
                        rawNmea = nmeaSentence
                    )
                } catch (e: Exception) {
                    Log.e("NMEA_PARSE", "GPGGAパースエラー: ${e.message} (元データ: $nmeaSentence)")
                    return null
                }
            }
        } else if (nmeaSentence.startsWith("\$GPHDT") || nmeaSentence.startsWith("\$GNHDT")) {
            // HDTセンテンスのパース (ヘディング)
            val parts = nmeaSentence.split(",")
            if (parts.size >= 2) {
                try {
                    val heading = parts[1].toDoubleOrNull()
                    return GpsData(timestamp, heading = heading, rawNmea = nmeaSentence)
                } catch (e: Exception) {
                    Log.e("NMEA_PARSE", "HDTパースエラー: ${e.message} (元データ: $nmeaSentence)")
                    return null
                }
            }
        }
        // 認識できないセンテンス、またはパースできなかった場合はnullを返す
        return null
    }

    // NMEA形式の緯度・経度を10進数形式に変換するヘルパー関数
    private fun convertNmeaToDecimalDegrees(nmeaCoord: String, direction: String): Double? {
        if (nmeaCoord.length < 5) return null // ddmm.mmmm の形式を想定
        try {
            val dotIndex = nmeaCoord.indexOf(".")
            if (dotIndex == -1 || dotIndex < 2) return null // ドットがないか、形式が不正

            val degrees = nmeaCoord.substring(0, dotIndex - 2).toDouble()
            val minutes = nmeaCoord.substring(dotIndex - 2).toDouble()
            var decimalDegrees = degrees + (minutes / 60.0)

            if (direction == "S" || direction == "W") {
                decimalDegrees *= -1.0
            }
            return decimalDegrees
        } catch (e: Exception) {
            Log.e("NMEA_CONVERT", "NMEA座標変換エラー: ${e.message} (元データ: $nmeaCoord $direction)")
            return null
        }
    }

    // パース済みGPSデータをファイルにログ保存する関数
    private fun logParsedGpsDataToFile(gpsData: GpsData) {
        executor.execute { // ファイル書き込みはI/O操作なので別スレッドで実行
            try {
                // アプリ固有の外部ストレージディレクトリにCSV形式で保存
                // 例: /sdcard/Android/data/com.example.gps_rtk_droid2/files/gps_rtk_parsed_log.csv
                val logFile = File(getExternalFilesDir(null), "gps_rtk_parsed_log.csv")

                // ヘッダー行を書き込む (ファイルが新規作成される場合のみ)
                if (!logFile.exists()) {
                    FileWriter(logFile, true).use { writer ->
                        writer.append("Timestamp,Latitude,Longitude,Altitude,Heading,RtkStatus,RawNMEA\n") // ★ヘッダーにRtkStatusを追加
                    }
                }

                // データ行を書き込む (CSV形式)
                FileWriter(logFile, true).use { writer ->
                    writer.append("${gpsData.timestamp}," +
                            "${gpsData.latitude?.let { "%.6f".format(it) } ?: ""}," +
                            "${gpsData.longitude?.let { "%.6f".format(it) } ?: ""}," +
                            "${gpsData.altitude?.let { "%.3f".format(it) } ?: ""}," +
                            "${gpsData.heading?.let { "%.2f".format(it) } ?: ""}," +
                            "${gpsData.rtkStatus ?: ""}," + // ★RTKステータスを追加
                            "\"${gpsData.rawNmea}\"\n") // CSVでカンマを含む場合のために引用符で囲む
                }
            } catch (e: IOException) {
                Log.e("MainActivity", "パース済みログファイルへの書き込みエラー: ${e.message}")
            }
        }
    }
}