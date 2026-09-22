package com.flashalarm.miband.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.flashalarm.miband.domain.model.BleConnectionState
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
import java.util.ArrayDeque
import java.util.UUID

@SuppressLint("MissingPermission")
class EcgBleManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "EcgBleManager"

        val UUID_SERVICE_HEART_RATE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_DESCRIPTOR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_RECENT_RR_COUNT = 60
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    private var targetMac: String = ""
    private var targetName: String = "FlashAlarm-ECG"
    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var isIntentionalDisconnect = false

    // State flows
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _isLeadsOff = MutableStateFlow(false)
    val isLeadsOff: StateFlow<Boolean> = _isLeadsOff.asStateFlow()

    private val _currentHeartRate = MutableStateFlow(0)
    val currentHeartRate: StateFlow<Int> = _currentHeartRate.asStateFlow()

    private val _lastRrMs = MutableStateFlow(0.0)
    val lastRrMs: StateFlow<Double> = _lastRrMs.asStateFlow()

    private val _recentRrList = MutableStateFlow<List<Double>>(emptyList())
    val recentRrList: StateFlow<List<Double>> = _recentRrList.asStateFlow()

    private val _rrIntervalFlow = MutableSharedFlow<Double>(extraBufferCapacity = 128)
    val rrIntervalFlow: SharedFlow<Double> = _rrIntervalFlow.asSharedFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredBleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredBleDevice>> = _discoveredDevices.asStateFlow()

    private val rrBuffer = ArrayDeque<Double>(MAX_RECENT_RR_COUNT)

    fun setTargetDevice(mac: String, name: String = "FlashAlarm-ECG") {
        targetMac = mac
        targetName = name
    }

    fun getTargetMac(): String = targetMac

    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "BluetoothLeScanner unavailable")
            return
        }
        if (_isScanning.value) return

        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID_SERVICE_HEART_RATE)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            // Also scan unfiltered so devices with name "FlashAlarm-ECG" or "ESP32" match even if service is in ScanRecord
            scanner.startScan(null, settings, scanCallback)
            Log.d(TAG, "Started BLE scan for ECG devices")

            // Auto-stop scan after 20 seconds to prevent battery drain
            scanTimeoutJob?.cancel()
            scanTimeoutJob = scope.launch(Dispatchers.Main) {
                delay(20000L)
                if (_isScanning.value) {
                    Log.d(TAG, "BLE scan timeout reached (20s). Auto-stopping scan.")
                    stopScan()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            _isScanning.value = false
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        if (!_isScanning.value) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        try {
            scanner?.stopScan(scanCallback)
            Log.d(TAG, "Stopped BLE scan")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan", e)
        }
        _isScanning.value = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown HR Device"
                val address = device.address ?: return
                val rssi = result.rssi

                // Filter for relevant heart rate or ECG devices
                val hasHrService = result.scanRecord?.serviceUuids?.any { it.uuid == UUID_SERVICE_HEART_RATE } ?: false
                val isMatchingName = name.contains("ECG", ignoreCase = true) ||
                        name.contains("Heart", ignoreCase = true) ||
                        name.contains("ESP32", ignoreCase = true) ||
                        name.contains("AD8232", ignoreCase = true) ||
                        name.contains("Polar", ignoreCase = true)

                if (hasHrService || isMatchingName) {
                    val current = _discoveredDevices.value.toMutableList()
                    val idx = current.indexOfFirst { it.address == address }
                    val item = DiscoveredBleDevice(name, address, rssi)
                    if (idx >= 0) {
                        current[idx] = item
                    } else {
                        current.add(item)
                    }
                    _discoveredDevices.value = current.sortedByDescending { it.rssi }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            _isScanning.value = false
        }
    }

    fun connect(macAddress: String = targetMac) {
        if (macAddress.isBlank()) {
            Log.w(TAG, "Cannot connect: empty MAC address")
            return
        }
        targetMac = macAddress
        isIntentionalDisconnect = false
        reconnectJob?.cancel()

        val adapter = bluetoothAdapter ?: run {
            Log.w(TAG, "BluetoothAdapter unavailable")
            return
        }

        try {
            val device = adapter.getRemoteDevice(macAddress)
            _connectionState.value = BleConnectionState.CONNECTING
            Log.d(TAG, "Connecting to ECG device: ${device.name ?: "Unknown"} ($macAddress)")

            val oldGatt = bluetoothGatt
            bluetoothGatt = null
            oldGatt?.let {
                try {
                    it.disconnect()
                    it.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing previous GATT", e)
                }
            }

            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ECG device", e)
            _connectionState.value = BleConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    fun disconnect() {
        isIntentionalDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        stopScan()
        _connectionState.value = BleConnectionState.DISCONNECTING

        val gattToClose = bluetoothGatt
        bluetoothGatt = null
        try {
            gattToClose?.disconnect()
            gattToClose?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting ECG GATT", e)
        }

        _connectionState.value = BleConnectionState.DISCONNECTED
        _isLeadsOff.value = false
        _currentHeartRate.value = 0
        _lastRrMs.value = 0.0
        synchronized(rrBuffer) {
            rrBuffer.clear()
            _recentRrList.value = emptyList()
        }
    }

    private fun scheduleReconnect() {
        if (isIntentionalDisconnect || targetMac.isBlank()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(5000L)
            if (isActive && !isIntentionalDisconnect && _connectionState.value == BleConnectionState.DISCONNECTED) {
                Log.d(TAG, "Attempting auto-reconnect to ECG device: $targetMac")
                connect(targetMac)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.CONNECTED
                Log.d(TAG, "ECG GATT Connected. Discovering services...")
                gatt?.discoverServices()
            } else {
                _connectionState.value = BleConnectionState.DISCONNECTED
                _isLeadsOff.value = false
                _currentHeartRate.value = 0
                if (bluetoothGatt === gatt) {
                    bluetoothGatt = null
                }
                try {
                    gatt?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing gatt on disconnect", e)
                }
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                Log.w(TAG, "Service discovery failed with status $status")
                return
            }

            val hrService = gatt.getService(UUID_SERVICE_HEART_RATE)
            if (hrService == null) {
                Log.w(TAG, "Device does not contain standard Heart Rate Service (0x180D)")
                return
            }

            val hrChar = hrService.getCharacteristic(UUID_CHAR_HR_MEASUREMENT)
            if (hrChar == null) {
                Log.w(TAG, "Heart Rate Service missing 0x2A37 measurement characteristic")
                return
            }

            // Enable local notifications
            gatt.setCharacteristicNotification(hrChar, true)

            // Write 0x2902 CCCD to enable remote notifications
            val descriptor = hrChar.getDescriptor(UUID_DESCRIPTOR_CCCD)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                Log.d(TAG, "Successfully enabled notifications for 0x2A37 HR Measurement")
            } else {
                Log.w(TAG, "Descriptor 0x2902 not found on 0x2A37")
            }
        }

        // For Android 13 (Tiramisu, API 33) and above
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleHeartRatePacket(characteristic, value)
        }

        // For Android 12 and below
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            characteristic?.value?.let { value ->
                handleHeartRatePacket(characteristic, value)
            }
        }
    }

    private fun handleHeartRatePacket(characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        if (characteristic.uuid != UUID_CHAR_HR_MEASUREMENT) return

        val packet = StandardHrPacketParser.parse(data) ?: return

        _currentHeartRate.value = packet.heartRateBpm
        _isLeadsOff.value = packet.isLeadsOff

        if (packet.rrIntervalsMs.isNotEmpty()) {
            synchronized(rrBuffer) {
                for (rr in packet.rrIntervalsMs) {
                    _lastRrMs.value = rr
                    _rrIntervalFlow.tryEmit(rr)

                    if (rrBuffer.size >= MAX_RECENT_RR_COUNT) {
                        rrBuffer.removeFirst()
                    }
                    rrBuffer.addLast(rr)
                }
                _recentRrList.value = rrBuffer.toList()
            }
        }
    }
}
