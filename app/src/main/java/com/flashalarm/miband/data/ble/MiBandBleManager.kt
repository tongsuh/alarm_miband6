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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private var motionWatchdogJob: Job? = null
    private var actigraphyTickerJob: Job? = null
    private var vibrationJob: Job? = null
    private var isVibrating = false
    private var isHrToggleBusy = false

    // Dynamic Actigraphy differential state (successive differences AC filter)
    private var prevSampleX = 0f
    private var prevSampleY = 0f
    private var prevSampleZ = 0f
    private var hasPrevSample = false
    private var lastPacketIndex = -1
    private var gravityScale = 4096f
    private var smoothedActigraphy = 0f
    private var lastRawSensorPacketTimeMs = 0L
    private var totalRawSensorPackets: Long = 0L
    private var lastSampleX: Float = 0f
    private var lastSampleY: Float = 0f
    private var lastSampleZ: Float = 0f

    // Sequential GATT operation synchronization
    private val gattMutex = Mutex()
    private val descriptorDeferredMap = ConcurrentHashMap<UUID, CompletableDeferred<Int>>()
    private val characteristicWriteDeferredMap = ConcurrentHashMap<UUID, CompletableDeferred<Int>>()

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
        motionWatchdogJob?.cancel()
        motionWatchdogJob = null
        actigraphyTickerJob?.cancel()
        actigraphyTickerJob = null
        hasPrevSample = false
        lastPacketIndex = -1
        stopVibration()

        descriptorDeferredMap.values.forEach { it.cancel() }
        descriptorDeferredMap.clear()
        characteristicWriteDeferredMap.values.forEach { it.cancel() }
        characteristicWriteDeferredMap.clear()
        totalRawSensorPackets = 0L

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

            // Notify any awaiting sequential descriptor deferred
            if (charUuid != null) {
                descriptorDeferredMap.remove(charUuid)?.complete(status)
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Failed writing descriptor for $charUuid, status=$status")
                // Only treat as fatal error if it's the security channel auth descriptor!
                if (charUuid == BleConstants.UUID_CHAR_CHUNKED_2021_READ || charUuid == BleConstants.UUID_CHAR_AUTH) {
                    _authStatusDetail.value = "配置安全通道描述符失败 (状态码: $status)"
                    _connectionState.value = BleConnectionState.ERROR
                }
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
                if (_deviceMetrics.value.isHrStreaming && gatt != null) {
                    startHrKeepAlive(gatt)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            val charUuid = characteristic?.uuid
            Log.d(TAG, "onCharacteristicWrite uuid=$charUuid, status=$status")
            if (charUuid != null) {
                characteristicWriteDeferredMap.remove(charUuid)?.complete(status)
            }
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

                // Subscribe HR notifications on auth ready (keep sensor stream idle for low-power standby)
                scope.launch(Dispatchers.IO) {
                    delay(250L)
                    gatt?.let { g ->
                        subscribeHeartRate(g)
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

                        // Subscribe HR notifications on auth ready (keep sensor stream idle for low-power standby)
                        scope.launch(Dispatchers.IO) {
                            delay(250L)
                            bluetoothGatt?.let { g ->
                                subscribeHeartRate(g)
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
                        _deviceMetrics.value = _deviceMetrics.value.copy(
                            heartRateBpm = hr
                        )
                        _heartRateFlow.tryEmit(hr)
                    }
                }
            }

            BleConstants.UUID_CHAR_SENSOR_DATA -> {
                parseActigraphyData(value)
            }

            BleConstants.UUID_CHAR_SENSOR_CTRL -> {
                val hex = value.joinToString(separator = " ") { "%02X".format(it) }
                Log.i(TAG, "Huami Sensor Control (0x0001) notification ACK: [$hex]")
            }

            BleConstants.UUID_CHAR_REALTIME_STEPS -> {
                parseRealtimeStepsData(value)
            }
        }
    }

    private suspend fun subscribeHeartRate(gatt: BluetoothGatt) {
        var hrService = gatt.getService(BleConstants.UUID_SERVICE_HEART_RATE)
        var hrChar = hrService?.getCharacteristic(BleConstants.UUID_CHAR_HEART_RATE_MEASUREMENT)
        if (hrChar == null) {
            for (s in gatt.services) {
                val c = s.getCharacteristic(BleConstants.UUID_CHAR_HEART_RATE_MEASUREMENT)
                if (c != null) {
                    hrChar = c
                    hrService = s
                    break
                }
            }
        }
        if (hrChar != null) {
            Log.i(TAG, "Subscribing heart rate measurement sequentially...")
            enableNotificationSequential(gatt, hrChar)
        } else {
            Log.w(TAG, "Standard Heart Rate service not found")
        }
    }

    fun startHrKeepAlive(gatt: BluetoothGatt) {
        hrKeepAliveJob?.cancel()
        hrKeepAliveJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Heart rate keepalive ping started (12s interval to 0x2A39)")
            while (isActive && _connectionState.value == BleConnectionState.CONNECTED && _deviceMetrics.value.isHrStreaming) {
                delay(12000L)
                if (!_deviceMetrics.value.isHrStreaming) break
                var hrService = gatt.getService(BleConstants.UUID_SERVICE_HEART_RATE)
                var hrCtrlChar = hrService?.getCharacteristic(BleConstants.UUID_CHAR_HEART_RATE_CONTROL)
                if (hrCtrlChar == null) {
                    for (s in gatt.services) {
                        val c = s.getCharacteristic(BleConstants.UUID_CHAR_HEART_RATE_CONTROL)
                        if (c != null) { hrCtrlChar = c; break }
                    }
                }
                if (hrCtrlChar != null) {
                    writeCharacteristicSequential(gatt, hrCtrlChar, BleConstants.HR_PING_KEEPALIVE)
                }
            }
            Log.i(TAG, "Heart rate keepalive ping stopped.")
        }
    }

    fun stopHrKeepAlive() {
        hrKeepAliveJob?.cancel()
        hrKeepAliveJob = null
    }

    private fun startMotionWatchdog(gatt: BluetoothGatt) {
        motionWatchdogJob?.cancel()
        motionWatchdogJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Motion sensor watchdog started (responsive stall monitor & clean session renewal)")
            while (isActive && _connectionState.value == BleConnectionState.CONNECTED && _deviceMetrics.value.isMotionStreaming) {
                delay(1500L) // Fast 1.5s check interval
                if (!_deviceMetrics.value.isMotionStreaming) break
                val now = System.currentTimeMillis()
                val idleMs = now - lastRawSensorPacketTimeMs

                // When packets are flowing (idleMs <= 1800ms), DO NOT interrupt the active hardware FIFO.
                // Only take action when the 50s session naturally completes or stalls (>1800ms without packets):
                if (idleMs > 1800L && totalRawSensorPackets > 0L) {
                    var huamiService = gatt.getService(BleConstants.UUID_SERVICE_HUAMI)
                    var sensorCtrlChar = huamiService?.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_CTRL)
                    if (sensorCtrlChar == null) {
                        for (s in gatt.services) {
                            val c = s.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_CTRL)
                            if (c != null) { sensorCtrlChar = c; break }
                        }
                    }

                    if (sensorCtrlChar != null) {
                        Log.i(TAG, "Motion stream idle for ${idleMs}ms (50s session elapsed). Renewing 3-step activation...")
                        writeCharacteristicSequential(gatt, sensorCtrlChar, BleConstants.CMD_RAW_SENSOR_START_1)
                        delay(40L)
                        writeCharacteristicSequential(gatt, sensorCtrlChar, BleConstants.CMD_RAW_SENSOR_START_2)
                        delay(40L)
                        writeCharacteristicSequential(gatt, sensorCtrlChar, BleConstants.CMD_RAW_SENSOR_START_3)
                        // Reset timer baseline so we don't fire redundantly before first packet arrives
                        lastRawSensorPacketTimeMs = System.currentTimeMillis()
                    }
                }
            }
            Log.i(TAG, "Motion sensor watchdog stopped.")
        }
    }

    private fun stopMotionWatchdog() {
        motionWatchdogJob?.cancel()
        motionWatchdogJob = null
    }

    fun enableSensorNotifications(gatt: BluetoothGatt? = bluetoothGatt, resetBaseline: Boolean = false) {
        val g = gatt ?: bluetoothGatt ?: run {
            Log.w(TAG, "enableSensorNotifications: bluetoothGatt is null")
            return
        }
        // Request high connection priority (low latency 11.25~15ms interval) to prevent band FIFO overflow at 25Hz
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

        if (resetBaseline) {
            hasPrevSample = false
            lastPacketIndex = -1
            smoothedActigraphy = 0.0f
            _deviceMetrics.value = _deviceMetrics.value.copy(
                actigraphyG = 0.0f
            )
            Log.i(TAG, "Sensor actigraphy dynamic filter reset requested.")
        }
        _deviceMetrics.value = _deviceMetrics.value.copy(isMotionStreaming = true)
        startMotionWatchdog(g)
        startActigraphyTicker()
        scope.launch(Dispatchers.IO) {
            var huamiService = g.getService(BleConstants.UUID_SERVICE_HUAMI)
            var sensorDataChar = huamiService?.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_DATA)
            var sensorCtrlChar = huamiService?.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_CTRL)

            if (sensorDataChar == null || sensorCtrlChar == null) {
                for (s in g.services) {
                    if (sensorDataChar == null) sensorDataChar = s.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_DATA)
                    if (sensorCtrlChar == null) sensorCtrlChar = s.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_CTRL)
                }
            }

            // Step 1: Enable CCCD on 0x0001 (Sensor Ctrl) so band can ACK our commands
            if (sensorCtrlChar != null) {
                Log.i(TAG, "Enabling sensor control notification on 0x0001 sequentially...")
                enableNotificationSequential(g, sensorCtrlChar)
            }

            // Step 2: Enable CCCD on 0x0002 (Sensor Data) using sequential dispatcher
            if (sensorDataChar != null) {
                Log.i(TAG, "Enabling sensor data notification on 0x0002 sequentially...")
                val success = enableNotificationSequential(g, sensorDataChar)
                Log.i(TAG, "0x0002 notification enabled result: $success")
            } else {
                Log.w(TAG, "0x0002 (UUID_CHAR_SENSOR_DATA) not found on device!")
            }

            // Step 3: Send standard Huami 2021 raw sensor activation sequence to 0x0001 (accelerometer only, no green LED)
            if (sensorCtrlChar != null) {
                // A. Stop previous sensor streams to reset hardware FIFO state
                Log.i(TAG, "Sending CMD_RAW_SENSOR_STOP [0x03] to 0x0001...")
                writeCharacteristicSequential(g, sensorCtrlChar, BleConstants.CMD_RAW_SENSOR_STOP)
                delay(60L)

                // B. Send CMD_RAW_SENSOR_START_1 [0x01, 0x03, 0x19] (band replies 10:01:03:05)
                Log.i(TAG, "Sending CMD_RAW_SENSOR_START_1 [0x01, 0x03, 0x19] to 0x0001...")
                writeCharacteristicSequential(g, sensorCtrlChar, BleConstants.CMD_RAW_SENSOR_START_1)
                delay(60L)

                // C. Send CMD_RAW_SENSOR_START_2 [0x01, 0x03, 0x00, 0x00, 0x00, 0x19] (band replies 10:01:01:05)
                Log.i(TAG, "Sending CMD_RAW_SENSOR_START_2 [0x01, 0x03, 0x00, 0x00, 0x00, 0x19] to 0x0001...")
                writeCharacteristicSequential(g, sensorCtrlChar, BleConstants.CMD_RAW_SENSOR_START_2)
                delay(60L)

                // D. Send CMD_RAW_SENSOR_START_3 [0x02] trigger to kick-start hardware FIFO pushing (band replies 10:02:01)
                Log.i(TAG, "Sending CMD_RAW_SENSOR_START_3 [0x02] trigger to 0x0001...")
                writeCharacteristicSequential(g, sensorCtrlChar, BleConstants.CMD_RAW_SENSOR_START_3)
                delay(60L)
            }
        }
    }

    fun disableSensorNotifications(gatt: BluetoothGatt? = bluetoothGatt) {
        val g = gatt ?: bluetoothGatt ?: run {
            Log.w(TAG, "disableSensorNotifications: bluetoothGatt is null")
            return
        }
        stopMotionWatchdog()
        actigraphyTickerJob?.cancel()
        actigraphyTickerJob = null
        hasPrevSample = false
        lastPacketIndex = -1
        smoothedActigraphy = 0.0f
        _deviceMetrics.value = _deviceMetrics.value.copy(
            isMotionStreaming = false,
            actigraphyG = 0.0f
        )

        scope.launch(Dispatchers.IO) {
            var huamiService = g.getService(BleConstants.UUID_SERVICE_HUAMI)
            var sensorDataChar = huamiService?.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_DATA)
            var sensorCtrlChar = huamiService?.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_CTRL)

            if (sensorDataChar == null || sensorCtrlChar == null) {
                for (s in g.services) {
                    if (sensorDataChar == null) sensorDataChar = s.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_DATA)
                    if (sensorCtrlChar == null) sensorCtrlChar = s.getCharacteristic(BleConstants.UUID_CHAR_SENSOR_CTRL)
                }
            }

            if (sensorCtrlChar != null) {
                Log.i(TAG, "Sending CMD_RAW_SENSOR_STOP [0x03] to 0x0001...")
                writeCharacteristicSequential(g, sensorCtrlChar, BleConstants.CMD_RAW_SENSOR_STOP)
                delay(60L)
            }

            if (sensorDataChar != null) {
                Log.i(TAG, "Disabling sensor data notification on 0x0002 sequentially...")
                disableNotificationSequential(g, sensorDataChar)
            }

            // Restore connection priority to BALANCED for battery power saving
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            Log.i(TAG, "Sensor stream stopped, connection priority restored to BALANCED")
        }
    }

    private fun startActigraphyTicker() {
        if (actigraphyTickerJob?.isActive == true) return
        actigraphyTickerJob = scope.launch(Dispatchers.Default) {
            while (isActive && _connectionState.value == BleConnectionState.CONNECTED && _deviceMetrics.value.isMotionStreaming) {
                delay(500L) // 2Hz smooth decay tick if user is motionless
                val now = System.currentTimeMillis()
                if (now - lastRawSensorPacketTimeMs > 400L && smoothedActigraphy > 0.002f) {
                    smoothedActigraphy = (smoothedActigraphy * 0.60f).coerceAtLeast(0.0f)
                    if (smoothedActigraphy < 0.003f) smoothedActigraphy = 0.0f
                    _deviceMetrics.value = _deviceMetrics.value.copy(
                        actigraphyG = smoothedActigraphy,
                        rawSensorPacketsCount = totalRawSensorPackets,
                        lastRawSampleX = lastSampleX,
                        lastRawSampleY = lastSampleY,
                        lastRawSampleZ = lastSampleZ
                    )
                    _actigraphyFlow.tryEmit(smoothedActigraphy)
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
            lastStepsCount = currentSteps
            // Actigraphy is 100% driven by raw sensor 0x0002; steps will NEVER touch actigraphy values.
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing realtime steps data", e)
        }
    }

    private fun parseActigraphyData(data: ByteArray) {
        if (data.isEmpty()) return
        lastRawSensorPacketTimeMs = System.currentTimeMillis()
        totalRawSensorPackets++

        if (totalRawSensorPackets <= 5L || totalRawSensorPackets % 50L == 1L) {
            val hex = data.take(16).joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "Raw sensor packet #$totalRawSensorPackets: size=${data.size}, data=[$hex]")
        }

        try {
            // Huami raw sensor packet format on 0x0002:
            // Byte 0: type (0x00 = Accelerometer, 0x01 = Optical PPG, 0x07 = Timestamp sync)
            // Byte 1: sequence counter (0..255)
            // Bytes 2+: 6-byte samples [rawX (2B), rawY (2B), rawZ (2B)]
            val type = data[0].toInt() and 0xFF
            if (data.size < 8 || type != 0x00 || (data.size - 2) % 6 != 0) {
                // Non-accelerometer raw telemetry (e.g. 0x07 timestamp sync packet or 0x01 optical packet)
                _deviceMetrics.value = _deviceMetrics.value.copy(
                    rawSensorPacketsCount = totalRawSensorPackets,
                    isMotionStreaming = true
                )
                return
            }

            // Verify packet sequence continuity (detect BLE packet drops)
            val packetIndex = data[1].toInt() and 0xFF
            if (lastPacketIndex >= 0) {
                val expectedIndex = (lastPacketIndex + 1) and 0xFF
                if (packetIndex != expectedIndex) {
                    // Packet drop detected: reset sample continuity so we don't compute bogus inter-packet delta
                    hasPrevSample = false
                }
            }
            lastPacketIndex = packetIndex

            var offset = 2
            var sumDeltaG = 0.0f
            var sampleCount = 0

            while (offset + 6 <= data.size) {
                // 16-bit signed integer extraction (little-endian)
                val rawX = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                val rawY = (data[offset + 2].toInt() and 0xFF) or ((data[offset + 3].toInt() and 0xFF) shl 8)
                val rawZ = (data[offset + 4].toInt() and 0xFF) or ((data[offset + 5].toInt() and 0xFF) shl 8)

                val x = rawX.toShort().toFloat()
                val y = rawY.toShort().toFloat()
                val z = rawZ.toShort().toFloat()

                lastSampleX = x
                lastSampleY = y
                lastSampleZ = z

                val mag = sqrt(x * x + y * y + z * z)
                if (mag > 500.0f) {
                    if (gravityScale <= 500.0f) {
                        gravityScale = mag
                    } else {
                        // Slowly calibrate 1g magnitude scale (~4096 LSB/g on Mi Band 6)
                        gravityScale = gravityScale * 0.999f + mag * 0.001f
                    }
                }

                if (hasPrevSample) {
                    val dx = x - prevSampleX
                    val dy = y - prevSampleY
                    val dz = z - prevSampleZ
                    val deltaMag = sqrt(dx * dx + dy * dy + dz * dz)
                    val deltaG = deltaMag / gravityScale.coerceAtLeast(1000.0f)

                    // Deadband thresholding: filter sensor thermal & quantization noise (~0.010g)
                    val motion = if (deltaG < 0.010f) 0.0f else (deltaG - 0.010f) * 1.25f
                    sumDeltaG += motion
                    sampleCount++
                }

                prevSampleX = x
                prevSampleY = y
                prevSampleZ = z
                hasPrevSample = true

                offset += 6
            }

            if (sampleCount > 0) {
                val packetMotion = sumDeltaG / sampleCount
                // Fast attack for instant reaction, smooth decay when motion stops
                smoothedActigraphy = if (packetMotion > smoothedActigraphy) {
                    smoothedActigraphy * 0.25f + packetMotion * 0.75f
                } else {
                    smoothedActigraphy * 0.70f + packetMotion * 0.30f
                }

                if (smoothedActigraphy < 0.005f) {
                    smoothedActigraphy = 0.0f
                }

                _deviceMetrics.value = _deviceMetrics.value.copy(
                    actigraphyG = smoothedActigraphy,
                    isMotionStreaming = true,
                    rawSensorPacketsCount = totalRawSensorPackets,
                    lastRawSampleX = lastSampleX,
                    lastRawSampleY = lastSampleY,
                    lastRawSampleZ = lastSampleZ
                )
                _actigraphyFlow.tryEmit(smoothedActigraphy)

                if (totalRawSensorPackets % 50L == 1L) {
                    Log.d(TAG, "Actigraphy #$totalRawSensorPackets: raw=($lastSampleX, $lastSampleY, $lastSampleZ), gScale=$gravityScale, motion=$smoothedActigraphy")
                }
            } else {
                _deviceMetrics.value = _deviceMetrics.value.copy(
                    rawSensorPacketsCount = totalRawSensorPackets,
                    isMotionStreaming = true
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing actigraphy sensor bytes", e)
        }
    }

    fun setHeartRateStreamingMode(isContinuous: Boolean) {
        if (_deviceMetrics.value.isHrStreaming == isContinuous) {
            return
        }
        if (isHrToggleBusy) {
            Log.w(TAG, "setHeartRateStreamingMode: toggle busy, skipping rapid invocation")
            return
        }
        val gatt = bluetoothGatt ?: run {
            Log.w(TAG, "setHeartRateStreamingMode failed: bluetoothGatt is null")
            return
        }
        isHrToggleBusy = true
        scope.launch(Dispatchers.IO) {
            try {
                var hrService = gatt.getService(BleConstants.UUID_SERVICE_HEART_RATE)
                var hrCtrlChar = hrService?.getCharacteristic(BleConstants.UUID_CHAR_HEART_RATE_CONTROL)
                var hrMeasChar = hrService?.getCharacteristic(BleConstants.UUID_CHAR_HEART_RATE_MEASUREMENT)

                if (hrCtrlChar == null || hrMeasChar == null) {
                    for (s in gatt.services) {
                        if (hrCtrlChar == null) hrCtrlChar = s.getCharacteristic(BleConstants.UUID_CHAR_HEART_RATE_CONTROL)
                        if (hrMeasChar == null) hrMeasChar = s.getCharacteristic(BleConstants.UUID_CHAR_HEART_RATE_MEASUREMENT)
                    }
                }

                if (isContinuous) {
                    Log.i(TAG, "Enabling continuous heart rate streaming...")
                    // 1. Ensure CCCD notification on 0x2A37 is active sequentially
                    if (hrMeasChar != null) {
                        enableNotificationSequential(gatt, hrMeasChar)
                    }
                    // 2. Ensure manual measurement is stopped first sequentially
                    if (hrCtrlChar != null) {
                        writeCharacteristicSequential(gatt, hrCtrlChar, byteArrayOf(0x15, 0x02, 0x00))
                        delay(60L)
                        // 3. Start continuous heart rate measurement sequentially (activates optical PPG engine & green LED)
                        writeCharacteristicSequential(gatt, hrCtrlChar, BleConstants.HR_START_CONTINUOUS)
                    }
                    _deviceMetrics.value = _deviceMetrics.value.copy(isHrStreaming = true)
                    startHrKeepAlive(gatt)
                } else {
                    Log.i(TAG, "Stopping continuous heart rate streaming...")
                    stopHrKeepAlive()
                    if (hrCtrlChar != null) {
                        writeCharacteristicSequential(gatt, hrCtrlChar, BleConstants.HR_STOP_CONTINUOUS)
                        delay(60L)
                        writeCharacteristicSequential(gatt, hrCtrlChar, byteArrayOf(0x15, 0x02, 0x00))
                    }
                    _deviceMetrics.value = _deviceMetrics.value.copy(isHrStreaming = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in setHeartRateStreamingMode", e)
            } finally {
                delay(300L) // Debounce window
                isHrToggleBusy = false
            }
        }
    }

    suspend fun enableNotificationSequential(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        timeoutMs: Long = 2500L
    ): Boolean = gattMutex.withLock {
        val descriptor = characteristic.getDescriptor(BleConstants.UUID_DESCRIPTOR_CCCD) ?: run {
            Log.w(TAG, "enableNotificationSequential: CCCD descriptor not found for ${characteristic.uuid}")
            return@withLock false
        }

        gatt.setCharacteristicNotification(characteristic, true)

        val cccdValue = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        val deferred = CompletableDeferred<Int>()
        descriptorDeferredMap[characteristic.uuid] = deferred

        val writeInitiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeDescriptor(descriptor, cccdValue)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = cccdValue
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }

        if (!writeInitiated) {
            Log.e(TAG, "enableNotificationSequential: writeDescriptor call rejected by Android BLE stack for ${characteristic.uuid}")
            descriptorDeferredMap.remove(characteristic.uuid)
            return@withLock false
        }

        val status = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }

        descriptorDeferredMap.remove(characteristic.uuid)

        if (status == null) {
            Log.w(TAG, "enableNotificationSequential: timed out waiting for onDescriptorWrite for ${characteristic.uuid}")
            return@withLock false
        }

        val success = (status == BluetoothGatt.GATT_SUCCESS)
        Log.i(TAG, "enableNotificationSequential: ${characteristic.uuid} CCCD written with status=$status (success=$success)")
        delay(60L) // Pacing delay for BLE radio
        return@withLock success
    }

    suspend fun disableNotificationSequential(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        timeoutMs: Long = 2500L
    ): Boolean = gattMutex.withLock {
        val descriptor = characteristic.getDescriptor(BleConstants.UUID_DESCRIPTOR_CCCD)
        if (descriptor == null) {
            gatt.setCharacteristicNotification(characteristic, false)
            return@withLock true
        }

        gatt.setCharacteristicNotification(characteristic, false)

        val cccdValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE

        val deferred = CompletableDeferred<Int>()
        descriptorDeferredMap[characteristic.uuid] = deferred

        val writeInitiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeDescriptor(descriptor, cccdValue)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = cccdValue
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }

        if (!writeInitiated) {
            descriptorDeferredMap.remove(characteristic.uuid)
            return@withLock false
        }

        val status = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }
        descriptorDeferredMap.remove(characteristic.uuid)
        delay(60L)
        return@withLock (status == BluetoothGatt.GATT_SUCCESS)
    }

    suspend fun writeCharacteristicSequential(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        timeoutMs: Long = 2500L
    ): Boolean = gattMutex.withLock {
        val writeType = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        characteristic.writeType = writeType

        val deferred = if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
            val d = CompletableDeferred<Int>()
            characteristicWriteDeferredMap[characteristic.uuid] = d
            d
        } else {
            null
        }

        val writeInitiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(characteristic, data, writeType)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

        if (!writeInitiated) {
            Log.e(TAG, "writeCharacteristicSequential: write call rejected by Android BLE stack for ${characteristic.uuid}")
            if (deferred != null) characteristicWriteDeferredMap.remove(characteristic.uuid)
            return@withLock false
        }

        if (deferred != null) {
            val status = withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
            characteristicWriteDeferredMap.remove(characteristic.uuid)
            if (status == null) {
                Log.w(TAG, "writeCharacteristicSequential: timed out waiting for onCharacteristicWrite for ${characteristic.uuid}")
                return@withLock false
            }
            val success = (status == BluetoothGatt.GATT_SUCCESS)
            val hex = data.joinToString(" ") { "%02X".format(it) }
            Log.i(TAG, "writeCharacteristicSequential: ${characteristic.uuid} data=[$hex] status=$status (success=$success)")
            delay(50L) // Radio pacing delay
            return@withLock success
        } else {
            delay(60L)
            return@withLock true
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

    fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, data: ByteArray, forceNoResponse: Boolean = false): Boolean {
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
            forceNoResponse || (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 -> {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 -> {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            else -> {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
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
     * Send CALL_START sequentially (with 25ms delay between chunks) to trigger motor vibration.
     * Matches Gadgetbridge MiBand6Support.java:40 onFindDevice implementation.
     */
    private suspend fun sendCallStart(callerName: String = "DreamAlarm") {
        if (use2021Protocol) {
            try {
                val nameBytes = callerName.toByteArray(Charsets.UTF_8)
                val callStartPayload = byteArrayOf(0x00, 0x00, 0xC0.toByte(), 0x00, 3, 0, 0, 0, 0, 0) +
                        nameBytes + byteArrayOf(0, 0, 0, 2)
                val chunks = chunkedEncoder.encode(
                    BleConstants.CHUNKED2021_ENDPOINT_COMPAT,
                    callStartPayload,
                    extendedFlags = true,
                    encrypt = true
                )
                for (chunk in chunks) {
                    writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_WRITE, chunk)
                    delay(25L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending 2021 call start vibration chunk", e)
            }
        } else {
            // Immediate Alert fallback for legacy devices
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
                }
            }
        }
    }

    /**
     * Send CALL_STOP sequentially (with 25ms delay between chunks) to stop motor vibration.
     */
    private suspend fun sendCallStop() {
        if (use2021Protocol) {
            try {
                val callStopPayload = byteArrayOf(0x00, 0x00, 0xC0.toByte(), 0x00, 3, 3, 0, 0, 0, 0)
                val chunks = chunkedEncoder.encode(
                    BleConstants.CHUNKED2021_ENDPOINT_COMPAT,
                    callStopPayload,
                    extendedFlags = true,
                    encrypt = true
                )
                for (chunk in chunks) {
                    writeCharacteristic(BleConstants.UUID_SERVICE_HUAMI, BleConstants.UUID_CHAR_CHUNKED_2021_WRITE, chunk)
                    delay(25L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending 2021 call stop vibration chunk", e)
            }
        } else {
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
    }

    private fun startMotorVibration(onTimeMs: Int = 300) {
        scope.launch(Dispatchers.IO) {
            sendCallStart()
        }
    }

    private fun stopMotorVibration() {
        scope.launch(Dispatchers.IO) {
            sendCallStop()
        }
    }

    fun triggerCustomVibration(
        pattern: CustomizableVibrationPattern,
        useTotalDuration: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        // Cancel any active vibration job cleanly without launching competing async writes
        vibrationJob?.cancel()
        vibrationJob = null

        val repeatCount = pattern.repeatCount.coerceIn(1, 10)
        val pulseMs = pattern.pulseMs.toLong().coerceIn(100L, 2000L)
        // Handshake/firmware call state machine requires >= 500ms interval between call end and next call start
        // to complete teardown animation and reset GSM debounce timer, otherwise subsequent calls are silently dropped.
        val pauseMs = pattern.pauseMs.toLong().coerceAtLeast(500L)
        val totalDurationMs = if (useTotalDuration) pattern.durationSeconds.toLong() * 1000L else 0L

        vibrationJob = scope.launch(Dispatchers.IO) {
            val wasVibrating = isVibrating
            isVibrating = true
            val startTime = System.currentTimeMillis()
            try {
                // If motor was previously vibrating, ensure it is cleanly stopped and settled first
                if (wasVibrating) {
                    sendCallStop()
                    delay(200L)
                }

                do {
                    for (r in 0 until repeatCount) {
                        if (!isActive) break

                        val currentPulseMs = when (pattern.type) {
                            PatternType.CRESCENDO -> (pulseMs * (0.6f + 0.4f * (r + 1) / repeatCount)).toLong()
                            else -> pulseMs
                        }

                        // 1. Send CALL_START sequentially (awaiting completion of all chunks)
                        sendCallStart()
                        // 2. Vibrate for exact pulse duration
                        delay(currentPulseMs)
                        // 3. Send CALL_STOP sequentially (awaiting completion of all chunks)
                        sendCallStop()

                        // 4. Inter-pulse pause: allow firmware call state machine to cleanly reset
                        if (r < repeatCount - 1) {
                            delay(pauseMs)
                        }
                    }

                    // If total duration is enabled, check remaining time before next burst cycle
                    if (useTotalDuration && isActive) {
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed + (repeatCount * (pulseMs + pauseMs)) <= totalDurationMs) {
                            // Inter-burst pause (1.5s gentle rest before next pattern cycle)
                            delay(1500L)
                        } else {
                            break
                        }
                    }
                } while (useTotalDuration && isActive && (System.currentTimeMillis() - startTime < totalDurationMs))
            } catch (e: CancellationException) {
                // Cancelled early by stopVibration(), guarantee cleanup
                withContext(NonCancellable) {
                    sendCallStop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in custom vibration job", e)
                withContext(NonCancellable) {
                    sendCallStop()
                }
            } finally {
                isVibrating = false
                onComplete?.invoke()
            }
        }
    }

    fun triggerCadenceVibration(cadenceType: VibrationCadenceType) {
        val pattern = VibrationCadenceProfiles.getPattern(cadenceType)
        stopVibration()

        vibrationJob = scope.launch(Dispatchers.IO) {
            isVibrating = true
            try {
                for (i in pattern.sequenceMs.indices) {
                    if (!isActive) break
                    val duration = pattern.sequenceMs[i]
                    val isVibrateStep = (i % 2 == 0)

                    if (isVibrateStep) {
                        sendCallStart()
                    } else {
                        sendCallStop()
                    }
                    delay(duration)
                }
            } finally {
                sendCallStop()
                isVibrating = false
            }
        }
    }

    fun stopVibration() {
        vibrationJob?.cancel()
        vibrationJob = null
        if (isVibrating) {
            stopMotorVibration()
        }
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
