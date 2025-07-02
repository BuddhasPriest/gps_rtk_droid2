package com.example.gps_rtk_droid2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.gps_rtk_droid2.databinding.ActivityMainBinding
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue


private const val PERMISSION_REQUEST_CODE = 100 // 値は任意

// BuildConfig.APPLICATION_ID が解決できない、またはconst valの要件を満たさないため、
// val に変更し、アプリケーションIDを直接指定するか、他の方法で取得します。
// ここでは直接指定します。
val ACTION_USB_PERMISSION = "com.example.gps_rtk_droid2.USB_PERMISSION" // valに変更

// GpsRtkService.ktから移動した定数もここに含める
const val ACTION_USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
const val ACTION_USB_DEVICE_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false

    // GPSデータ処理用のキューとコルーチンスコープ
    private val gpsDataQueue = LinkedBlockingQueue<GpsData>()
    private var logProcessingJob: Job? = null
    private val logProcessingScope = CoroutineScope(Dispatchers.IO)

    // WakeLock
    private lateinit var wakeLock: PowerManager.WakeLock

    // CSVロギング関連
    private val logFile by lazy {
        val appSpecificExternalDir = getExternalFilesDir(null)
        File(appSpecificExternalDir, "gps_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv")
    }
    private val fileLogWriter = Executors.newSingleThreadExecutor()
    private var csvWriter: FileWriter? = null

    // USBパーミッション要求のためのPendingIntent
    private val usbPermissionIntent by lazy {
        PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    // USBパーミッションとデバイス接続のためのBroadcastReceiver
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.d("USB_PERMISSION", "USB permission granted for device: ${it.deviceName}")
                            connectUsbDevice(it)
                        }
                    } else {
                        Log.e("USB_PERMISSION", "USB permission denied for device: $device")
                        Toast.makeText(context, "USBデバイスの権限が拒否されました", Toast.LENGTH_LONG).show()
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                device?.let {
                    Log.d("USB_ATTACHED", "USB device attached: ${it.deviceName}")
                    val manager = getSystemService(Context.USB_SERVICE) as UsbManager
                    if (!manager.hasPermission(it)) {
                        manager.requestPermission(it, usbPermissionIntent)
                    } else {
                        connectUsbDevice(it)
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                device?.let {
                    Log.d("USB_DETACHED", "USB device detached: ${it.deviceName}")
                    stopGpsRtkService()
                    binding.usbStatusTextView.text = "USB状態: 切断"
                    Toast.makeText(context, "USBデバイスが取り外されました", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 画面を常にオンにする
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // TextViewをスクロール可能にする (logTextViewを使用)
        binding.logTextView.movementMethod = ScrollingMovementMethod()

        // NMEAデータ処理コルーチンの開始
        logProcessingJob = logProcessingScope.launch {
            while (isActive) {
                try {
                    val gpsData = gpsDataQueue.take() // キューからデータを取り出す
                    logGpsDataToUi(gpsData) // UIに表示
                    logGpsDataToFile(gpsData) // ファイルに保存
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.e("MainActivity", "NMEA data processing interrupted", e)
                    break
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error processing NMEA data", e)
                }
            }
        }

        // WakeLockの初期化
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GpsRtkDroid2::WakeLockTag")

        // USBパーミッションと接続/切断のBroadcastReceiverを登録
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)

        // UIボタンのセットアップ
        setupButtons()

        // アプリ起動時に権限を確認・要求
        checkAndRequestPermissions()

        // CSV Writerの初期化
        try {
            csvWriter = FileWriter(logFile, true) // Append mode
            if (logFile.length() == 0L) { // ファイルが空の場合のみヘッダーを書き込む
                csvWriter?.append("Timestamp,Latitude,Longitude,Altitude,Heading,RTK Status,Raw NMEA\n")
            }
            csvWriter?.flush()
            Log.i("FILE_LOG_WRITER", "CSV writer initialized successfully.")
        } catch (e: IOException) {
            Log.e("FILE_LOG_WRITER", "Error initializing CSV writer: ${e.message}")
            Toast.makeText(this, "CSVファイルの初期化に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // WakeLockの解放
        if (wakeLock.isHeld) {
            wakeLock.release()
        }

        // サービスが実行中の場合は停止する
        stopGpsRtkService()

        // BroadcastReceiverの登録解除
        unregisterReceiver(usbReceiver)

        // NMEAデータ処理コルーチンのキャンセル
        logProcessingJob?.cancel()

        // CSV Writerのクローズ
        try {
            csvWriter?.flush()
            csvWriter?.close()
            csvWriter = null
            Log.i("FILE_LOG_WRITER", "CSV writer closed successfully.")
        } catch (e: IOException) {
            Log.e("FILE_LOG_WRITER", "Error closing CSV writer: ${e.message}")
        }
        fileLogWriter.shutdown() // ExecutorServiceをシャットダウン

        Log.d("MainActivity", "onDestroy called")
    }

    private fun setupButtons() {
        // startServiceButtonとstopServiceButtonが存在することをactivity_main.xmlで確認済み
        binding.startServiceButton.setOnClickListener {
            checkAndRequestPermissions()
        }

        binding.stopServiceButton.setOnClickListener {
            stopGpsRtkService()
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf<String>().apply {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(android.Manifest.permission.FOREGROUND_SERVICE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest,
                PERMISSION_REQUEST_CODE
            )
        } else {
            // 全ての権限が既に許可されている場合、サービスを開始
            startMyForegroundService()
            Toast.makeText(this, "全ての権限が許可されています。", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allPermissionsGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allPermissionsGranted) {
                startMyForegroundService()
                Toast.makeText(this, "必要な権限が許可されました。", Toast.LENGTH_SHORT).show()
            } else {
                var showRationale = false
                for (permission in permissions) {
                    if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                        if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                            showRationale = true
                            break
                        }
                    }
                }

                if (showRationale) {
                    Toast.makeText(
                        this,
                        "このアプリは位置情報権限が必要です。再度許可してください。",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "必要な権限が永続的に拒否されました。設定から手動で許可してください。",
                        Toast.LENGTH_LONG
                    ).show()
                    val intent = Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    private fun startMyForegroundService() {
        if (!isServiceRunning) {
            if (!wakeLock.isHeld) {
                wakeLock.acquire()
                Log.d("MainActivity", "WakeLock acquired.")
            }

            val serviceIntent = Intent(this, GpsRtkService::class.java).apply {
                action = GpsRtkService.ACTION_START_FOREGROUND_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            isServiceRunning = true
            binding.serviceStatusTextView.text = "サービス状態: 実行中"
            Toast.makeText(this, "GPS RTKサービスを開始しました。", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "GPS RTK service started.")

            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList
            Log.d("MainActivity", "Found USB devices: ${deviceList.size}")
            if (deviceList.isEmpty()) {
                binding.usbStatusTextView.text = "USB状態: デバイスなし"
                Toast.makeText(this, "USBデバイスが見つかりません。", Toast.LENGTH_LONG).show()
            } else {
                for ((_, device) in deviceList) {
                    Log.d("MainActivity", "USB Device: ${device.deviceName}, VendorId: ${device.vendorId}, ProductId: ${device.productId}")
                    if (usbManager.hasPermission(device)) {
                        connectUsbDevice(device)
                        break
                    } else {
                        usbManager.requestPermission(device, usbPermissionIntent)
                        break
                    }
                }
            }
        } else {
            Toast.makeText(this, "GPS RTKサービスは既に実行中です。", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopGpsRtkService() {
        if (isServiceRunning) {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d("MainActivity", "WakeLock released.")
            }

            val serviceIntent = Intent(this, GpsRtkService::class.java).apply {
                action = GpsRtkService.ACTION_STOP_FOREGROUND_SERVICE
            }
            startService(serviceIntent)
            isServiceRunning = false
            binding.serviceStatusTextView.text = "サービス状態: 停止中"
            binding.usbStatusTextView.text = "USB状態: 未接続"
            Toast.makeText(this, "GPS RTKサービスを停止しました。", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "GPS RTK service stopped.")
        } else {
            Toast.makeText(this, "GPS RTKサービスは実行されていません。", Toast.LENGTH_SHORT).show()
        }
    }

    // GpsRtkServiceからのデータを受信
    private val gpsDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GpsRtkService.ACTION_GPS_DATA_UPDATE) {
                val timestamp = intent.getStringExtra("timestamp") ?: ""
                val latitude = intent.getDoubleExtra("latitude", Double.NaN)
                val longitude = intent.getDoubleExtra("longitude", Double.NaN)
                val altitude = intent.getDoubleExtra("altitude", Double.NaN)
                val heading = intent.getDoubleExtra("heading", Double.NaN)
                val rtkStatus = intent.getStringExtra("rtkStatus")
                val rawNmea = intent.getStringExtra("rawNmea") ?: ""

                val gpsData = GpsData(
                    timestamp = timestamp,
                    latitude = if (latitude.isNaN()) null else latitude,
                    longitude = if (longitude.isNaN()) null else longitude,
                    altitude = if (altitude.isNaN()) null else altitude,
                    heading = if (heading.isNaN()) null else heading,
                    rtkStatus = rtkStatus,
                    rawNmea = rawNmea
                )
                gpsDataQueue.offer(gpsData) // ここでキューにデータを追加
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(GpsRtkService.ACTION_GPS_DATA_UPDATE)
        // RECEIVER_NOT_EXPORTEDはAndroid 12 (API 31)以降で推奨されるフラグ
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33から推奨
            registerReceiver(gpsDataReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(gpsDataReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(gpsDataReceiver)
    }

    // activity_main.xml の UI部品IDに合わせて修正
    private fun logGpsDataToUi(data: GpsData) {
        uiHandler.post {
            // NMEAログ表示用のTextView (logTextViewを使用)
            binding.logTextView.append("${data.rawNmea}\n")
            val scrollAmount = binding.logTextView.layout?.getLineTop(binding.logTextView.lineCount)!! - binding.logTextView.height
            if (scrollAmount > 0) {
                binding.logTextView.scrollTo(0, scrollAmount)
            }

            // 個別のGPSデータ表示用TextViewを更新
            binding.latitudeValueTextView.text = data.latitude?.toString() ?: "N/A"
            binding.longitudeValueTextView.text = data.longitude?.toString() ?: "N/A"
            binding.altitudeValueTextView.text = data.altitude?.toString() ?: "N/A"
            binding.headingValueTextView.text = data.heading?.toString() ?: "N/A"
            binding.rtkStatusValueTextView.text = data.rtkStatus ?: "N/A"
            binding.timestampValueTextView.text = data.timestamp
        }
    }

    private fun logGpsDataToFile(data: GpsData) {
        fileLogWriter.execute {
            try {
                // csvWriterがnullの場合、またはエラー後の再試行で、再初期化を試みる
                if (csvWriter == null) {
                    try {
                        csvWriter = FileWriter(logFile, true) // 再初期化（追記モード）
                        // ファイルが空の場合のみヘッダーを再度書き込む
                        if (logFile.length() == 0L) {
                            csvWriter?.append("Timestamp,Latitude,Longitude,Altitude,Heading,RTK Status,Raw NMEA\n")
                        }
                        csvWriter?.flush()
                        Log.i("FILE_LOG_WRITER", "CSV writer re-initialized successfully after being null.")
                    } catch (e: IOException) {
                        Log.e("FILE_LOG_WRITER", "Error re-initializing CSV writer: ${e.message}")
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(this, "CSVファイル初期化エラー (再試行): ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        return@execute // 再初期化に失敗したら、今回の書き込みはスキップ
                    }
                }

                Log.d("FILE_LOG_WRITER", "Attempting to write GPS data to file for timestamp: ${data.timestamp}")
                // null許容型に合わせてデフォルト値を与える (?: "")
                // NMEAメッセージ内のカンマと引用符を適切にエスケープ
                val escapedRawNmea = data.rawNmea.replace("\"", "\"\"") // 内部の引用符を二重化
                val line = "${data.timestamp},${data.latitude ?: ""},${data.longitude ?: ""},${data.altitude ?: ""},${data.heading ?: ""},${data.rtkStatus ?: ""},\"$escapedRawNmea\"\n"
                csvWriter?.append(line)
                csvWriter?.flush() // 各書き込み後に即座にフラッシュ
                Log.d("FILE_LOG_WRITER", "Successfully wrote GPS data to file for timestamp: ${data.timestamp}")
            } catch (e: IOException) {
                Log.e("FILE_LOG_WRITER", "Error writing GPS data to file: ${e.message}")
                // ここで csvWriter をクローズしたり null に設定したりしない
                // 次の試行で回復するか、再初期化ロジックが働くことを期待する
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "CSVファイル書き込みエラー: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun connectUsbDevice(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Toast.makeText(this, "USBデバイスへの接続に失敗しました。", Toast.LENGTH_LONG).show()
            binding.usbStatusTextView.text = "USB状態: 接続失敗"
            return
        }

        try {
            val serialPort = findSerialPort(device, usbManager) // usbManagerを渡す
            if (serialPort == null) {
                Toast.makeText(this, "対応するUSBシリアルポートが見つかりませんでした。", Toast.LENGTH_LONG).show()
                binding.usbStatusTextView.text = "USB状態: シリアルポートなし"
                connection.close()
                return
            }

            val serviceIntent = Intent(this, GpsRtkService::class.java).apply {
                action = GpsRtkService.ACTION_USB_DEVICE_READY
                putExtra("USB_DEVICE", device)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            binding.usbStatusTextView.text = "USB状態: 接続済み (${device.deviceName})"
            Toast.makeText(this, "USBデバイス接続成功: ${device.deviceName}", Toast.LENGTH_SHORT).show()

        } catch (e: IOException) {
            Log.e("MainActivity", "USB接続中にエラーが発生しました: ${e.message}", e)
            Toast.makeText(this, "USB接続エラー: ${e.message}", Toast.LENGTH_LONG).show()
            binding.usbStatusTextView.text = "USB状態: エラー"
            connection.close()
        }
    }

    // findSerialPortの修正: usbManagerを受け取るように
    private fun findSerialPort(device: UsbDevice, usbManager: UsbManager): com.hoho.android.usbserial.driver.UsbSerialPort? {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        for (driver in drivers) {
            for (port in driver.ports) {
                if (port.device == device) {
                    return port
                }
            }
        }
        return null
    }
}