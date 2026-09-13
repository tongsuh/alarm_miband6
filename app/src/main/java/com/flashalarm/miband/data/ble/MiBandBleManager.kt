package com.flashalarm.miband.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.Log
import com.flashalarm.miband.domain.model.BleConnectionState
import com.flashalarm.miband.domain.model.BleDeviceInfo
import com.flashalarm.miband.domain.model.BleDeviceMetrics
import com.flashalarm.miband.domain.model.VibrationCadenceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.sqrt

@SuppressLint("MissingPermission")
class MiBandBleManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MiBandBleManager"
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        manager?.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private val authHandler = HuamiAuthHandler()

    // State flows
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _deviceMetrics = MutableStateFlow(BleDeviceMetrics())
    val deviceMetrics: StateFlow<BleDeviceMetrics> = _deviceMetrics.asStateFlow()

    private val _deviceInfo = MutableStateFlow(BleDeviceInfo())
    val deviceInfo: StateFlow<BleDeviceInfo> = _deviceInfo.asStateFlow()

    // Event streams
    private val _heartRateFlow = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val heartRateFlow: SharedFlow<Int> = _heartRateFlow.asSharedFlow()

    private val _actigraphyFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    val actigraphyFlow: SharedFlow<Float> = _actigraphyFlow.asSharedFlow()

    private var hrKeepAliveJob: Job? = null
    private var vibrationJob: Job? = null
    private var isVibrating = false

    fun setTargetDevice(name: String, mac: String, authKeyHex: String) {
        _deviceInfo.value = BleDeviceInfo(
            name = name.ifBlank { "Mi Smart Band 6" },
            macAddress = mac.uppercase().trim(),
            authKeyHex = authKeyHex.trim()
        )
        authHandler.setAuthKeyHex(authKeyHex)
    }

    fun startScanAndConnect(targetMac: String = _deviceInfo.value.macAddress) {
        val adapter = bluetoothAdapter ?: run {
            _connectionState.value = BleConnectionState.ERROR
            return
        }

        if (!adapter.isEnabled) {
            _connectionState.value = BleConnectionState.DISCONNECTED
            return
        }

        if (targetMac.isNotBlank()) {
            try {
                val device = adapter.getRemoteDevice(targetMac)
                connectToDevice(device)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Invalid MAC: $targetMac, falling back to LE scan", e)
            }
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            _connectionState.value = BleConnectionState.ERROR
            return
        }

        _connectionState.value = BleConnectionState.SCANNING
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val dev = result?.device ?: return
                val devName = dev.name ?: ""
                val devAddress = dev.address ?: ""

                val match = if (targetMac.isNotBlank()) {
                    devAddress.equals(targetMac, ignoreCase = true)
                } else {
                    devName.contains("Mi Smart Band 6", ignoreCase = true) ||
                            devName.contains("Mi Band 6", ignoreCase = true)
                }

                if (match) {
                    try {
                        scanner.stopScan(this)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping scan", e)
                    }
                    _deviceInfo.value = _deviceInfo.value.copy(name = devName.ifBlank { "Mi Smart Band 6" }, macAddress = devAddress)
                    connectToDevice(dev)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code: $errorCode")
                _connectionState.value = BleConnectionState.ERROR
            }
        }

        scanner.startScan(scanCallback)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        disconnect()
        _connectionState.value = BleConnectionState.CONNECTING
        Log.i(TAG, "Connecting to GATT device: ${device.address}")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        hrKeepAliveJob?.cancel()
        hrKeepAliveJob = null
        vibrationJob?.cancel()
        vibrationJob = null

        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing GATT", e)
            }
        }
        bluetoothGatt = null
        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status, newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.ERROR
                disconnect()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState.AUTHENTICATING
                    gatt?.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    disconnect()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu, discovering services...")
            gatt?.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            Log.i(TAG, "Services discovered. Starting Huami Auth Handshake...")
            startAuthHandshake(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            val charUuid = descriptor?.characteristic?.uuid
            Log.d(TAG, "Descriptor written for $charUuid, status=$status")

            if (charUuid == BleConstants.UUID_CHAR_AUTH) {
                // Send first handshake packet: request random challenge
                val requestPacket = authHandler.startHandshake()
                writeCharacteristic(BleConstants.UUID_SERVICE_AUTH, BleConstants.UUID_CHAR_AUTH, requestPacket)
            } else if (charUuid == BleConstants.UUID_CHAR_HEART_RATE_MEASUREMENT) {
                // Start continuous HR
                writeCharacteristic(BleConstants.UUID_SERVICE_HEART_RATE, BleConstants.UUID_CHAR_HEART_RATE_CONTROL, BleConstants.HR_START_CONTINUOUS)
                startHrKeepAlive()
                // Now enable sensor actigraphy
                gatt?.let { enableSensorNotifications(it) }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleCharacteristicData(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            characteristic?.let {
                @Suppress("DEPRECATION")
                handleCharacteristicData(it.uuid, it.value ?: ByteArray(0))
            }
        }
    }

    private fun handleCharacteristicData(uuid: UUID, value: ByteArray) {
        when (uuid) {
            BleConstants.UUID_CHAR_AUTH -> {
                when (val result = authHandler.handleAuthNotification(value)) {
                    is AuthResult.SendPacket -> {
                        Log.d(TAG, "Sending encrypted auth challenge response")
                        writeCharacteristic(BleConstants.UUID_SERVICE_AUTH, BleConstants.UUID_CHAR_AUTH, result.data)
                    }
                    is AuthResult.Success -> {
                        Log.i(TAG, "Huami authentication SUCCESSFUL! Mi Band 6 is ready.")
                        _connectionState.value = BleConnectionState.CONNECTED
                        // Start sensor data subscriptions
                        bluetoothGatt?.let { subscribeHeartRate(it) }
                    }
                    is AuthResult.Failed -> {
                        Log.e(TAG, "Huami authentication failed: ${result.error}")
                        _connectionState.value = BleConnectionState.ERROR
                    }
                }
            }

            BleConstants.UUID_CHAR_HEART_RATE_MEASUREMENT -> {
                if (value.isNotEmpty()) {
                    // BLE Heart Rate Measurement Format:
                    // Flags (byte 0): bit 0 -> 0 = UINT8, 1 = UINT16
                    val is16Bit = (value[0].toInt() and 0x01) != 0
                    val hr = if (is16Bit && value.size >= 3) {
                        (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
                    } else if (value.size >= 2) {
                        value[1].toInt() and 0xFF
                    } else {
                        0
                    }

                    if (hr > 0) {
                        _deviceMetrics.value = _deviceMetrics.value.copy(
                            heartRateBpm = hr,
                            isHrStreaming = true
                        )
                        _heartRateFlow.tryEmit(hr)
                    }
                }
            }

            BleConstants.UUID_CHAR_SENSOR_DATA -> {
                parseActigraphyData(value)
            }
        }
    }

    private fun startAuthHandshake(gatt: BluetoothGatt) {
        val authService = gatt.getService(BleConstants.UUID_SERVICE_AUTH)
        val authChar = authService?.getCharacteristic(BleConstants.UUID_CHAR_AUTH)
        if (authChar != null) {
            enableNotification(gatt, authChar)
        } else {
            Log.e(TAG, "Auth characteristic not found on device!")
            _connectionState.value = BleConnectionState.ERROR
        }
    }

    private fun subscribeHeartRate(gatt: BluetoothGatt) {
        val hrService = gatt.getService(BleConstants.UUID_SERVICE_HEART_RATE)
        val hrChar = hrService?.getCharacteristic(BleConstants.UUID_CHAR_HEART_RATE_MEASUREMENT)
        if (hrChar != null) {
            enableNotification(gatt, hrChar)
        } else {
            Log.w(TAG, "Standard Heart Rate service not found, attempting sensor stream")
            enableSensorNotifications(gatt)
        }
    }

    private fun enableSensorNotifications(gatt: BluetoothGatt) {
        val huamiService = gatt.getService(BleConstants.UUID_SERVICE_HUAMI) ?: return
        val sensorDataChar = huamiService.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_DATA)
        if (sensorDataChar != null) {
            enableNotification(gatt, sensorDataChar)
            // Send start streaming to control char
            writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_SENSOR_CTRL, BleConstants.SENSOR_START_CMD)
            _deviceMetrics.value = _deviceMetrics.value.copy(isMotionStreaming = true)
        }
    }

    private fun parseActigraphyData(data: ByteArray) {
        if (data.size < 4) return
        // Parse 3-axis accelerometer packets (e.g. 16-bit signed delta samples)
        // Magnitude VM = sqrt(x^2 + y^2 + z^2)
        try {
            var offset = 0
            var sumMovement = 0.0f
            var sampleCount = 0

            while (offset + 6 <= data.size) {
                val x = (data[offset].toInt() and 0xFF) or (data[offset + 1].toInt() shl 8)
                val y = (data[offset + 2].toInt() and 0xFF) or (data[offset + 3].toInt() shl 8)
                val z = (data[offset + 4].toInt() and 0xFF) or (data[offset + 5].toInt() shl 8)

                // Normalize: 1g is approximately 4096 LSB or 1000 LSB depending on scale
                val normX = x / 4096.0f
                val normY = y / 4096.0f
                val normZ = z / 4096.0f

                val vm = sqrt(normX * normX + normY * normY + normZ * normZ)
                val delta = kotlin.math.abs(vm - 1.0f) // ENMO / dynamic movement count
                sumMovement += delta
                sampleCount++
                offset += 6
            }

            val avgMagnitude = if (sampleCount > 0) sumMovement / sampleCount else 0.0f
            _deviceMetrics.value = _deviceMetrics.value.copy(actigraphyG = avgMagnitude)
            _actigraphyFlow.tryEmit(avgMagnitude)
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing actigraphy sensor bytes", e)
        }
    }

    private fun startHrKeepAlive() {
        hrKeepAliveJob?.cancel()
        hrKeepAliveJob = scope.launch(Dispatchers.IO) {
            while (isActive && _connectionState.value == BleConnectionState.CONNECTED) {
                delay(12000L) // 12-second ping keepalive
                writeCharacteristic(BleConstants.UUID_SERVICE_HEART_RATE, BleConstants.UUID_CHAR_HEART_RATE_CONTROL, BleConstants.HR_PING_KEEPALIVE)
            }
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(BleConstants.UUID_DESCRIPTOR_CCCD)
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, data: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(serviceUuid) ?: return
        val char = service.getCharacteristic(charUuid) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    /**
     * Executes wrist lucid dream cueing vibration using Immediate Alert Service (0x1802).
     */
    fun triggerCadenceVibration(cadenceType: VibrationCadenceType) {
        if (isVibrating) return
        val pattern = VibrationCadenceProfiles.getPattern(cadenceType)

        vibrationJob?.cancel()
        vibrationJob = scope.launch(Dispatchers.IO) {
            isVibrating = true
            try {
                for (i in pattern.sequenceMs.indices) {
                    if (!isActive) break
                    val duration = pattern.sequenceMs[i]
                    val isVibrateStep = (i % 2 == 0)

                    if (isVibrateStep) {
                        // Alert level: 0x01 (Mild Alert)
                        writeAlertLevel(0x01)
                    } else {
                        // Alert level: 0x00 (No Alert)
                        writeAlertLevel(0x00)
                    }
                    delay(duration)
                }
            } finally {
                writeAlertLevel(0x00)
                isVibrating = false
            }
        }
    }

    private fun writeAlertLevel(level: Int) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(BleConstants.UUID_SERVICE_IMMEDIATE_ALERT) ?: return
        val char = service.getCharacteristic(BleConstants.UUID_CHAR_ALERT_LEVEL) ?: return

        val data = byteArrayOf(level.toByte())
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }
}
