package com.example.gps_rtk_droid2

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.Executors
import com.hoho.android.usbserial.driver.UsbSerialProber

class UsbGpsConnection(
    private val usbManager: UsbManager,
    private val usbDevice: UsbDevice?
) {
    private var serialPort: UsbSerialPort? = null
    private var connection: UsbDeviceConnection? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var listener: GpsDataListener? = null
    var isRunning: Boolean = false // ★追加: 接続状態を示すプロパティ

    interface GpsDataListener {
        fun onGpsDataReceived(data: String)
        fun onError(error: String)
    }

    fun setDataListener(listener: GpsDataListener) {
        this.listener = listener
    }

    @Throws(IOException::class)
    fun openConnection() {
        if (usbDevice == null) {
            throw IOException("USBデバイスが接続されていません")
        }

        val driver = UsbSerialProber.getDefaultProber().probeDevice(usbDevice)
            ?: throw IOException("互換性のあるUSBシリアルドライバが見つかりませんでした。")

        // 最初のポートを使用する（通常、シリアルデバイスには1つしかポートがない）
        serialPort = driver.ports[0]

        connection = usbManager.openDevice(usbDevice)
            ?: throw IOException("USBデバイスへの接続を開けませんでした。")

        serialPort?.open(connection)
        serialPort?.setParameters(
            115200, // ボーレート (例: 115200bps)
            8,      // データビット
            UsbSerialPort.STOPBITS_1, // ストップビット
            UsbSerialPort.PARITY_NONE // パリティ
        )

        // データ受信のためのIOマネージャーを設定
        ioManager = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                // 受信したバイトデータを文字列（NMEA）に変換してリスナーに通知
                // GPSデバイスによってはUTF-8以外のエンコーディングの場合もあるので注意
                val receivedData = String(data, Charsets.UTF_8)
                listener?.onGpsDataReceived(receivedData)
            }

            override fun onRunError(e: Exception) {
                // エラーが発生した場合、リスナーに通知
                listener?.onError(e.message ?: "USB通信エラー")
            }
        })

        executor.execute { ioManager?.start() }
        isRunning = true // ★追加: 接続開始時にtrueに設定
    }

    fun write(data: ByteArray) {
        try {
            serialPort?.write(data, 0) // タイムアウト0はブロックしない
        } catch (e: IOException) {
            listener?.onError("GPSへのRTCMデータ書き込みエラー: ${e.message}")
        }
    }

    fun closeConnection() {
        var closeError: String? = null
        try {
            ioManager?.stop() // IOマネージャーを停止
        } catch (e: Exception) { // SerialInputOutputManager.stop()はIOExceptionをスローしないが念のため
            closeError = "IO Manager stop error: ${e.message}"
        } finally {
            ioManager = null
        }

        try {
            serialPort?.close() // シリアルポートをクローズ
        } catch (e: IOException) {
            closeError = "Serial Port close error: ${e.message}"
        } finally {
            serialPort = null
        }

        try {
            connection?.close() // USBデバイス接続をクローズ
        } catch (e: Exception) { // UsbDeviceConnection.close()はIOExceptionをスローしないが念のため
            closeError = "USB Device Connection close error: ${e.message}"
        } finally {
            connection = null
        }
        isRunning = false // ★追加: 接続終了時にfalseに設定

        if (closeError != null) {
            listener?.onError("USB接続クローズエラー: $closeError")
        }
    }
}