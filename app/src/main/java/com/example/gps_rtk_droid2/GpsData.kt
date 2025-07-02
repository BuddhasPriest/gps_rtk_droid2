package com.example.gps_rtk_droid2

// GpsDataデータクラスを共通ファイルに定義
data class GpsData(
    val timestamp: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val heading: Double? = null,
    val rtkStatus: String? = null, // RTKステータスを追加
    val rawNmea: String // 元のNMEAメッセージも保持
)
