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
    val UUID_CHAR_REALTIME_STEPS: UUID = UUID.fromString("00000007-0000-3512-2118-0009af100700")

    // Huami 2021 Chunked Protocol Characteristics (Used by Mi Band 6 firmware v1.0.6.xx+ / New Auth Protocol)
    val UUID_CHAR_CHUNKED_2021_WRITE: UUID = UUID.fromString("00000016-0000-3512-2118-0009af100700")
    val UUID_CHAR_CHUNKED_2021_READ: UUID = UUID.fromString("00000017-0000-3512-2118-0009af100700")
    const val CHUNKED2021_ENDPOINT_AUTH: Short = 0x0082.toShort()
    const val CHUNKED2021_ENDPOINT_HEARTRATE: Short = 0x001D
    const val CHUNKED2021_ENDPOINT_FIND_DEVICE: Short = 0x001A
    const val CHUNKED2021_ENDPOINT_STEPS: Short = 0x0016
    const val CHUNKED2021_ENDPOINT_COMPAT: Short = 0x0090.toShort()
    const val STEPS_CMD_ENABLE_REALTIME: Byte = 0x05

    // Standard Heart Rate Service (0x180D)
    val UUID_SERVICE_HEART_RATE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_HEART_RATE_CONTROL: UUID = UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb")

    // Immediate Alert Service (0x1802) for Motor Vibration
    val UUID_SERVICE_IMMEDIATE_ALERT: UUID = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_ALERT_LEVEL: UUID = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")

    // Auth Protocol Commands & Opcodes
    const val AUTH_BYTE_MODE_STANDARD: Byte = 0x08                 // Gadgetbridge / Notify / Mi Band standard AES-128 flag
    const val AUTH_BYTE_MODE_ALT: Byte = 0x00                      // Alternative mode flag for certain firmware variants
    const val AUTH_CRYPT_FLAG: Byte = 0x80.toByte()                // Huami Gen 4/5/6 crypt flag (Gadgetbridge MiBand4Support/InitOperation)

    // Modern Mi Band 4/5/6 Challenge Request: [0x82, 0x08, 0x02, 0x01, 0x00] (5 bytes)
    val AUTH_CMD_REQUEST_RANDOM_MODERN = byteArrayOf(
        (0x02 or 0x80).toByte(), // 0x82
        AUTH_BYTE_MODE_STANDARD, // 0x08
        0x02,
        0x01,
        0x00
    )

    // Legacy Mi Band 2/3 Challenge Request: [0x02, 0x08]
    val AUTH_CMD_REQUEST_RANDOM = byteArrayOf(0x02, AUTH_BYTE_MODE_STANDARD)
    val AUTH_CMD_REQUEST_RANDOM_ALT = byteArrayOf(0x02, AUTH_BYTE_MODE_ALT)
    const val AUTH_BYTE_RESPONSE_PREFIX: Byte = 0x10
    const val AUTH_BYTE_PAIR_OP: Byte = 0x01
    const val AUTH_BYTE_RANDOM_KEY_OP: Byte = 0x02
    const val AUTH_BYTE_ENCRYPTED_KEY_OP: Byte = 0x03

    // Opcodes with 0x80 crypt flag (used for Mi Band 4, 5, 6)
    val AUTH_BYTE_RANDOM_KEY_OP_CRYPT: Byte = (0x02 or 0x80).toByte()     // 0x82
    val AUTH_BYTE_ENCRYPTED_KEY_OP_CRYPT: Byte = (0x03 or 0x80).toByte() // 0x83

    const val AUTH_BYTE_SUCCESS: Byte = 0x01
    const val AUTH_BYTE_FAIL_NOT_PAIRED: Byte = 0x04
    const val AUTH_BYTE_FAIL_INVALID_KEY: Byte = 0x06
    const val AUTH_BYTE_FAIL_INVALID_FLAG: Byte = 0x07

    // Heart Rate Commands
    val HR_START_CONTINUOUS = byteArrayOf(0x15, 0x01, 0x01)
    val HR_START_CONTINUOUS_ALT = byteArrayOf(0x15, 0x02, 0x01)
    val HR_STOP_CONTINUOUS = byteArrayOf(0x15, 0x01, 0x00)
    val HR_PING_KEEPALIVE = byteArrayOf(0x16)

    // Immediate Alert Levels
    const val ALERT_LEVEL_NONE: Byte = 0x00
    const val ALERT_LEVEL_MESSAGE: Byte = 0x01
    const val ALERT_LEVEL_PHONE_CALL: Byte = 0x02
    const val ALERT_LEVEL_VIBRATE_ONLY: Byte = 0x03

    // Sensor Commands (Actigraphy streaming)
    val CMD_RAW_SENSOR_START_ACCEL_25HZ = byteArrayOf(0x01, 0x01, 0x19) // Dedicated Accel 25Hz
    val CMD_RAW_SENSOR_START_ACCEL_ALT = byteArrayOf(0x01, 0x01)       // Accel 2-byte command
    val CMD_RAW_SENSOR_START_1 = byteArrayOf(0x01, 0x03, 0x19)         // Combined 25Hz (Accel+PPG)
    val CMD_RAW_SENSOR_START_3 = byteArrayOf(0x02)                     // Trigger / Flush FIFO stream
    val CMD_RAW_SENSOR_STOP = byteArrayOf(0x03)                        // Stop / Reset stream
    val SENSOR_START_CMD = byteArrayOf(0x01, 0x01)
    val SENSOR_STOP_CMD = byteArrayOf(0x00)
}
