package com.example.gps_rtk_droid2

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gps_rtk_droid2.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private var usbDevice: UsbDevice? = null
    private val handler = Handler(Looper.getMainLooper())

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (usbDevice == null) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        usbDevice = device
                        checkUsbDevice()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        logMessage("USB Permission granted.")
                    } else {
                        logMessage("USB Permission denied.")
                        usbDevice = null
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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        binding.logTextView.movementMethod = ScrollingMovementMethod()

        binding.connectButton.setOnClickListener { startConnection() }
        binding.disconnectButton.setOnClickListener { stopConnection() }

        updateUiState(false)
        observeSharedData()
    }

    override fun onResume() {
        super.onResume()
        checkUsbDevice()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(usbReceiver)
    }

    private fun observeSharedData() {
        lifecycleScope.launch {
            SharedData.logMessage.collectLatest { message ->
                message?.let { logMessage(it.substringBefore(" [")) }
            }
        }
        lifecycleScope.launch {
            SharedData.gpsData.collectLatest { data ->
                data?.let { updateGpsUi(it) }
            }
        }
        lifecycleScope.launch {
            SharedData.connectionStatus.collectLatest { isConnected ->
                updateUiState(isConnected)
            }
        }
    }

    private fun checkUsbDevice() {
        val deviceList = usbManager.deviceList
        val YOUR_VENDOR_ID = 1027
        val YOUR_PRODUCT_ID = 24577

        usbDevice = deviceList.values.firstOrNull { device ->
            device.vendorId == YOUR_VENDOR_ID && device.productId == YOUR_PRODUCT_ID
        }

        if (usbDevice == null) {
            logMessage("USB GPSデバイスが接続されていません")
        } else {
            logMessage("USB GPSデバイスを検出: ${usbDevice?.deviceName}")
            requestUsbPermission()
        }
    }

    private fun requestUsbPermission() {
        usbDevice?.let { device ->
            if (!usbManager.hasPermission(device)) {
                val permissionIntent = PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
                )
                usbManager.requestPermission(device, permissionIntent)
            }
        }
    }

    private fun startConnection() {
        if (usbDevice == null) {
            logMessage("USB GPSデバイスが選択されていません。")
            Toast.makeText(this, "USB GPSデバイスが未接続です", Toast.LENGTH_SHORT).show()
            return
        }

        val server = binding.ntripServerEditText.text.toString()
        val mountPoint = binding.ntripMountpointEditText.text.toString()
        if (server.isEmpty() || mountPoint.isEmpty()) {
            Toast.makeText(this, "NTRIP設定が不完全です", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, GpsRtkService::class.java).apply {
            action = GpsRtkService.ACTION_START
            putExtra("inputExtra", "バックグラウンド処理を開始しました")
            putExtra("usb_device", usbDevice)
            putExtra("ntrip_server", server)
            putExtra("ntrip_port", binding.ntripPortEditText.text.toString().toIntOrNull() ?: 2101)
            putExtra("ntrip_mountpoint", mountPoint)
            putExtra("ntrip_user", binding.ntripUserEditText.text.toString())
            putExtra("ntrip_password", binding.ntripPasswordEditText.text.toString())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopConnection() {
        val serviceIntent = Intent(this, GpsRtkService::class.java).apply {
            action = GpsRtkService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun updateGpsUi(data: GpsData) {
        data.latitude?.let { lat ->
            val latHemi = if (lat < 0) "S" else "N"
            binding.latitudeTextView.text = "%.6f %s".format(Math.abs(lat), latHemi)
        }
        data.longitude?.let { lon ->
            val lonHemi = if (lon < 0) "W" else "E"
            binding.longitudeTextView.text = "%.6f %s".format(Math.abs(lon), lonHemi)
        }
        data.altitude?.let { alt ->
            binding.altitudeTextView.text = "%.2f m".format(alt)
        }
        data.rtkStatus?.let {
            updateRtkStatus(it)
        }
    }

    private fun updateRtkStatus(status: String) {
        val (color, text) = when (status) {
            "FIX" -> Pair(android.graphics.Color.GREEN, "RTK: FIX")
            "FLOAT" -> Pair(android.graphics.Color.YELLOW, "RTK: FLOAT")
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
                }
            }
        }
    }

    private fun updateUiState(connected: Boolean) {
        binding.connectButton.isEnabled = !connected
        binding.disconnectButton.isEnabled = connected
        binding.ntripServerEditText.isEnabled = !connected
        binding.ntripPortEditText.isEnabled = !connected
        binding.ntripMountpointEditText.isEnabled = !connected
        binding.ntripUserEditText.isEnabled = !connected
        binding.ntripPasswordEditText.isEnabled = !connected
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.gps_rtk_droid2.USB_PERMISSION"
    }
}