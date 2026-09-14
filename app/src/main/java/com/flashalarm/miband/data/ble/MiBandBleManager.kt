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
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val chunkedEncoder = com.flashalarm.miband.data.ble.protocol2021.Huami2021ChunkedEncoder(force2021Protocol = true)
    private val chunkedDecoder = com.flashalarm.miband.data.ble.protocol2021.Huami2021ChunkedDecoder(force2021Protocol = true)
    var use2021Protocol: Boolean = true

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
    private var isHrToggleBusy = false

    // Actigraphy differential state
    private var prevX = 0f
    private var prevY = 0f
    private var prevZ = 0f
    private var hasPrevSample = false
    private var smoothedActigraphy = 0f

    fun setTargetDevice(name: String, mac: String, authKeyHex: String, use2021: Boolean = true) {
        this.use2021Protocol = use2021
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

            Log.i(TAG, "Services discovered. Checking for 2021 Chunked or legacy auth...")
            _authStatusDetail.value = "已发现服务，正在检索认证特征通道..."

            var authChar: BluetoothGattCharacteristic? = null
            var chunkedReadChar: BluetoothGattCharacteristic? = null
            var chunkedWriteChar: BluetoothGattCharacteristic? = null

            for (s in gatt.services) {
                if (authChar == null) authChar = s.getCharacteristic(BleConstants.UUID_CHAR_AUTH)
                if (chunkedReadChar == null) chunkedReadChar = s.getCharacteristic(BleConstants.UUID_CHAR_CHUNKED_2021_READ)
                if (chunkedWriteChar == null) chunkedWriteChar = s.getCharacteristic(BleConstants.UUID_CHAR_CHUNKED_2021_WRITE)
            }

            val canUse2021 = use2021Protocol && (chunkedReadChar != null && chunkedWriteChar != null)

            if (canUse2021) {
                Log.i(TAG, "Huami 2021 Chunked characteristics found (0x0016 & 0x0017)! Enabling 2021 security channel...")
                _authStatusDetail.value = "已匹配2021新认证通道(0x0016/0x0017)，正在配置安全分块..."
                enableNotification(gatt, chunkedReadChar!!)
            } else if (authChar != null) {
                Log.i(TAG, "Huami Auth characteristic (0x0009) found! Enabling notification/indication...")
                _authStatusDetail.value = "已找到传统认证特征(0x0009)，正在配置安全通道..."
                enableNotification(gatt, authChar)
            } else {
                Log.e(TAG, "Neither Huami 2021 nor legacy auth characteristics found on device!")
                _connectionState.value = BleConnectionState.ERROR
                _authStatusDetail.value = "未找到手环认证特征通道 (0x0009 或 0x0017)"
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

            if (charUuid == BleConstants.UUID_CHAR_CHUNKED_2021_READ) {
                scope.launch(Dispatchers.IO) {
                    delay(150L)
                    _authStatusDetail.value = "2021安全通道就绪，生成ECDH公钥，发起握手..."
                    chunkedDecoder.setHandler { type, payload ->
                        if (type == BleConstants.CHUNKED2021_ENDPOINT_AUTH) {
                            handle2021AuthPayload(gatt, payload)
                        }
                    }
                    val chunks = authHandler.startHandshake2021(chunkedEncoder)
                    for (chunk in chunks) {
                        delay(40L)
                        writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_WRITE, chunk)
                    }
                }
            } else if (charUuid == BleConstants.UUID_CHAR_AUTH) {
                // Dispatch with slight delay so the GATT stack transitions out of GATT_BUSY state!
                scope.launch(Dispatchers.IO) {
                    delay(150L)
                    val requestPacket = authHandler.startHandshake()
                    Log.i(TAG, "Writing request random challenge packet: ${requestPacket.joinToString(separator = " ") { "%02X".format(it) }}")
                    _authStatusDetail.value = "安全通道就绪，已发送Challenge请求，等待手环响应..."
                    val writeSuccess = writeCharacteristic(BleConstants.UUID_SERVICE_AUTH, BleConstants.UUID_CHAR_AUTH, requestPacket)
                    if (!writeSuccess) {
                        Log.e(TAG, "Failed sending random challenge request packet")
                        _authStatusDetail.value = "发送随机数请求失败，请重试"
                        _connectionState.value = BleConnectionState.ERROR
                    }
                }
            } else if (charUuid == BleConstants.UUID_CHAR_HEART_RATE_MEASUREMENT) {
                scope.launch(Dispatchers.IO) {
                    delay(150L)
                    writeCharacteristic(BleConstants.UUID_SERVICE_HEART_RATE, BleConstants.UUID_CHAR_HEART_RATE_CONTROL, BleConstants.HR_START_CONTINUOUS)
                    startHrKeepAlive()
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

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged: mtu=$mtu, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                chunkedEncoder.setMtu(mtu)
            }
        }
    }

    private fun handle2021AuthPayload(gatt: BluetoothGatt?, payload: ByteArray) {
        when (val result = authHandler.handle2021Payload(payload, chunkedEncoder, chunkedDecoder)) {
            is AuthResult.SendChunks -> {
                _authStatusDetail.value = "已获取2021挑战码，正在回传双重AES密文..."
                scope.launch(Dispatchers.IO) {
                    for (chunk in result.chunks) {
                        delay(40L)
                        writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_WRITE, chunk)
                    }
                }
            }
            is AuthResult.Success -> {
                Log.i(TAG, "Huami 2021 authentication SUCCESSFUL! Mi Band 6 is authenticated.")
                authTimeoutJob?.cancel()
                authTimeoutJob = null
                _authStatusDetail.value = "2021安全握手认证成功！手环已就绪"
                _connectionState.value = BleConnectionState.CONNECTED

                gatt?.requestMtu(512)

                // Subscribe HR + sensor directly (don't rely on descriptor write cascade)
                scope.launch(Dispatchers.IO) {
                    delay(250L)
                    gatt?.let { g ->
                        subscribeHeartRate(g)
                        delay(200L)
                        enableSensorNotifications(g)
                    }
                }
            }
            is AuthResult.Failed -> {
                Log.e(TAG, "2021 authentication failed: ${result.error}")
                authTimeoutJob?.cancel()
                authTimeoutJob = null
                _authStatusDetail.value = result.error
                _connectionState.value = BleConnectionState.ERROR
            }
            else -> {}
        }
    }

    private fun handleCharacteristicData(uuid: UUID, value: ByteArray) {
        when (uuid) {
            BleConstants.UUID_CHAR_CHUNKED_2021_READ -> {
                val needsAck = chunkedDecoder.decode(value)
                if (needsAck) {
                    val ack = byteArrayOf(0x04, 0x00, chunkedDecoder.lastHandle, 0x01, chunkedDecoder.lastCount)
                    writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_READ, ack)
                }
            }

            BleConstants.UUID_CHAR_AUTH -> {
                val hexStr = value.joinToString(separator = " ") { "%02X".format(it) }
                Log.i(TAG, "Received AUTH characteristic update: [$hexStr]")

                when (val result = authHandler.handleAuthNotification(value)) {
                    is AuthResult.SendPacket -> {
                        val packetHex = result.data.joinToString(separator = " ") { "%02X".format(it) }
                        val opByte = result.data.getOrElse(0) { 0 }
                        val maskedOp = opByte.toInt() and 0x0F
                        if (maskedOp == BleConstants.AUTH_BYTE_PAIR_OP.toInt()) {
                            _authStatusDetail.value = "手环提示：请轻触手环屏幕确认配对..."
                        } else if (maskedOp == BleConstants.AUTH_BYTE_RANDOM_KEY_OP.toInt()) {
                            _authStatusDetail.value = "正在请求手环挑战码 (Op: 0x%02X)...".format(opByte)
                        } else {
                            _authStatusDetail.value = "已获取Challenge，正在回传AES密文 (Op: 0x%02X)...".format(opByte)
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

                    is AuthResult.SendChunks -> {
                        scope.launch(Dispatchers.IO) {
                            for (chunk in result.chunks) {
                                delay(40L)
                                writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_WRITE, chunk)
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
                            bluetoothGatt?.let { g ->
                                subscribeHeartRate(g)
                                delay(200L)
                                enableSensorNotifications(g)
                            }
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
                        val streaming = hrKeepAliveJob?.isActive == true
                        _deviceMetrics.value = _deviceMetrics.value.copy(
                            heartRateBpm = hr,
                            isHrStreaming = streaming
                        )
                        _heartRateFlow.tryEmit(hr)
                    }
                }
            }

            BleConstants.UUID_CHAR_SENSOR_DATA -> {
                parseActigraphyData(value)
            }

            BleConstants.UUID_CHAR_REALTIME_STEPS -> {
                parseRealtimeStepsData(value)
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

    fun enableSensorNotifications(gatt: BluetoothGatt? = bluetoothGatt, resetBaseline: Boolean = false) {
        val g = gatt ?: bluetoothGatt ?: run {
            Log.w(TAG, "enableSensorNotifications: bluetoothGatt is null")
            return
        }
        if (resetBaseline) {
            hasPrevSample = false
            smoothedActigraphy = 0.0f
            _deviceMetrics.value = _deviceMetrics.value.copy(actigraphyG = 0.0f)
        }
        _deviceMetrics.value = _deviceMetrics.value.copy(isMotionStreaming = true)
        scope.launch(Dispatchers.IO) {
            var huamiService = g.getService(BleConstants.UUID_SERVICE_HUAMI)
            var sensorDataChar = huamiService?.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_DATA)
            var sensorCtrlChar = huamiService?.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_CTRL)
            var stepsChar = huamiService?.getCharacteristic(BleConstants.UUID_CHAR_REALTIME_STEPS)

            if (sensorDataChar == null || sensorCtrlChar == null || stepsChar == null) {
                for (s in g.services) {
                    if (sensorDataChar == null) sensorDataChar = s.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_DATA)
                    if (sensorCtrlChar == null) sensorCtrlChar = s.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_CTRL)
                    if (stepsChar == null) stepsChar = s.getCharacteristic(BleConstants.UUID_CHAR_REALTIME_STEPS)
                }
            }

            // 1. Enable CCCD on 0x0002 (Data) for raw 25Hz accelerometer streaming
            if (sensorDataChar != null) {
                Log.i(TAG, "Enabling sensor data notification on 0x0002...")
                enableNotification(g, sensorDataChar)
                delay(250L)
            }

            // 2. Enable CCCD on 0x0007 (Realtime steps & movement)
            if (stepsChar != null) {
                Log.i(TAG, "Enabling realtime steps notification on 0x0007...")
                enableNotification(g, stepsChar)
                delay(250L)
            }

            // 3. Enable CCCD on 0x0001 (Control)
            if (sensorCtrlChar != null) {
                Log.i(TAG, "Enabling sensor control notification on 0x0001...")
                enableNotification(g, sensorCtrlChar)
                delay(250L)
            }

            // 4. Send Huami raw sensor sequence with WRITE_TYPE_NO_RESPONSE
            if (sensorCtrlChar != null) {
                Log.i(TAG, "Sending CMD_RAW_SENSOR_START_1 [0x01, 0x03, 0x19] to 0x0001...")
                writeRawSensorCommand(g, sensorCtrlChar, BleConstants.CMD_RAW_SENSOR_START_1)
                delay(150L)
                Log.i(TAG, "Sending CMD_RAW_SENSOR_START_3 [0x02] trigger to 0x0001...")
                writeRawSensorCommand(g, sensorCtrlChar, BleConstants.CMD_RAW_SENSOR_START_3)
                delay(150L)
            }

            // 5. If 2021 protocol, also enable realtime steps on endpoint 0x0016
            if (use2021Protocol) {
                try {
                    val stepsPayload = byteArrayOf(BleConstants.STEPS_CMD_ENABLE_REALTIME) // 0x05
                    val chunks = chunkedEncoder.encode(BleConstants.CHUNKED2021_ENDPOINT_STEPS, stepsPayload, extendedFlags = true, encrypt = true)
                    for (chunk in chunks) {
                        delay(35L)
                        writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_WRITE, chunk)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed enabling 2021 realtime steps stream", e)
                }
            }
        }
    }

    private var lastStepsCount = -1

    private fun parseRealtimeStepsData(data: ByteArray) {
        if (data.isEmpty()) return
        try {
            val currentSteps = if (data.size >= 3) {
                (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
            } else {
                (data[0].toInt() and 0xFF)
            }

            val stepDelta = if (lastStepsCount >= 0) (currentSteps - lastStepsCount).coerceAtLeast(0) else 0
            lastStepsCount = currentSteps

            val movementG = when {
                stepDelta >= 5 -> 0.35f
                stepDelta > 0 -> 0.18f
                else -> 0.015f
            }

            smoothedActigraphy = if (smoothedActigraphy <= 0.0001f) movementG else (smoothedActigraphy * 0.7f + movementG * 0.3f)
            _deviceMetrics.value = _deviceMetrics.value.copy(
                actigraphyG = smoothedActigraphy,
                isMotionStreaming = true
            )
            _actigraphyFlow.tryEmit(smoothedActigraphy)
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing realtime steps data", e)
        }
    }

    private fun writeRawSensorCommand(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    private fun parseActigraphyData(data: ByteArray) {
        if (data.size < 6) return
        try {
            // Standard Mi Band raw sensor packet (20 bytes): 2-byte sequence header + 3x 6-byte samples
            var offset = if (data.size % 6 == 2) 2 else 0
            var sumMovement = 0.0f
            var sampleCount = 0

            while (offset + 6 <= data.size) {
                // Correct 16-bit signed integer extraction
                val rawX = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                val rawY = (data[offset + 2].toInt() and 0xFF) or ((data[offset + 3].toInt() and 0xFF) shl 8)
                val rawZ = (data[offset + 4].toInt() and 0xFF) or ((data[offset + 5].toInt() and 0xFF) shl 8)

                val x = rawX.toShort().toFloat()
                val y = rawY.toShort().toFloat()
                val z = rawZ.toShort().toFloat()

                if (hasPrevSample) {
                    val dx = (x - prevX) / 4096.0f
                    val dy = (y - prevY) / 4096.0f
                    val dz = (z - prevZ) / 4096.0f
                    val jerk = sqrt(dx * dx + dy * dy + dz * dz)
                    sumMovement += jerk
                    sampleCount++
                } else {
                    hasPrevSample = true
                }

                prevX = x
                prevY = y
                prevZ = z
                offset += 6
            }

            val avgMagnitude = if (sampleCount > 0) sumMovement / sampleCount else 0.0f
            // Exponential smoothing to eliminate digital jitter
            smoothedActigraphy = if (smoothedActigraphy <= 0.0001f) avgMagnitude else (smoothedActigraphy * 0.65f + avgMagnitude * 0.35f)

            _deviceMetrics.value = _deviceMetrics.value.copy(
                actigraphyG = smoothedActigraphy,
                isMotionStreaming = true
            )
            _actigraphyFlow.tryEmit(smoothedActigraphy)
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
        if (isHrToggleBusy) {
            Log.w(TAG, "setHeartRateStreamingMode: toggle busy, skipping rapid invocation")
            return
        }
        isHrToggleBusy = true
        scope.launch(Dispatchers.IO) {
            try {
                if (isContinuous) {
                    Log.i(TAG, "Enabling continuous heart rate streaming...")
                    // 1. Ensure manual measurement is stopped first
                    writeCharacteristic(BleConstants.UUID_SERVICE_HEART_RATE, BleConstants.UUID_CHAR_HEART_RATE_CONTROL, byteArrayOf(0x15, 0x02, 0x00))
                    delay(80L)
                    // 2. Start continuous heart rate measurement
                    writeCharacteristic(BleConstants.UUID_SERVICE_HEART_RATE, BleConstants.UUID_CHAR_HEART_RATE_CONTROL, BleConstants.HR_START_CONTINUOUS)
                    delay(80L)
                    startHrKeepAlive()
                    _deviceMetrics.value = _deviceMetrics.value.copy(isHrStreaming = true)
                } else {
                    Log.i(TAG, "Stopping continuous heart rate streaming...")
                    hrKeepAliveJob?.cancel()
                    hrKeepAliveJob = null
                    // Stop continuous measurement
                    writeCharacteristic(BleConstants.UUID_SERVICE_HEART_RATE, BleConstants.UUID_CHAR_HEART_RATE_CONTROL, BleConstants.HR_STOP_CONTINUOUS)
                    delay(80L)
                    writeCharacteristic(BleConstants.UUID_SERVICE_HEART_RATE, BleConstants.UUID_CHAR_HEART_RATE_CONTROL, byteArrayOf(0x15, 0x02, 0x00))
                    _deviceMetrics.value = _deviceMetrics.value.copy(isHrStreaming = false)
                }

                // Also sync 2021 chunked heart rate endpoint (0x001D) if on 2021 protocol
                if (use2021Protocol) {
                    try {
                        val hrPayload = byteArrayOf(0x04, if (isContinuous) 0x01 else 0x00)
                        val hrChunks = chunkedEncoder.encode(BleConstants.CHUNKED2021_ENDPOINT_HEARTRATE, hrPayload, extendedFlags = true, encrypt = true)
                        for (chunk in hrChunks) {
                            delay(35L)
                            writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_WRITE, chunk)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error sending 2021 HR mode chunk", e)
                    }
                }
            } finally {
                delay(300L) // Debounce window
                isHrToggleBusy = false
            }
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

    /**
     * Huami 2021 Custom Vibration Pattern Protocol (Gadgetbridge compatible)
     * Writes to CHUNKED2021_ENDPOINT_COMPAT (0x0090) with header [0x00, 0x00, 0xC2, 0x00] and opcode 0x20.
     * When test = true, flag has bit 7 (0x80) set:
     * - The band hardware motor plays the [on_ms, off_ms] sequence directly.
     * - Does NOT trigger an incoming call or notification screen!
     * - Zero roundtrip Bluetooth debounce or pulse dropping.
     */
    fun sendNativeVibrationPattern(
        onOffSequence: List<Short>,
        notifType: Byte = 0x09, // FIND_BAND (0x09) does not display incoming call UI
        test: Boolean = true
    ): Boolean {
        if (!use2021Protocol || onOffSequence.isEmpty()) {
            return false
        }

        // The pattern must consist of even pairs [on_ms, off_ms, ...]
        val normalizedList = if (onOffSequence.size % 2 != 0) {
            onOffSequence + 0.toShort()
        } else {
            onOffSequence
        }

        val pairCount = normalizedList.size / 2
        var flag = pairCount or 0x40
        if (test) {
            flag = flag or 0x80
        }

        val buffer = ByteBuffer.allocate(3 + normalizedList.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x20.toByte()) // Custom vibration opcode
        buffer.put(notifType)    // Notification type (0x09: FIND_BAND, 0x01: CALL, 0x00: APP)
        buffer.put(flag.toByte())
        for (duration in normalizedList) {
            buffer.putShort(duration)
        }

        val header = byteArrayOf(0x00, 0x00, 0xC2.toByte(), 0x00)
        val payload = header + buffer.array()

        return try {
            val chunks = chunkedEncoder.encode(
                BleConstants.CHUNKED2021_ENDPOINT_COMPAT,
                payload,
                extendedFlags = true,
                encrypt = true
            )
            if (chunks.isEmpty()) {
                Log.w(TAG, "sendNativeVibrationPattern: chunkedEncoder returned empty")
                return false
            }
            scope.launch(Dispatchers.IO) {
                for (chunk in chunks) {
                    delay(20L)
                    writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_WRITE, chunk)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendNativeVibrationPattern failed", e)
            false
        }
    }

    private fun startMotorVibration(onTimeMs: Int = 300) {
        // 1. Huami 2021 Chunked Protocol: Endpoint 0x0090 (CALL_INCOMING)
        // Mi Band 6 ignores standard 0x001A and 0x2A06, but triggers physical motor via 2021 call notifications
        if (use2021Protocol) {
            try {
                val callStartPayload = byteArrayOf(0x00, 0x00, 0xC0.toByte(), 0x00, 3, 0, 0, 0, 0, 0) +
                        "DreamAlarm".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0, 0, 2)
                val chunks = chunkedEncoder.encode(
                    BleConstants.CHUNKED2021_ENDPOINT_COMPAT,
                    callStartPayload,
                    extendedFlags = true,
                    encrypt = true
                )
                scope.launch(Dispatchers.IO) {
                    for (chunk in chunks) {
                        delay(20L)
                        writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_WRITE, chunk)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending 2021 call start vibration chunk", e)
            }

            // Also send Find Band start (0x03) to 0x001A as auxiliary
            try {
                val findChunks = chunkedEncoder.encode(
                    BleConstants.CHUNKED2021_ENDPOINT_FIND_DEVICE,
                    byteArrayOf(0x03),
                    extendedFlags = true,
                    encrypt = true
                )
                scope.launch(Dispatchers.IO) {
                    for (chunk in findChunks) {
                        delay(20L)
                        writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_WRITE, chunk)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending 2021 find device start", e)
            }
        }

        // 2. Immediate Alert Service (0x1802 / 0x2A06) as hardware fallback
        bluetoothGatt?.let { gatt ->
            var service = gatt.getService(BleConstants.UUID_SERVICE_IMMEDIATE_ALERT)
            var char = service?.getCharacteristic(BleConstants.UUID_CHAR_ALERT_LEVEL)
            if (char == null) {
                for (s in gatt.services) {
                    val c = s.getCharacteristic(BleConstants.UUID_CHAR_ALERT_LEVEL)
                    if (c != null) {
                        char = c
                        break
                    }
                }
            }
            if (char != null) {
                writeDirectImmediateAlert(gatt, char, byteArrayOf(BleConstants.ALERT_LEVEL_VIBRATE_ONLY))
                val patternPacket = byteArrayOf(
                    0xFF.toByte(),
                    (onTimeMs and 0xFF).toByte(),
                    ((onTimeMs shr 8) and 0xFF).toByte(),
                    (100 and 0xFF).toByte(),
                    0x00,
                    1
                )
                writeDirectImmediateAlert(gatt, char, patternPacket)
            }
        }
    }

    private fun stopMotorVibration() {
        // 1. Huami 2021 Chunked Protocol: Endpoint 0x0090 (CALL_STOP)
        if (use2021Protocol) {
            try {
                val callStopPayload = byteArrayOf(0x00, 0x00, 0xC0.toByte(), 0x00, 3, 3, 0, 0, 0, 0)
                val chunks = chunkedEncoder.encode(
                    BleConstants.CHUNKED2021_ENDPOINT_COMPAT,
                    callStopPayload,
                    extendedFlags = true,
                    encrypt = true
                )
                scope.launch(Dispatchers.IO) {
                    for (chunk in chunks) {
                        delay(20L)
                        writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_WRITE, chunk)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending 2021 call stop vibration chunk", e)
            }

            // Also send Find Band stop (0x06) to 0x001A
            try {
                val findStopChunks = chunkedEncoder.encode(
                    BleConstants.CHUNKED2021_ENDPOINT_FIND_DEVICE,
                    byteArrayOf(0x06),
                    extendedFlags = true,
                    encrypt = true
                )
                scope.launch(Dispatchers.IO) {
                    for (chunk in findStopChunks) {
                        delay(20L)
                        writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_WRITE, chunk)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending 2021 find device stop", e)
            }
        }

        // 2. Immediate Alert Service (0x1802 / 0x2A06)
        bluetoothGatt?.let { gatt ->
            var service = gatt.getService(BleConstants.UUID_SERVICE_IMMEDIATE_ALERT)
            var char = service?.getCharacteristic(BleConstants.UUID_CHAR_ALERT_LEVEL)
            if (char == null) {
                for (s in gatt.services) {
                    val c = s.getCharacteristic(BleConstants.UUID_CHAR_ALERT_LEVEL)
                    if (c != null) {
                        char = c
                        break
                    }
                }
            }
            if (char != null) {
                writeDirectImmediateAlert(gatt, char, byteArrayOf(BleConstants.ALERT_LEVEL_NONE))
            }
        }
    }

    fun triggerCustomVibration(
        pattern: CustomizableVibrationPattern,
        onComplete: (() -> Unit)? = null
    ) {
        stopVibration()

        // 1. Build the millisecond sequence [on, off, on, off...]
        val onOffSequence = mutableListOf<Short>()
        val repeatCount = pattern.repeatCount.coerceIn(1, 10)
        val pulseMs = pattern.pulseMs.coerceIn(60, 2000)
        val pauseMs = pattern.pauseMs.coerceIn(60, 2000)

        when (pattern.type) {
            PatternType.CRESCENDO -> {
                for (i in 0 until repeatCount) {
                    val stepPulse = (pulseMs * (0.6f + 0.4f * (i + 1) / repeatCount)).toInt().toShort()
                    onOffSequence.add(stepPulse)
                    onOffSequence.add(pauseMs.toShort())
                }
            }
            PatternType.HEARTBEAT -> {
                for (i in 0 until repeatCount) {
                    onOffSequence.add(120.toShort())
                    onOffSequence.add(120.toShort())
                    onOffSequence.add(180.toShort())
                    onOffSequence.add(pauseMs.coerceAtLeast(600).toShort())
                }
            }
            else -> {
                for (i in 0 until repeatCount) {
                    onOffSequence.add(pulseMs.toShort())
                    onOffSequence.add(pauseMs.toShort())
                }
            }
        }

        // 2. Try native 2021 custom vibration pattern first (silent, no call screen, accurate pulse count)
        if (use2021Protocol) {
            val sent = sendNativeVibrationPattern(onOffSequence, notifType = 0x09, test = true)
            if (sent) {
                val totalDurationMs = onOffSequence.sumOf { it.toLong() }
                vibrationJob = scope.launch(Dispatchers.IO) {
                    isVibrating = true
                    try {
                        delay(totalDurationMs + 100L)
                    } finally {
                        isVibrating = false
                        onComplete?.invoke()
                    }
                }
                return
            }
        }

        // 3. Fallback for non-2021 devices: software timed loop
        vibrationJob = scope.launch(Dispatchers.IO) {
            isVibrating = true
            try {
                for (i in 0 until onOffSequence.size step 2) {
                    if (!isActive) break
                    val onDuration = onOffSequence[i].toLong()
                    startMotorVibration(onDuration.toInt())
                    delay(onDuration)
                    stopMotorVibration()
                    if (i + 1 < onOffSequence.size) {
                        val offDuration = onOffSequence[i + 1].toLong()
                        delay(offDuration)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in custom vibration fallback job", e)
            } finally {
                stopMotorVibration()
                isVibrating = false
                onComplete?.invoke()
            }
        }
    }

    fun triggerCadenceVibration(cadenceType: VibrationCadenceType) {
        val pattern = VibrationCadenceProfiles.getPattern(cadenceType)
        stopVibration()

        val onOffSequence = pattern.sequenceMs.map { it.toShort() }
        if (use2021Protocol) {
            val sent = sendNativeVibrationPattern(onOffSequence, notifType = 0x09, test = true)
            if (sent) {
                val totalDurationMs = pattern.sequenceMs.sum()
                vibrationJob = scope.launch(Dispatchers.IO) {
                    isVibrating = true
                    try {
                        delay(totalDurationMs + 100L)
                    } finally {
                        isVibrating = false
                    }
                }
                return
            }
        }

        vibrationJob = scope.launch(Dispatchers.IO) {
            isVibrating = true
            try {
                for (i in pattern.sequenceMs.indices) {
                    if (!isActive) break
                    val duration = pattern.sequenceMs[i]
                    val isVibrateStep = (i % 2 == 0)

                    if (isVibrateStep) {
                        startMotorVibration(duration.toInt())
                    } else {
                        stopMotorVibration()
                    }
                    delay(duration)
                }
            } finally {
                stopMotorVibration()
                isVibrating = false
            }
        }
    }

    fun stopVibration() {
        vibrationJob?.cancel()
        vibrationJob = null
        stopMotorVibration()
        isVibrating = false
    }

    private fun writeAlertLevel(level: Int, onTimeMs: Int = 250, offTimeMs: Int = 200, repeat: Int = 1) {
        if (level > 0) {
            startMotorVibration(onTimeMs)
        } else {
            stopMotorVibration()
        }
    }

    private fun writeDirectImmediateAlert(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, data: ByteArray) {
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
