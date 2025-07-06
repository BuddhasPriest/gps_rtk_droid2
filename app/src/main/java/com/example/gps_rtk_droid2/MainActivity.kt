package com.example.gps_rtk_droid2

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gps_rtk_droid2.databinding.ActivityMainBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NtripServerInfo(
    val server: String,
    val port: Int,
    val mountPoint: String,
    val username: String,
    val password: String,
    val displayName: String = "$server:$port/$mountPoint"
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var gson: Gson
    private var usbDevice: UsbDevice? = null
    private val handler = Handler(Looper.getMainLooper())

    private var savedNtripServers = mutableListOf<NtripServerInfo>()
    private lateinit var spinnerAdapter: ArrayAdapter<String>

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.gps_rtk_droid2.USB_PERMISSION"
        private const val PREFS_NAME = "ntrip_servers"
        private const val KEY_SERVERS = "servers"
    }

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

        // SharedPreferencesとGsonを初期化
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        gson = Gson()

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        binding.logTextView.movementMethod = ScrollingMovementMethod()

        // 保存されたサーバー情報を読み込み
        loadSavedServers()

        // スピナーを設定
        setupSpinner()

        // ViewBindingを使用してクリックリスナーを設定
        binding.saveButton.setOnClickListener {
            saveCurrentServer()
        }
        binding.connectButton.setOnClickListener {
            startConnection()
        }
        binding.disconnectButton.setOnClickListener { stopConnection() }

        updateUiState(false)
        observeSharedData()
    }

    private fun loadSavedServers() {
        val serversJson = sharedPreferences.getString(KEY_SERVERS, null)
        if (serversJson != null) {
            val type = object : TypeToken<List<NtripServerInfo>>() {}.type
            val servers: List<NtripServerInfo> = gson.fromJson(serversJson, type)
            savedNtripServers.clear()
            savedNtripServers.addAll(servers)
        }

        // デフォルトサーバーを追加（まだ保存されていない場合）
        if (savedNtripServers.isEmpty()) {
            savedNtripServers.add(
                NtripServerInfo(
                    server = "rtk2go.com",
                    port = 2101,
                    mountPoint = "ANY",
                    username = "",
                    password = "",
                    displayName = "RTK2GO Default"
                )
            )
            saveServers()
        }
    }

    private fun saveServers() {
        val serversJson = gson.toJson(savedNtripServers)
        sharedPreferences.edit().putString(KEY_SERVERS, serversJson).apply()
    }

    private fun saveCurrentServer() {
        val serverName = binding.ntripServerNameEditText.text.toString().trim()
        val server = binding.ntripServerEditText.text.toString().trim()
        val port = binding.ntripPortEditText.text.toString().toIntOrNull() ?: 2101
        val mountPoint = binding.ntripMountpointEditText.text.toString().trim()
        val username = binding.ntripUserEditText.text.toString().trim()
        val password = binding.ntripPasswordEditText.text.toString().trim()

        if (server.isEmpty() || mountPoint.isEmpty()) {
            Toast.makeText(this, "サーバー名とマウントポイントは必須です", Toast.LENGTH_SHORT).show()
            return
        }

        // デフォルトの表示名を生成
        val defaultDisplayName = "$server:$port/$mountPoint"

        // ユーザーが入力した名前を使用、空の場合はデフォルトを使用
        val finalDisplayName = if (serverName.isEmpty()) {
            defaultDisplayName
        } else {
            // ユーザー入力がデフォルトと同じ場合はそのまま、異なる場合は両方表示
            if (serverName == defaultDisplayName) {
                serverName
            } else {
                "$serverName ($defaultDisplayName)"
            }
        }

        val newServer = NtripServerInfo(
            server = server,
            port = port,
            mountPoint = mountPoint,
            username = username,
            password = password,
            displayName = finalDisplayName
        )

        // 既存のサーバーと重複チェック（サーバー、ポート、マウントポイントで判定）
        val existingIndex = savedNtripServers.indexOfFirst {
            it.server == newServer.server &&
                    it.port == newServer.port &&
                    it.mountPoint == newServer.mountPoint
        }

        if (existingIndex >= 0) {
            // 既存のサーバーを更新
            savedNtripServers[existingIndex] = newServer
            Toast.makeText(this, "サーバー設定を更新しました", Toast.LENGTH_SHORT).show()
        } else {
            // 新しいサーバーを追加
            savedNtripServers.add(newServer)
            Toast.makeText(this, "新しいサーバー設定を保存しました", Toast.LENGTH_SHORT).show()
        }

        saveServers()
        updateSpinner()

        // 保存後、スピナーで新しく保存されたサーバーを選択
        val savedServerIndex = savedNtripServers.indexOfFirst {
            it.server == newServer.server &&
                    it.port == newServer.port &&
                    it.mountPoint == newServer.mountPoint
        }
        if (savedServerIndex >= 0) {
            binding.ntripServerSpinner.setSelection(savedServerIndex + 1) // +1 because of "新しいサーバー" at index 0
        }
    }

    private fun setupSpinner() {
        val displayNames = mutableListOf<String>()
        displayNames.add("新しいサーバー") // 最初の項目
        displayNames.addAll(savedNtripServers.map { it.displayName })

        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.ntripServerSpinner.adapter = spinnerAdapter

        binding.ntripServerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    // "新しいサーバー"が選択された場合、フィールドをクリア
                    clearServerFields()
                } else {
                    // 保存されたサーバーが選択された場合、フィールドに設定
                    val selectedServer = savedNtripServers[position - 1]
                    loadServerToFields(selectedServer)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // 何もしない
            }
        }
    }

    private fun updateSpinner() {
        val displayNames = mutableListOf<String>()
        displayNames.add("新しいサーバー")
        displayNames.addAll(savedNtripServers.map { it.displayName })

        spinnerAdapter.clear()
        spinnerAdapter.addAll(displayNames)
        spinnerAdapter.notifyDataSetChanged()
    }

    private fun clearServerFields() {
        binding.ntripServerNameEditText.setText("")
        binding.ntripServerEditText.setText("")
        binding.ntripPortEditText.setText("2101")
        binding.ntripMountpointEditText.setText("")
        binding.ntripUserEditText.setText("")
        binding.ntripPasswordEditText.setText("")

        // デフォルトのプレースホルダーを表示
        updateServerNamePlaceholder()
    }

    private fun loadServerToFields(serverInfo: NtripServerInfo) {
        // 表示名からユーザー入力部分を抽出
        val displayName = serverInfo.displayName
        val defaultName = "${serverInfo.server}:${serverInfo.port}/${serverInfo.mountPoint}"

        val userName = if (displayName.contains(" (") && displayName.endsWith(")")) {
            // "ユーザー名 (デフォルト名)" の形式の場合
            displayName.substring(0, displayName.indexOf(" ("))
        } else if (displayName == defaultName) {
            // デフォルト名そのものの場合
            ""
        } else {
            // その他の場合はそのまま
            displayName
        }

        binding.ntripServerNameEditText.setText(userName)
        binding.ntripServerEditText.setText(serverInfo.server)
        binding.ntripPortEditText.setText(serverInfo.port.toString())
        binding.ntripMountpointEditText.setText(serverInfo.mountPoint)
        binding.ntripUserEditText.setText(serverInfo.username)
        binding.ntripPasswordEditText.setText(serverInfo.password)

        updateServerNamePlaceholder()
    }

    private fun updateServerNamePlaceholder() {
        val server = binding.ntripServerEditText.text.toString().trim()
        val port = binding.ntripPortEditText.text.toString().trim()
        val mountPoint = binding.ntripMountpointEditText.text.toString().trim()

        if (server.isNotEmpty() && port.isNotEmpty() && mountPoint.isNotEmpty()) {
            val defaultName = "$server:$port/$mountPoint"
            binding.ntripServerNameEditText.hint = "例: $defaultName"
        } else {
            binding.ntripServerNameEditText.hint = "サーバー名（任意）"
        }
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
        binding.saveButton.isEnabled = !connected
        binding.ntripServerSpinner.isEnabled = !connected
        binding.ntripServerNameEditText.isEnabled = !connected
        binding.ntripServerEditText.isEnabled = !connected
        binding.ntripPortEditText.isEnabled = !connected
        binding.ntripMountpointEditText.isEnabled = !connected
        binding.ntripUserEditText.isEnabled = !connected
        binding.ntripPasswordEditText.isEnabled = !connected

        // 接続中でない場合は、フィールドの変更を監視してプレースホルダーを更新
        if (!connected) {
            setupFieldChangeListeners()
        }
    }

    private fun setupFieldChangeListeners() {
        val textWatcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                updateServerNamePlaceholder()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        binding.ntripServerEditText.addTextChangedListener(textWatcher)
        binding.ntripPortEditText.addTextChangedListener(textWatcher)
        binding.ntripMountpointEditText.addTextChangedListener(textWatcher)
    }
}