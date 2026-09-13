package com.flashalarm.miband.data.ble

import java.util.UUID

object BleConstants {
    // Standard CCCD for enabling notifications/indications
    val UUID_DESCRIPTOR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Huami Auth Service & Characteristic (0xFEE1)
    val UUID_SERVICE_AUTH: UUID = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_AUTH: UUID = UUID.fromString("00000009-0000-3512-2118-0009af100700")

    // Huami Sensor Service & Characteristics (0xFEE0)
    val UUID_SERVICE_HUAMI: UUID = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_SENSOR_DATA: UUID = UUID.fromString("00000002-0000-3512-2118-0009af100700")
    val UUID_CHAR_SENSOR_CTRL: UUID = UUID.fromString("00000001-0000-3512-2118-0009af100700")

    // Standard Heart Rate Service (0x180D)
    val UUID_SERVICE_HEART_RATE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_HEART_RATE_CONTROL: UUID = UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb")

    // Immediate Alert Service (0x1802) for Motor Vibration
    val UUID_SERVICE_IMMEDIATE_ALERT: UUID = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_ALERT_LEVEL: UUID = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")

    // Auth Protocol Commands
    val AUTH_CMD_REQUEST_RANDOM = byteArrayOf(0x02, 0x08)
    val AUTH_CMD_REQUEST_RANDOM_ALT = byteArrayOf(0x02, 0x00)
    val AUTH_BYTE_RESPONSE_PREFIX: Byte = 0x10
    val AUTH_BYTE_RANDOM_KEY_OP: Byte = 0x02
    val AUTH_BYTE_ENCRYPTED_KEY_OP: Byte = 0x03
    val AUTH_BYTE_SUCCESS: Byte = 0x01

    // Heart Rate Commands
    val HR_START_CONTINUOUS = byteArrayOf(0x15, 0x01, 0x01)
    val HR_START_CONTINUOUS_ALT = byteArrayOf(0x15, 0x02, 0x01)
    val HR_PING_KEEPALIVE = byteArrayOf(0x16)

    // Sensor Commands (Actigraphy streaming)
    val SENSOR_START_CMD = byteArrayOf(0x01, 0x01)
    val SENSOR_STOP_CMD = byteArrayOf(0x00)
}
