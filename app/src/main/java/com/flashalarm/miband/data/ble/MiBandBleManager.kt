package com.flashalarm.miband.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.Log
import com.flashalarm.miband.domain.model.BleConnectionState
import com.flashalarm.miband.domain.model.BleDeviceInfo
import com.flashalarm.miband.domain.model.BleDeviceMetrics
import com.flashalarm.miband.domain.model.CustomizableVibrationPattern
import com.flashalarm.miband.domain.model.PatternType
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

data class DiscoveredBleDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

@SuppressLint("MissingPermission")
class MiBandBleManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MiBandBleManager"
        private const val AUTH_TIMEOUT_MS = 15000L
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        manager?.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private val authHandler = HuamiAuthHandler()

    // Connection state flows
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _authStatusDetail = MutableStateFlow("手环未连接")
    val authStatusDetail: StateFlow<String> = _authStatusDetail.asStateFlow()

    private val _deviceMetrics = MutableStateFlow(BleDeviceMetrics())
    val deviceMetrics: StateFlow<BleDeviceMetrics> = _deviceMetrics.asStateFlow()

    private val _deviceInfo = MutableStateFlow(BleDeviceInfo())
    val deviceInfo: StateFlow<BleDeviceInfo> = _deviceInfo.asStateFlow()

    // Scanning state flows
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredBleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredBleDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var activeScanCallback: ScanCallback? = null
    private var scanTimeoutJob: Job? = null
    private var authTimeoutJob: Job? = null

    // Event streams
    private val _heartRateFlow = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val heartRateFlow: SharedFlow<Int> = _heartRateFlow.asSharedFlow()

    private val _actigraphyFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    val actigraphyFlow: SharedFlow<Float> = _actigraphyFlow.asSharedFlow()

    private var hrKeepAliveJob: Job? = null
    private var vibrationJob: Job? = null
    private var isVibrating = false

    fun setTargetDevice(name: String, mac: String, authKeyHex: String) {
        val cleanKey = authKeyHex.trim()
            .replace(":", "")
            .replace(" ", "")
            .replace("-", "")
            .let { if (it.startsWith("0x", ignoreCase = true)) it.substring(2) else it }

        _deviceInfo.value = BleDeviceInfo(
            name = name.ifBlank { "Mi Smart Band 6" },
            macAddress = mac.uppercase().trim(),
            authKeyHex = cleanKey
        )
        authHandler.setAuthKeyHex(cleanKey)
    }

    /**
     * Starts BLE scanning to discover nearby Mi Band or BLE devices.
     */
    fun startBleScan() {
        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "BluetoothAdapter is null, cannot scan")
            return
        }

        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "BluetoothLeScanner is null")
            return
        }

        stopBleScan()
        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        val deviceMap = mutableMapOf<String, DiscoveredBleDevice>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val dev = result?.device ?: return
                val address = dev.address ?: return
                val name = (dev.name ?: result.scanRecord?.deviceName ?: "").trim()
                val rssi = result.rssi

                val displayName = if (name.isNotBlank()) name else "未知蓝牙设备 ($address)"
                deviceMap[address] = DiscoveredBleDevice(displayName, address, rssi)

                _discoveredDevices.value = deviceMap.values.sortedWith(
                    compareByDescending<DiscoveredBleDevice> {
                        it.name.contains("Band", ignoreCase = true) || it.name.contains("Mi", ignoreCase = true)
                    }.thenByDescending { it.rssi }
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE Scan failed: $errorCode")
                _isScanning.value = false
            }
        }

        activeScanCallback = callback
        try {
            scanner.startScan(callback)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan", e)
            _isScanning.value = false
        }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch(Dispatchers.Main) {
            delay(15000L)
            stopBleScan()
        }
    }

    fun stopBleScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        activeScanCallback?.let { callback ->
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping scan", e)
            }
        }
        activeScanCallback = null
        _isScanning.value = false
    }

    fun startScanAndConnect(targetMac: String = _deviceInfo.value.macAddress) {
        val adapter = bluetoothAdapter ?: run {
            _connectionState.value = BleConnectionState.ERROR
            _authStatusDetail.value = "蓝牙适配器不可用"
            return
        }

        if (!adapter.isEnabled) {
            _connectionState.value = BleConnectionState.DISCONNECTED
            _authStatusDetail.value = "蓝牙处于关闭状态，请先开启手机蓝牙"
            return
        }

        stopBleScan()

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
        _authStatusDetail.value = "正在扫描匹配手环..."

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
                    _deviceInfo.value = _deviceInfo.value.copy(
                        name = devName.ifBlank { "Mi Smart Band 6" },
                        macAddress = devAddress
                    )
                    connectToDevice(dev)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code: $errorCode")
                _connectionState.value = BleConnectionState.ERROR
                _authStatusDetail.value = "扫描失败 (错误码: $errorCode)"
            }
        }

        scanner.startScan(scanCallback)
    }

    fun connectToDevice(device: BluetoothDevice) {
        disconnect()
        _connectionState.value = BleConnectionState.CONNECTING
        _authStatusDetail.value = "正在建立低功耗蓝牙物理链路..."
        Log.i(TAG, "Connecting to GATT device: ${device.address}")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        authTimeoutJob?.cancel()
        authTimeoutJob = null
        hrKeepAliveJob?.cancel()
        hrKeepAliveJob = null
        stopVibration()

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
        _authStatusDetail.value = "手环未连接"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status, newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Connection failed with status: $status")
                _connectionState.value = BleConnectionState.ERROR
                _authStatusDetail.value = "GATT连接异常 (状态码: $status)，请重试"
                disconnect()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState.AUTHENTICATING
                    _authStatusDetail.value = "蓝牙链路已连通，准备发现服务..."
                    Log.i(TAG, "Connected to GATT server. Scheduling service discovery...")

                    // 15-second safety timeout for authentication
                    authTimeoutJob?.cancel()
                    authTimeoutJob = scope.launch(Dispatchers.Main) {
                        delay(AUTH_TIMEOUT_MS)
                        if (_connectionState.value == BleConnectionState.AUTHENTICATING) {
                            Log.e(TAG, "Huami authentication timed out after ${AUTH_TIMEOUT_MS}ms")
                            _authStatusDetail.value = "认证超时：手环未响应握手。请点亮手环屏幕，或重新核对AuthKey"
                            _connectionState.value = BleConnectionState.ERROR
                        }
                    }

                    // Delay 300ms before discoverServices() as recommended by Android BLE best practices
                    scope.launch(Dispatchers.Main) {
                        delay(300L)
                        _authStatusDetail.value = "正在检索手环GATT服务列表..."
                        val discoverSuccess = gatt?.discoverServices() ?: false
                        if (!discoverSuccess) {
                            Log.e(TAG, "discoverServices returned false")
                            _authStatusDetail.value = "启动服务发现失败"
                            _connectionState.value = BleConnectionState.ERROR
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    _authStatusDetail.value = "手环蓝牙已断开"
                    disconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                Log.e(TAG, "onServicesDiscovered failed with status: $status")
                _connectionState.value = BleConnectionState.ERROR
                _authStatusDetail.value = "服务发现失败 (状态码: $status)"
                return
            }

            Log.i(TAG, "Services discovered. Searching for Huami Auth characteristic (0x0009)...")
            _authStatusDetail.value = "已发现服务，正在检索华米认证特征..."

            // Search in standard Huami Auth service (0xFEE1) or any discovered service
            var authChar: BluetoothGattCharacteristic? = null
            val authService = gatt.getService(BleConstants.UUID_SERVICE_AUTH)
            if (authService != null) {
                authChar = authService.getCharacteristic(BleConstants.UUID_CHAR_AUTH)
            }
            if (authChar == null) {
                for (s in gatt.services) {
                    val c = s.getCharacteristic(BleConstants.UUID_CHAR_AUTH)
                    if (c != null) {
                        authChar = c
                        break
                    }
                }
            }

            if (authChar != null) {
                Log.i(TAG, "Huami Auth characteristic found! Enabling notification/indication...")
                _authStatusDetail.value = "已找到认证特征，正在配置安全通道..."
                enableNotification(gatt, authChar)
            } else {
                Log.e(TAG, "Huami Auth characteristic (00000009) not found on device!")
                _connectionState.value = BleConnectionState.ERROR
                _authStatusDetail.value = "未找到华米认证特征通道 (0x0009)"
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            val charUuid = descriptor?.characteristic?.uuid
            Log.d(TAG, "Descriptor written for $charUuid, status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Failed writing descriptor for $charUuid, status=$status")
                _authStatusDetail.value = "配置安全通道描述符失败 (状态码: $status)"
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            if (charUuid == BleConstants.UUID_CHAR_AUTH) {
                // Dispatch with slight delay so the GATT stack transitions out of GATT_BUSY state!
                scope.launch(Dispatchers.IO) {
                    delay(150L)
                    val requestPacket = authHandler.startHandshake(legacy = false)
                    Log.i(TAG, "Writing request random challenge packet: ${requestPacket.joinToString(separator = " ") { "%02X".format(it) }}")
                    _authStatusDetail.value = "安全通道就绪，已发送0x02请求，等待手环Challenge..."
                    val writeSuccess = writeCharacteristic(BleConstants.UUID_SERVICE_AUTH, BleConstants.UUID_CHAR_AUTH, requestPacket)
                    if (!writeSuccess) {
                        Log.e(TAG, "Failed sending random challenge request packet")
                        _authStatusDetail.value = "发送0x02随机数请求失败，请重试"
                        _connectionState.value = BleConnectionState.ERROR
                    }
                }
            } else if (charUuid == BleConstants.UUID_CHAR_HEART_RATE_MEASUREMENT) {
                scope.launch(Dispatchers.IO) {
                    delay(150L)
                    writeCharacteristic(BleConstants.UUID_SERVICE_HEART_RATE, BleConstants.UUID_CHAR_HEART_RATE_CONTROL, BleConstants.HR_START_CONTINUOUS)
                    startHrKeepAlive()
                    delay(150L)
                    gatt?.let { enableSensorNotifications(it) }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            Log.d(TAG, "onCharacteristicWrite uuid=${characteristic?.uuid}, status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Characteristic write not SUCCESS: status=$status")
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
                val hexStr = value.joinToString(separator = " ") { "%02X".format(it) }
                Log.i(TAG, "Received AUTH characteristic update: [$hexStr]")

                when (val result = authHandler.handleAuthNotification(value)) {
                    is AuthResult.SendPacket -> {
                        val packetHex = result.data.joinToString(separator = " ") { "%02X".format(it) }
                        if (result.data.isNotEmpty() && result.data[0] == BleConstants.AUTH_BYTE_PAIR_OP) {
                            _authStatusDetail.value = "手环提示：请轻触手环屏幕确认配对..."
                        } else if (result.data.isNotEmpty() && result.data[0] == BleConstants.AUTH_BYTE_RANDOM_KEY_OP) {
                            _authStatusDetail.value = "正在请求手环挑战码 (模式: 0x%02X)...".format(result.data.getOrElse(1) { 0 })
                        } else {
                            _authStatusDetail.value = "已获取Challenge，正在进行AES运算并回传密文..."
                        }

                        scope.launch(Dispatchers.IO) {
                            delay(100L)
                            Log.d(TAG, "Sending auth packet to band: [$packetHex]")
                            val success = writeCharacteristic(BleConstants.UUID_SERVICE_AUTH, BleConstants.UUID_CHAR_AUTH, result.data)
                            if (!success) {
                                Log.e(TAG, "Failed writing auth packet [$packetHex]")
                                _authStatusDetail.value = "发送认证密文失败"
                                _connectionState.value = BleConnectionState.ERROR
                            }
                        }
                    }

                    is AuthResult.Success -> {
                        Log.i(TAG, "Huami authentication SUCCESSFUL! Mi Band 6 is authenticated and ready.")
                        authTimeoutJob?.cancel()
                        authTimeoutJob = null
                        _authStatusDetail.value = "华米握手认证成功！手环已就绪"
                        _connectionState.value = BleConnectionState.CONNECTED

                        // Request MTU now after auth to optimize sensor data throughput
                        bluetoothGatt?.requestMtu(512)

                        // Start heart rate & actigraphy sensor data subscriptions
                        scope.launch(Dispatchers.IO) {
                            delay(250L)
                            bluetoothGatt?.let { subscribeHeartRate(it) }
                        }
                    }

                    is AuthResult.Failed -> {
                        Log.e(TAG, "Huami authentication failed: ${result.error}")
                        authTimeoutJob?.cancel()
                        authTimeoutJob = null
                        _authStatusDetail.value = result.error
                        _connectionState.value = BleConnectionState.ERROR
                    }
                }
            }

            BleConstants.UUID_CHAR_HEART_RATE_MEASUREMENT -> {
                if (value.isNotEmpty()) {
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
            writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_SENSOR_CTRL, BleConstants.SENSOR_START_CMD)
            _deviceMetrics.value = _deviceMetrics.value.copy(isMotionStreaming = true)
        }
    }

    private fun parseActigraphyData(data: ByteArray) {
        if (data.size < 4) return
        try {
            var offset = 0
            var sumMovement = 0.0f
            var sampleCount = 0

            while (offset + 6 <= data.size) {
                val x = (data[offset].toInt() and 0xFF) or (data[offset + 1].toInt() shl 8)
                val y = (data[offset + 2].toInt() and 0xFF) or (data[offset + 3].toInt() shl 8)
                val z = (data[offset + 4].toInt() and 0xFF) or (data[offset + 5].toInt() shl 8)

                val normX = x / 4096.0f
                val normY = y / 4096.0f
                val normZ = z / 4096.0f

                val vm = sqrt(normX * normX + normY * normY + normZ * normZ)
                val delta = kotlin.math.abs(vm - 1.0f)
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
                delay(12000L)
                writeCharacteristic(BleConstants.UUID_SERVICE_HEART_RATE, BleConstants.UUID_CHAR_HEART_RATE_CONTROL, BleConstants.HR_PING_KEEPALIVE)
            }
        }
    }

    fun setHeartRateStreamingMode(isContinuous: Boolean) {
        if (isContinuous) {
            writeCharacteristic(BleConstants.UUID_SERVICE_HEART_RATE, BleConstants.UUID_CHAR_HEART_RATE_CONTROL, BleConstants.HR_START_CONTINUOUS)
            startHrKeepAlive()
        } else {
            hrKeepAliveJob?.cancel()
            hrKeepAliveJob = null
            writeCharacteristic(BleConstants.UUID_SERVICE_HEART_RATE, BleConstants.UUID_CHAR_HEART_RATE_CONTROL, BleConstants.HR_PING_KEEPALIVE)
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(BleConstants.UUID_DESCRIPTOR_CCCD)
        if (descriptor != null) {
            val cccdValue = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, cccdValue)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = cccdValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, data: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: run {
            Log.w(TAG, "writeCharacteristic failed: bluetoothGatt is null")
            return false
        }

        var service = gatt.getService(serviceUuid)
        var char = service?.getCharacteristic(charUuid)

        if (char == null) {
            for (s in gatt.services) {
                val c = s.getCharacteristic(charUuid)
                if (c != null) {
                    char = c
                    service = s
                    break
                }
            }
        }

        if (char == null) {
            Log.w(TAG, "writeCharacteristic failed: characteristic $charUuid not found on device")
            return false
        }

        val writeType = when {
            (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 -> {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 -> {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            else -> {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        }
        char.writeType = writeType

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(char, data, writeType)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    fun triggerCustomVibration(
        pattern: CustomizableVibrationPattern,
        onComplete: (() -> Unit)? = null
    ) {
        stopVibration()

        vibrationJob = scope.launch(Dispatchers.IO) {
            isVibrating = true
            try {
                val totalMs = pattern.durationSeconds * 1000L
                val startTime = System.currentTimeMillis()

                while (isActive && (System.currentTimeMillis() - startTime) < totalMs) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val progress = (elapsed.toFloat() / totalMs).coerceIn(0f, 1f)

                    val currentIntensity = when (pattern.type) {
                        PatternType.CRESCENDO -> {
                            pattern.startIntensityPercent + ((pattern.endIntensityPercent - pattern.startIntensityPercent) * progress).toInt()
                        }
                        PatternType.DECRESCENDO -> {
                            pattern.startIntensityPercent - ((pattern.startIntensityPercent - pattern.endIntensityPercent) * progress).toInt()
                        }
                        PatternType.HEARTBEAT -> {
                            pattern.startIntensityPercent
                        }
                        PatternType.STEADY, PatternType.PULSE_WAVE -> {
                            pattern.startIntensityPercent
                        }
                    }.coerceIn(10, 100)

                    val alertLevel = if (currentIntensity > 50) 0x02 else 0x01
                    val dutyCycle = (currentIntensity / 100f).coerceIn(0.15f, 1.0f)
                    val onTimeMs = (pattern.pulseMs * dutyCycle).toLong().coerceAtLeast(30L)
                    val offTimeMs = (pattern.pulseMs - onTimeMs).coerceAtLeast(0L)

                    writeAlertLevel(alertLevel)
                    delay(onTimeMs)

                    writeAlertLevel(0x00)
                    if (offTimeMs > 0L) {
                        delay(offTimeMs)
                    }

                    if (pattern.type == PatternType.HEARTBEAT) {
                        delay(80L)
                        writeAlertLevel(0x01)
                        delay((onTimeMs * 0.7f).toLong().coerceAtLeast(30L))
                        writeAlertLevel(0x00)
                    }

                    delay(pattern.pauseMs.toLong().coerceAtLeast(50L))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in custom vibration job", e)
            } finally {
                writeAlertLevel(0x00)
                isVibrating = false
                onComplete?.invoke()
            }
        }
    }

    fun triggerCadenceVibration(cadenceType: VibrationCadenceType) {
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
                        writeAlertLevel(0x01)
                    } else {
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

    fun stopVibration() {
        vibrationJob?.cancel()
        vibrationJob = null
        writeAlertLevel(0x00)
        isVibrating = false
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
