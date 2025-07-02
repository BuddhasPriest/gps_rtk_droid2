package com.example.gps_rtk_droid2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.* // コルーチンを使用する場合

class GpsRtkService : Service() {

    private val TAG = "MyForegroundService"
    private val CHANNEL_ID = "ForegroundServiceChannel"
    private val NOTIFICATION_ID = 1

    // サービス内で非同期処理を行うためのコルーチンスコープ
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")

        val input = intent?.getStringExtra("inputExtra")
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE // Android 12 (API 31) 以降で必須
        )

        // フォアグラウンドサービスは通知が必須
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS-RTK サービス実行中")
            .setContentText(input ?: "GPSデータ処理中...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details) // 小さなアイコンを設定
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // ここでバックグラウンドで実行したいタスクを開始します
        // 例: コルーチンで定期的にGPSデータを処理する
        serviceScope.launch {
            try {
                while (isActive) { // コルーチンがアクティブな間はループを続ける
                    Log.d(TAG, "GPSデータ処理中...")
                    // ここに実際のGPSデータ処理ロジックを追加
                    delay(5000) // 5秒ごとに実行
                }
            } catch (e: Exception) {
                Log.e(TAG, "Service task error: ${e.message}")
            }
        }

        // START_NOT_STICKY: システムがサービスを終了した場合、リソースが十分であれば再作成しません。
        // START_STICKY: システムがサービスを終了した場合、リソースが十分であればサービスを再作成します（最後に渡されたインテントはnullになります）。
        // START_REDELIVER_INTENT: システムがサービスを終了した場合、リソースが十分であればサービスを再作成し、最後に渡されたインテントを再配信します。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 必要に応じてバインダーを返す (例: Activity との通信用)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel() // サービスが破棄されるときにコルーチンをキャンセルする
        Log.d(TAG, "Service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "フォアグラウンドサービスチャンネル",
                NotificationManager.IMPORTANCE_DEFAULT // デフォルトの重要度
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}