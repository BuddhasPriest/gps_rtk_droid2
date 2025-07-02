package com.example.gps_rtk_droid2

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * ServiceとActivity間でデータを共有するためのシングルトンオブジェクト。
 * StateFlowを使ってデータの変更を通知する。
 */
object SharedData {
    // ログメッセージ用のStateFlow
    val logMessage = MutableStateFlow<String?>(null)

    // GPSデータ用のStateFlow
    val gpsData = MutableStateFlow<GpsData?>(null)

    // 接続状態用のStateFlow
    val connectionStatus = MutableStateFlow(false)
}