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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Summary of EOG sensor data aggregated across a 30-second epoch window.
 */
data class EogEpochSummary(
    val burstCount30s: Int = 0,
    val hasClipping: Boolean = false,
    val isContactOk: Boolean = false,
    val avgEnergy: Float = 0f,
    val packetCount: Int = 0
)

/**
 * EogBleManager
 * Handles BLE communication with the ESP32-EOG peripheral device.
 * Service UUID: 0000ff20-0000-1000-8000-00805f9b34fb
 * Char UUID:    0000ff21-0000-1000-8000-00805f9b34fb (Notify)
 *
 * Packet format (6 bytes):
 * - Byte 0: status_flags (bit 0: contact_ok, bit 1: is_clipped, bit 2: saccade_now, bit 3: noise_warning)
 * - Byte 1: burst_count_sec (uint8)
 * - Byte 2-3: eog_energy (uint16 Big-Endian)
 * - Byte 4: packet_seq (uint8)
 * - Byte 5: battery_pct (uint8)
 */
@SuppressLint("MissingPermission")
class EogBleManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "EogBleManager"

        val UUID_SERVICE_EOG: UUID = UUID.fromString("0000ff20-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_EOG_DATA: UUID = UUID.fromString("0000ff21-0000-1000-8000-00805f9b34fb")
        val UUID_DESCRIPTOR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val EOG_WATCHDOG_TIMEOUT_MS = 6000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    private var targetMac: String = ""
    private var targetName: String = "FlashAlarm-EOG"
    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var isIntentionalDisconnect = false

    @Volatile
    var lastPacketReceivedTimeMs: Long = 0L
        private set
    private var watchdogJob: Job? = null

    // State flows
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _isContactOk = MutableStateFlow(false)
    val isContactOk: StateFlow<Boolean> = _isContactOk.asStateFlow()

    private val _isClipped = MutableStateFlow(false)
    val isClipped: StateFlow<Boolean> = _isClipped.asStateFlow()

    private val _isSaccadeNow = MutableStateFlow(false)
    val isSaccadeNow: StateFlow<Boolean> = _isSaccadeNow.asStateFlow()

    private val _noiseWarning = MutableStateFlow(false)
    val noiseWarning: StateFlow<Boolean> = _noiseWarning.asStateFlow()

    private val _currentBurstCount = MutableStateFlow(0)
    val currentBurstCount: StateFlow<Int> = _currentBurstCount.asStateFlow()

    private val _currentEnergy = MutableStateFlow(0)
    val currentEnergy: StateFlow<Int> = _currentEnergy.asStateFlow()

    private val _batteryPct = MutableStateFlow(100)
    val batteryPct: StateFlow<Int> = _batteryPct.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredBleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredBleDevice>> = _discoveredDevices.asStateFlow()

    // 30-second Epoch accumulator
    private val epochLock = Any()
    private var epochBurstSum: Int = 0
    private var epochHasClipping: Boolean = false
    private var epochContactOkCount: Int = 0
    private var epochEnergySum: Long = 0L
    private var epochPacketCount: Int = 0

    fun isDataFresh(maxAgeMs: Long = EOG_WATCHDOG_TIMEOUT_MS): Boolean {
        val lastTime = lastPacketReceivedTimeMs
        return lastTime > 0L && (System.currentTimeMillis() - lastTime) <= maxAgeMs
    }

    /**
     * Consume and reset the accumulated metrics for the past 30-second epoch.
     */
    fun consumeEpochSummary(): EogEpochSummary {
        synchronized(epochLock) {
            val count = epochPacketCount
            val summary = if (count > 0) {
                EogEpochSummary(
                    burstCount30s = epochBurstSum,
                    hasClipping = epochHasClipping,
                    isContactOk = (epochContactOkCount * 2) >= count, // Majority contact ok
                    avgEnergy = epochEnergySum.toFloat() / count,
                    packetCount = count
                )
            } else {
                EogEpochSummary(
                    burstCount30s = 0,
                    hasClipping = false,
                    isContactOk = false,
                    avgEnergy = 0f,
                    packetCount = 0
                )
            }
            // Reset for the next epoch
            epochBurstSum = 0
            epochHasClipping = false
            epochContactOkCount = 0
            epochEnergySum = 0L
            epochPacketCount = 0
            return summary
        }
    }

    private fun resetEpochAccumulator() {
        synchronized(epochLock) {
            epochBurstSum = 0
            epochHasClipping = false
            epochContactOkCount = 0
            epochEnergySum = 0L
            epochPacketCount = 0
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.IO) {
            while (isActive && _connectionState.value == BleConnectionState.CONNECTED) {
                delay(2000L)
                val now = System.currentTimeMillis()
                val lastTime = lastPacketReceivedTimeMs
                if (lastTime > 0L && (now - lastTime) > EOG_WATCHDOG_TIMEOUT_MS) {
                    if (_isContactOk.value || _isSaccadeNow.value) {
                        Log.w(TAG, "EOG Watchdog triggered: no packet for ${now - lastTime}ms (> ${EOG_WATCHDOG_TIMEOUT_MS}ms). Marking offline/stale.")
                        _isContactOk.value = false
                        _isSaccadeNow.value = false
                        _currentBurstCount.value = 0
                    }
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    fun setTargetDevice(mac: String, name: String = "FlashAlarm-EOG") {
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
            ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID_SERVICE_EOG)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            // Also scan unfiltered so devices with matching name match even if service is in ScanRecord
            scanner.startScan(null, settings, scanCallback)
            Log.d(TAG, "Started BLE scan for EOG devices")

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
                val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown EOG Device"
                val address = device.address ?: return
                val rssi = result.rssi

                val hasEogService = result.scanRecord?.serviceUuids?.any { it.uuid == UUID_SERVICE_EOG } ?: false
                val isMatchingName = name.contains("EOG", ignoreCase = true) ||
                        name.contains("Eye", ignoreCase = true) ||
                        name.contains("FlashAlarm-EOG", ignoreCase = true) ||
                        name.contains("ESP32", ignoreCase = true)

                if (hasEogService || isMatchingName) {
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
            Log.d(TAG, "Connecting to EOG device: ${device.name ?: "Unknown"} ($macAddress)")

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
            Log.e(TAG, "Failed to connect to EOG device", e)
            _connectionState.value = BleConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    fun disconnect() {
        isIntentionalDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        stopWatchdog()
        stopScan()
        _connectionState.value = BleConnectionState.DISCONNECTING

        val gattToClose = bluetoothGatt
        bluetoothGatt = null
        try {
            gattToClose?.disconnect()
            gattToClose?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting EOG GATT", e)
        }

        lastPacketReceivedTimeMs = 0L
        _connectionState.value = BleConnectionState.DISCONNECTED
        _isContactOk.value = false
        _isClipped.value = false
        _isSaccadeNow.value = false
        _noiseWarning.value = false
        _currentBurstCount.value = 0
        _currentEnergy.value = 0
        resetEpochAccumulator()
    }

    private fun scheduleReconnect() {
        if (isIntentionalDisconnect || targetMac.isBlank()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(5000L)
            if (isActive && !isIntentionalDisconnect && _connectionState.value == BleConnectionState.DISCONNECTED) {
                Log.d(TAG, "Attempting auto-reconnect to EOG device: $targetMac")
                connect(targetMac)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.CONNECTED
                Log.d(TAG, "EOG GATT Connected. Discovering services...")
                startWatchdog()
                gatt?.discoverServices()
            } else {
                stopWatchdog()
                lastPacketReceivedTimeMs = 0L
                _connectionState.value = BleConnectionState.DISCONNECTED
                _isContactOk.value = false
                _isClipped.value = false
                _isSaccadeNow.value = false
                _noiseWarning.value = false
                _currentBurstCount.value = 0
                _currentEnergy.value = 0
                resetEpochAccumulator()
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

            val eogService = gatt.getService(UUID_SERVICE_EOG)
            if (eogService == null) {
                Log.w(TAG, "Device does not contain EOG Service (${UUID_SERVICE_EOG})")
                return
            }

            val eogChar = eogService.getCharacteristic(UUID_CHAR_EOG_DATA)
            if (eogChar == null) {
                Log.w(TAG, "EOG Service missing characteristic (${UUID_CHAR_EOG_DATA})")
                return
            }

            // Enable local notifications
            gatt.setCharacteristicNotification(eogChar, true)

            // Write 0x2902 CCCD to enable remote notifications
            val descriptor = eogChar.getDescriptor(UUID_DESCRIPTOR_CCCD)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                Log.d(TAG, "Successfully enabled notifications for EOG characteristic")
            } else {
                Log.w(TAG, "Descriptor 0x2902 not found on EOG characteristic")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleEogPacket(characteristic, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            characteristic?.value?.let { value ->
                handleEogPacket(characteristic, value)
            }
        }
    }

    private fun handleEogPacket(characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        if (characteristic.uuid != UUID_CHAR_EOG_DATA || data.size < 6) return

        lastPacketReceivedTimeMs = System.currentTimeMillis()

        // Byte 0: status_flags (bit 0: contact_ok, bit 1: is_clipped, bit 2: saccade_now, bit 3: noise_warning)
        val flags = data[0].toInt() and 0xFF
        val contactOk = (flags and 0x01) != 0
        val isClipped = (flags and 0x02) != 0
        val saccadeNow = (flags and 0x04) != 0
        val noiseWarning = (flags and 0x08) != 0

        // Byte 1: burst_count_sec (uint8)
        val burstCount = data[1].toInt() and 0xFF

        // Byte 2-3: eog_energy (uint16 Big-Endian)
        val energy = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)

        // Byte 4: packet_seq (uint8)
        val seq = data[4].toInt() and 0xFF

        // Byte 5: battery_pct (uint8)
        val battery = data[5].toInt() and 0xFF

        // Update live flows
        _isContactOk.value = contactOk
        _isClipped.value = isClipped
        _isSaccadeNow.value = saccadeNow
        _noiseWarning.value = noiseWarning
        _currentBurstCount.value = burstCount
        _currentEnergy.value = energy
        _batteryPct.value = battery

        // Accumulate into 30-second Epoch window
        synchronized(epochLock) {
            epochPacketCount++
            epochBurstSum += burstCount
            if (isClipped) epochHasClipping = true
            if (contactOk) epochContactOkCount++
            epochEnergySum += energy
        }
    }
}
