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
    private var isConnected = false

    // 接続タイムアウト時間を設定 (ミリ秒)
    private val CONNECT_TIMEOUT_MS = 10000 // 例: 10秒
    // 読み込みタイムアウト時間を設定 (ミリ秒)
    private val READ_TIMEOUT_MS = 30000 // 例: 30秒 (RTCMデータがない場合に無限に待たないように)

    interface NtripClientListener {
        fun onRtcmDataReceived(data: ByteArray)
        fun onStatusChanged(message: String)
        fun onError(error: String)
    }

    fun connect() {
        if (isConnected) {
            listener.onStatusChanged("NTRIP: 既に接続済みです。")
            return
        }

        try {
            listener.onStatusChanged("NTRIP: 接続中 $server:$port/$mountPoint...")

            // ソケットを初期化 (SSL/TLS対応)
            socket = if (port == 443) {
                // HTTPS (SSL/TLS) の場合
                SSLSocketFactory.getDefault().createSocket()
            } else {
                // HTTP の場合
                Socket()
            }

            // 接続タイムアウトを設定して接続
            socket?.connect(InetSocketAddress(server, port), CONNECT_TIMEOUT_MS)
            // データ読み込みタイムアウトを設定
            socket?.soTimeout = READ_TIMEOUT_MS

            // ヘッダー送受信用のReader/Writer (ISO_8859_1はNTRIPの標準エンコーディング)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.ISO_8859_1))
            writer = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream(), Charsets.ISO_8859_1))

            // RTCMデータ受信用のInputStream (バイナリデータ)
            rtcmInputStream = socket!!.getInputStream()

            // NTRIPリクエストの送信
            val request = buildNtripRequest()
            writer?.write(request)
            writer?.flush()
            listener.onStatusChanged("NTRIP: リクエストを送信しました。")

            // 応答ヘッダーの読み込みと確認
            var line: String?
            val headers = mutableListOf<String>()
            var responseStatusOk = false
            while (reader?.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break // 空行でヘッダー終了
                headers.add(line!!)
                listener.onStatusChanged("NTRIP RCV Header: $line") // ヘッダーをログに出す

                if (line!!.contains("HTTP/1.0 200 OK", ignoreCase = true) || line!!.contains("ICY 200 OK", ignoreCase = true)) {
                    responseStatusOk = true
                } else if (line!!.contains("HTTP/1.0 401 Unauthorized", ignoreCase = true)) {
                    listener.onError("NTRIP: 認証失敗 (401 Unauthorized)。ユーザー名/パスワードを確認してください。")
                    disconnect()
                    return
                } else if (line!!.contains("HTTP/1.0 404 Not Found", ignoreCase = true)) {
                    listener.onError("NTRIP: マウントポイントが見つかりません (404 Not Found)。マウントポイント名を確認してください。")
                    disconnect()
                    return
                }
            }

            if (!responseStatusOk) {
                listener.onError("NTRIP: サーバーから200 OK応答がありませんでした。受信ヘッダー:\n${headers.joinToString("\n")}")
                disconnect()
                return
            }

            isConnected = true
            listener.onStatusChanged("NTRIP: 接続成功。RTCMデータを受信中...")

            // RTCMデータ受信ループ
            val buffer = ByteArray(4096) // 適切なバッファサイズ
            var bytesRead: Int = -1 // 初期化はwhileループ内で確実に行われる
            while (isConnected && rtcmInputStream?.read(buffer).also { bytesRead = it ?: -1 } != -1) {
                if (bytesRead > 0) {
                    val data = buffer.copyOf(bytesRead)
                    listener.onRtcmDataReceived(data)
                }
            }

        } catch (e: SocketTimeoutException) {
            listener.onError("NTRIP: 接続またはデータ受信がタイムアウトしました: ${e.message}")
        } catch (e: ConnectException) {
            listener.onError("NTRIP: 接続が拒否されました (サーバーが起動しない、ポートが誤っているなど): ${e.message}")
        } catch (e: UnknownHostException) {
            listener.onError("NTRIP: ホスト名が見つかりません (サーバーアドレスを確認してください): ${e.message}")
        } catch (e: IOException) {
            if (isConnected) { // 接続中に発生したI/O例外は接続切断とみなす
                listener.onError("NTRIP: 接続が失われました: ${e.message}")
            } else { // 接続確立前に発生したI/O例外は接続失敗とみなす
                listener.onError("NTRIP: 接続に失敗しました: ${e.message}")
            }
        } catch (e: Exception) {
            listener.onError("NTRIP: 予期せぬエラーが発生しました: ${e.message}")
        } finally {
            // 接続が確立できなかった場合、またはエラーで切断が必要な場合
            if (!isConnected) {
                disconnect() // 必ずリソースを解放
            }
            // isConnectedがtrueであれば、disconnectは明示的に呼ばれるまで待つ
        }
    }

    fun disconnect() {
        if (!isConnected && socket == null) {
            // すでに切断済みか、初期状態であれば何もしない
            return
        }

        listener.onStatusChanged("NTRIP: 切断中...")
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

        return headers.joinToString("\r\n") + "\r\n\r\n"
    }

    fun isConnected(): Boolean = isConnected
}