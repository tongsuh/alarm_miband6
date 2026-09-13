package com.flashalarm.miband.domain.model

enum class BleConnectionState(val displayName: String) {
    DISCONNECTED("手环未连接"),
    SCANNING("正在扫描手环..."),
    CONNECTING("正在建立连接..."),
    AUTHENTICATING("正在进行Huami认证..."),
    CONNECTED("已连接小米手环 6"),
    DISCONNECTING("正在断开..."),
    ERROR("连接断开/认证失败")
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
