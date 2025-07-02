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
            ?: throw IOException("互換性のあるUSBシリアルドライバーが見つかりません")

        serialPort = driver.ports.firstOrNull()
            ?: throw IOException("シリアルポートが見つかりません")

        connection = usbManager.openDevice(usbDevice)
            ?: throw IOException("USBデバイスを開けませんでした")

        serialPort?.apply {
            open(connection)
            setParameters(
                115200,  // ボーレート
                8,       // データビット
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
        }

        // ここが重要：SerialInputOutputManagerを初期化して開始する
        ioManager = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                // 受信したデータを文字列に変換してリスナーに渡す
                val text = String(data, Charsets.UTF_8) // NMEAは通常UTF-8
                listener?.onGpsDataReceived(text)
            }

            override fun onRunError(e: Exception) {
                // エラーが発生した場合、リスナーに通知
                listener?.onError(e.message ?: "USB通信エラー")
            }
        })

        // バックグラウンドスレッドでIOマネージャーを開始
        executor.execute { ioManager?.start() }
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
            closeError = "Usb Connection close error: ${e.message}"
        } finally {
            connection = null
        }

        if (closeError != null) {
            listener?.onError("USB GPS切断中にエラーが発生しました: $closeError")
        }
    }
}
