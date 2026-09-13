package com.flashalarm.miband.domain.model

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class BleDeviceMetrics(
    val heartRateBpm: Int = 0,
    val actigraphyG: Float = 0.0f,
    val batteryPercent: Int = -1,
    val rssi: Int = 0,
    val isHrStreaming: Boolean = false,
    val isMotionStreaming: Boolean = false
)

data class BleDeviceInfo(
    val name: String = "Mi Smart Band 6",
    val macAddress: String = "",
    val authKeyHex: String = ""
)
