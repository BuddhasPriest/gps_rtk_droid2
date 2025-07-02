package com.example.gps_rtk_droid2

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64 // Android SDK 26以上で利用可能
import javax.net.ssl.SSLSocketFactory

class NtripClient(
    private val server: String,
    private val port: Int,
    private val mountPoint: String,
    private val username: String,
    private val password: String,
    private val listener: NtripClientListener
) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var rtcmInputStream: InputStream? = null // RTCMデータ用
    var isConnected = false
        private set // 外部からは読み取りのみ可能

    // 接続タイムアウト時間を設定 (ミリ秒)
    private val CONNECT_TIMEOUT_MS = 10000 // 例: 10秒
    // 読み込みタイムアウト時間を設定 (ミリ秒)
    private val READ_TIMEOUT_MS = 30000 // 例: 30秒 (RTCMデータがない場合に無限に待たないように)

    interface NtripClientListener {
        fun onRtcmDataReceived(data: ByteArray)
        fun onStatusChanged(status: String)
        fun onError(error: String)
    }

    @Throws(IOException::class)
    fun connect() {
        if (isConnected) {
            listener.onStatusChanged("NTRIP: 既に接続済みです。")
            return
        }

        listener.onStatusChanged("NTRIP: $server:$port/$mountPoint に接続中...")

        try {
            socket = Socket()
            socket?.connect(InetSocketAddress(server, port), CONNECT_TIMEOUT_MS)
            socket?.soTimeout = READ_TIMEOUT_MS // 読み込みタイムアウトを設定

            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            writer = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream()))
            rtcmInputStream = socket!!.getInputStream() // RTCMバイナリデータ読み込み用

            // NTRIPリクエストを送信
            val request = buildNtripRequest()
            writer?.write(request)
            writer?.flush()

            // Caster Responseの最初の行を読み取る
            val responseLine = reader?.readLine()
            if (responseLine == null || !responseLine.startsWith("ICY 200 OK") && !responseLine.startsWith("HTTP/1.0 200 OK")) {
                val errorMessage = "NTRIP: Caster Responseエラー: ${responseLine ?: "レスポンスなし"}"
                listener.onError(errorMessage)
                disconnect()
                throw IOException(errorMessage)
            }

            // HTTPヘッダーの残りを読み飛ばす
            var line: String?
            while (reader?.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                // ヘッダーをログに出力するなど、必要に応じて処理
                // Log.d("NtripClient", "Header: $line")
            }

            isConnected = true
            listener.onStatusChanged("NTRIP: $mountPoint に接続しました。RTCMデータ受信待機中。")

            // RTCMデータを連続して読み取る
            val buffer = ByteArray(4096) // 適切なバッファサイズ
            while (isConnected) {
                val bytesRead = rtcmInputStream?.read(buffer)
                if (bytesRead != null && bytesRead > 0) {
                    val receivedData = buffer.copyOfRange(0, bytesRead)
                    listener.onRtcmDataReceived(receivedData)
                } else if (bytesRead == -1) {
                    // ストリームの終端に達した（サーバーが切断した）
                    listener.onError("NTRIP: サーバーが切断しました。")
                    break
                }
            }

        } catch (e: UnknownHostException) {
            listener.onError("NTRIP: ホスト名解決エラー: ${e.message}")
            throw e
        } catch (e: ConnectException) {
            listener.onError("NTRIP: 接続拒否またはタイムアウト: ${e.message}")
            throw e
        } catch (e: SocketTimeoutException) {
            listener.onError("NTRIP: ソケットタイムアウト: ${e.message}")
            throw e
        } catch (e: IOException) {
            listener.onError("NTRIP: IOエラー: ${e.message}")
            throw e
        } catch (e: Exception) {
            listener.onError("NTRIP: 予期せぬエラー: ${e.message}")
            throw e
        } finally {
            if (!isConnected) { // 接続が確立できなかった、またはループを抜けた場合にのみdisconnectを呼ぶ
                disconnect()
            }
        }
    }

    fun disconnect() {
        if (!isConnected && socket == null) {
            listener.onStatusChanged("NTRIP: 既に切断済みです。")
            return
        }

        var disconnectError: String? = null

        try {
            reader?.close()
        } catch (e: IOException) {
            disconnectError = "Reader close error: ${e.message}"
        } finally {
            reader = null
        }
        try {
            writer?.close()
        } catch (e: IOException) {
            disconnectError = "Writer close error: ${e.message}"
        } finally {
            writer = null
        }
        try {
            rtcmInputStream?.close()
        } catch (e: IOException) {
            disconnectError = "RTCM InputStream close error: ${e.message}"
        } finally {
            rtcmInputStream = null
        }
        try {
            socket?.close()
        } catch (e: IOException) {
            disconnectError = "Socket close error: ${e.message}"
        } finally {
            socket = null
        }

        isConnected = false
        if (disconnectError != null) {
            listener.onError("NTRIP: 切断中にエラーが発生しました: $disconnectError")
        }
        listener.onStatusChanged("NTRIP: 切断しました。")
    }

    private fun buildNtripRequest(): String {
        val authString = if (username.isNotEmpty() && password.isNotEmpty()) {
            val credentials = "$username:$password"
            Base64.getEncoder().encodeToString(credentials.toByteArray())
        } else {
            ""
        }

        val headers = mutableListOf(
            "GET /$mountPoint HTTP/1.0",
            "User-Agent: NTRIP Client (Android RTK Droid)",
            "Accept: */*", // 一般的に必要とされるヘッダー
            "Connection: close",
            "Ntrip-Version: Ntrip/2.0"
        )

        if (authString.isNotEmpty()) {
            headers.add("Authorization: Basic $authString")
        }

        return headers.joinToString("\r\n", postfix = "\r\n\r\n")
    }
}