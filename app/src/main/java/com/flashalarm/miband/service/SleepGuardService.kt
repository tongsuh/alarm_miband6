package com.flashalarm.miband.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.flashalarm.miband.FlashAlarmApp
import com.flashalarm.miband.MainActivity
import com.flashalarm.miband.R
import com.flashalarm.miband.domain.model.DualEngineState
import com.flashalarm.miband.domain.model.RemStagingResult
import com.flashalarm.miband.domain.model.SleepSessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ActiveCueState(
    val cueId: Long,
    val sessionId: Long,
    val triggerTimeMs: Long,
    val cadenceName: String,
    val isAcknowledged: Boolean = false
)

class SleepGuardService : Service() {

    companion object {
        private const val TAG = "SleepGuardService"
        private const val CHANNEL_ID = "flashalarm_sleep_guard"
        private const val NOTIFICATION_ID = 9001

        const val ACTION_START_GUARD = "com.flashalarm.miband.START_GUARD"
        const val ACTION_STOP_GUARD = "com.flashalarm.miband.STOP_GUARD"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _currentSessionId = MutableStateFlow<Long?>(null)
        val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()

        private val _liveStaging = MutableStateFlow<RemStagingResult?>(null)
        val liveStaging: StateFlow<RemStagingResult?> = _liveStaging.asStateFlow()

        private val _activeCue = MutableStateFlow<ActiveCueState?>(null)
        val activeCue: StateFlow<ActiveCueState?> = _activeCue.asStateFlow()

        private val _dualEngineState = MutableStateFlow<DualEngineState?>(null)
        val dualEngineState: StateFlow<DualEngineState?> = _dualEngineState.asStateFlow()

        /**
         * Acknowledge and dismiss the currently playing dream cue immediately (<5ms latency):
         * 1. Terminates wrist motor vibration sequentially
         * 2. Stops audio playback
         * 3. Marks cue as acknowledged in database
         */
        fun acknowledgeActiveCue(app: FlashAlarmApp): Boolean {
            val current = _activeCue.value ?: return false
            if (current.isAcknowledged) return false

            // Immediate physical cutoff
            app.bleManager.stopVibration()
            app.audioPlayer.stopAudio()

            // Asynchronous DB persist
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    app.sleepRepository.markCueAcknowledged(current.cueId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed marking cue acknowledged in DB", e)
                }
            }

            _activeCue.value = current.copy(isAcknowledged = true)

            // Auto reset after 3.5 seconds so UI feedback shows success, then resets
            CoroutineScope(Dispatchers.Main).launch {
                delay(3500L)
                if (_activeCue.value?.cueId == current.cueId) {
                    _activeCue.value = null
                }
            }
            return true
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var wakeLock: PowerManager.WakeLock? = null
    private var epochCollectorJob: Job? = null

    // Cache latest values for epoch evaluation
    private var lastHeartRate = 60
    private var lastHeartRateReceivedTimeMs = 0L
    private var lastBandReconnectAttemptMs = 0L
    private val epochHeartRateSamples = mutableListOf<Int>()
    private var lastActigraphy = 0.0f
    private val epochActigraphySamples = mutableListOf<Float>()
    private var currentActiveSessionId: Long = 0L
    @Volatile
    var isEcgLatchedOff: Boolean = false
    @Volatile
    var isShadowWarmingUp: Boolean = false
    @Volatile
    var leadsOffStartTimeMs: Long = 0L

    inner class LocalBinder : Binder() {
        fun getService(): SleepGuardService = this@SleepGuardService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()

        val app = applicationContext as? FlashAlarmApp
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val enableAudio = app?.userPreferencesRepository?.cueConfig?.value?.enableAudioVerification == true && hasAudioPermission

        // Immediate promotion to foreground service in onCreate to guarantee system 5-second FGS contract
        val notification = buildNotification("正在监测睡眠体动与心率...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (enableAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting foreground service in onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_GUARD -> {
                stopSleepGuard()
            }
            else -> {
                startSleepGuard()
            }
        }
        return START_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FlashAlarm:SleepGuardWakeLock"
        )
        wakeLock?.acquire()
    }

    private fun startSleepGuard() {
        if (_isServiceRunning.value) return

        isEcgLatchedOff = false
        isShadowWarmingUp = false
        leadsOffStartTimeMs = 0L
        lastBandReconnectAttemptMs = 0L
        _dualEngineState.value = null

        val app = applicationContext as FlashAlarmApp
        val notification = buildNotification("正在监测睡眠体动与心率...")

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val enableAudio = app.userPreferencesRepository.cueConfig.value.enableAudioVerification && hasAudioPermission

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (enableAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground update failed", e)
        }

        _isServiceRunning.value = true

        serviceScope.launch {
            // 1. Create session in DB
            val sessionId = app.sleepRepository.createSession()
            currentActiveSessionId = sessionId
            _currentSessionId.value = sessionId

            // 2. Start REM Engine
            val config = app.userPreferencesRepository.cueConfig.value
            app.remEngine.updateConfig(config)
            app.remEngine.startSession()

            // 3. Connect BLE ONLY if not already connected (do not disconnect active session!)
            val prefs = app.userPreferencesRepository
            val targetMac = prefs.getDeviceMac()
            val authKey = prefs.getAuthKeyHex()
            val use2021 = prefs.getUse2021Protocol()
            if (targetMac.isNotBlank()) {
                if (app.bleManager.connectionState.value != com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED) {
                    app.bleManager.setTargetDevice("Mi Smart Band 6", targetMac, authKey, use2021)
                    app.bleManager.startScanAndConnect(targetMac)
                }
            }

            // 3.5 Connect AD8232 / ESP32-C3 BLE if Dual-Modality or 1Hz+HRV configured
            val ecgMac = prefs.getEcgMac()
            val isDualMode = config.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL
            val isMlMode = config.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.ML_MODEL
            isEcgLatchedOff = false
            isShadowWarmingUp = false
            leadsOffStartTimeMs = 0L
            if ((isDualMode || isMlMode) && ecgMac.isNotBlank()) {
                val isAlreadyConnected = app.ecgBleManager.connectionState.value == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED
                val isLeadsOff = app.ecgBleManager.isLeadsOff.value
                if (isDualMode) {
                    if (isAlreadyConnected && !isLeadsOff) {
                        isShadowWarmingUp = false
                        app.remEngine.setShadowPreWarming(false)
                        _dualEngineState.value = DualEngineState.ECG_PRIMARY
                    } else {
                        isShadowWarmingUp = true
                        app.remEngine.setShadowPreWarming(true)
                        _dualEngineState.value = DualEngineState.SHADOW_PREWARMING
                    }
                } else {
                    _dualEngineState.value = null
                    if (isAlreadyConnected && !isLeadsOff) {
                        app.remEngine.onBleConnected()
                    }
                }
                if (!isAlreadyConnected) {
                    app.ecgBleManager.setTargetDevice(ecgMac)
                    app.ecgBleManager.connect(ecgMac)
                }
            } else {
                _dualEngineState.value = null
            }

            // 3.6 Connect ESP32-EOG BLE if EOG_ASSISTED_AI configured
            val eogMac = config.eogMacAddress.ifBlank { prefs.getEogMac() }
            val isEogMode = config.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.EOG_ASSISTED_AI
            if (isEogMode && eogMac.isNotBlank()) {
                val isAlreadyConnected = app.eogBleManager.connectionState.value == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED
                if (!isAlreadyConnected) {
                    app.eogBleManager.setTargetDevice(eogMac)
                    app.eogBleManager.connect(eogMac)
                }
            }

            // 4. Start Audio Analyzer if configured and permitted
            if (enableAudio) {
                try {
                    app.audioAnalyzer.startAnalysis()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed starting audio analyzer", e)
                }
            }

            // 5. Collect incoming sensor flows (1Hz continuous HR, 25Hz actigraphy & ECG R-R)
            launch {
                app.bleManager.heartRateFlow.collect { hr ->
                    lastHeartRate = hr
                    lastHeartRateReceivedTimeMs = System.currentTimeMillis()
                    synchronized(epochHeartRateSamples) {
                        epochHeartRateSamples.add(hr)
                    }
                }
            }
            launch {
                app.bleManager.actigraphyFlow.collect { act ->
                    lastActigraphy = act
                    app.remEngine.pushActigraphy(act.toDouble())
                    synchronized(epochActigraphySamples) {
                        epochActigraphySamples.add(act)
                    }
                }
            }
            launch {
                app.ecgBleManager.rrIntervalFlow.collect { rrMs ->
                    val leadsOff = app.ecgBleManager.isLeadsOff.value
                    if (!isEcgLatchedOff) {
                        app.remEngine.pushRrInterval(rrMs, isHardwareLeadsOff = leadsOff)
                    }
                }
            }

            // 6. Ensure continuous high-frequency sensor streaming for Mi Band 6 (Hot Standby all night)
            app.bleManager.setHeartRateStreamingMode(true)
            app.bleManager.enableSensorNotifications()

            // 6.5 Failover & State Machine for Dual-Modality (ECG_PRIMARY / SHADOW_PREWARMING / LATCH_BAND)
            // Track leads-off flow events for rapid 30s debounce initiation / recovery
            launch {
                app.ecgBleManager.isLeadsOff.collect { leadsOff ->
                    val currentCfg = app.userPreferencesRepository.cueConfig.value
                    val dualActive = currentCfg.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL
                    if (dualActive && !isEcgLatchedOff) {
                        if (leadsOff) {
                            if (leadsOffStartTimeMs == 0L) {
                                leadsOffStartTimeMs = System.currentTimeMillis()
                                Log.d(TAG, "ECG leads-off detected. Starting 30s debounce timer.")
                            }
                        } else {
                            if (leadsOffStartTimeMs != 0L) {
                                Log.d(TAG, "ECG leads-off cleared. Resetting debounce timer.")
                            }
                            leadsOffStartTimeMs = 0L
                        }
                    }
                }
            }

            // Listen for ECG BLE connection events
            launch {
                app.ecgBleManager.connectionState.collect { connState ->
                    val currentCfg = app.userPreferencesRepository.cueConfig.value
                    val dualActive = currentCfg.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL
                    if (connState == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED) {
                        app.remEngine.onBleConnected()
                    } else if (connState == com.flashalarm.miband.domain.model.BleConnectionState.DISCONNECTED) {
                        app.remEngine.onBleDisconnected()
                    }
                    if (dualActive && !isEcgLatchedOff) {
                        if (connState == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED) {
                            val isLeadsOff = app.ecgBleManager.isLeadsOff.value
                            if (!isLeadsOff) {
                                Log.i(TAG, "ECG connected/reconnected! Entering SHADOW_PREWARMING.")
                                isShadowWarmingUp = true
                                app.remEngine.setShadowPreWarming(true)
                                _dualEngineState.value = DualEngineState.SHADOW_PREWARMING
                                app.remEngine.onLeadsOff(isConfirmed = false)
                            }
                        } else {
                            Log.w(TAG, "ECG disconnected! Setting SHADOW_PREWARMING.")
                            isShadowWarmingUp = true
                            app.remEngine.setShadowPreWarming(true)
                            _dualEngineState.value = DualEngineState.SHADOW_PREWARMING
                        }
                    }
                }
            }

            // Periodic 1s watchdog for debounced leads-off & state machine transitions
            launch {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    app.remEngine.onWatchdogTick(now)

                    val currentCfg = app.userPreferencesRepository.cueConfig.value
                    val dualActive = currentCfg.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL
                    val mlActive = currentCfg.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.ML_MODEL
                    val ecgTarget = currentCfg.ad8232MacAddress.ifBlank { ecgMac }

                    // Mi Band 6 disconnect self-healing watchdog with 10s debounce
                    val bandTargetMac = prefs.getDeviceMac()
                    if (_isServiceRunning.value && bandTargetMac.isNotBlank()) {
                        val bandConnState = app.bleManager.connectionState.value
                        if (bandConnState == com.flashalarm.miband.domain.model.BleConnectionState.DISCONNECTED) {
                            if (now - lastBandReconnectAttemptMs >= 10_000L) {
                                lastBandReconnectAttemptMs = now
                                Log.w(TAG, "Watchdog detected Mi Band disconnected while service active. Triggering auto-reconnect to $bandTargetMac (10s debounce)...")
                                val authKey = prefs.getAuthKeyHex()
                                val use2021 = prefs.getUse2021Protocol()
                                app.bleManager.setTargetDevice("Mi Smart Band 6", bandTargetMac, authKey, use2021)
                                app.bleManager.startScanAndConnect(bandTargetMac)
                            }
                        }
                    }

                    if (mlActive && ecgTarget.isNotBlank()) {
                        val connState = app.ecgBleManager.connectionState.value
                        if (connState == com.flashalarm.miband.domain.model.BleConnectionState.DISCONNECTED) {
                            app.ecgBleManager.connect(ecgTarget)
                        }
                    }

                    val isEogActive = currentCfg.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.EOG_ASSISTED_AI
                    val eogTarget = currentCfg.eogMacAddress.ifBlank { prefs.getEogMac() }
                    if (isEogActive && eogTarget.isNotBlank()) {
                        val connState = app.eogBleManager.connectionState.value
                        if (connState == com.flashalarm.miband.domain.model.BleConnectionState.DISCONNECTED) {
                            app.eogBleManager.connect(eogTarget)
                        }
                    }

                    if (dualActive && ecgTarget.isNotBlank()) {
                        if (isEcgLatchedOff) {
                            // If isEcgLatchedOff: keep running on Mi Band without reconnecting 8232.
                            _dualEngineState.value = DualEngineState.LATCH_BAND
                            if (app.ecgBleManager.connectionState.value == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED) {
                                app.ecgBleManager.disconnect()
                            }
                        } else {
                            val isEcgConnected = app.ecgBleManager.connectionState.value == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED
                            val isLeadsOff = app.ecgBleManager.isLeadsOff.value
                            val now = System.currentTimeMillis()

                            // Implement 30s debounce on leadsOff == true
                            if (isLeadsOff) {
                                if (leadsOffStartTimeMs == 0L) {
                                    leadsOffStartTimeMs = now
                                } else if (now - leadsOffStartTimeMs >= 30_000L && !isEcgLatchedOff) {
                                    isEcgLatchedOff = true
                                    isShadowWarmingUp = false
                                    app.remEngine.setShadowPreWarming(false)
                                    app.remEngine.onLeadsOff(isConfirmed = true)
                                    app.ecgBleManager.disconnect()
                                    _dualEngineState.value = DualEngineState.LATCH_BAND
                                    Log.w(TAG, "ECG electrode confirmed detached (>30s). One-Way Latch fallback to Mi Band activated.")
                                }
                            } else {
                                leadsOffStartTimeMs = 0L
                            }

                            if (!isEcgLatchedOff) {
                                if (!isEcgConnected) {
                                    // If ECG BLE is disconnected (e.g., user went out of range): set isShadowWarmingUp = true, app.remEngine.setShadowPreWarming(true)
                                    isShadowWarmingUp = true
                                    app.remEngine.setShadowPreWarming(true)
                                    _dualEngineState.value = DualEngineState.SHADOW_PREWARMING
                                } else if (!isLeadsOff) {
                                    // If ECG BLE is connected and !isLeadsOff:
                                    if (isShadowWarmingUp) {
                                        _dualEngineState.value = DualEngineState.SHADOW_PREWARMING
                                        // Check if app.remEngine.isHrvBufferFull() is true and app.ecgBleManager.isDataFresh(6000L) and app.ecgBleManager.currentHeartRate.value > 0
                                        if (app.remEngine.isHrvBufferFull() &&
                                            app.ecgBleManager.isDataFresh(6000L) &&
                                            app.ecgBleManager.currentHeartRate.value > 0
                                        ) {
                                            isShadowWarmingUp = false
                                            app.remEngine.setShadowPreWarming(false)
                                            _dualEngineState.value = DualEngineState.ECG_PRIMARY
                                            Log.i(TAG, "Shadow Pre-warming complete! 21 epochs full. Hot-switched back to AD8232.")
                                        }
                                    } else {
                                        _dualEngineState.value = DualEngineState.ECG_PRIMARY
                                    }
                                }
                            }
                        }
                    } else {
                        _dualEngineState.value = null
                    }
                    delay(1000L)
                }
            }

            // 7. Start 30-second epoch loop
            startEpochLoop(app, sessionId)
        }
    }

    private fun startEpochLoop(app: FlashAlarmApp, sessionId: Long) {
        epochCollectorJob?.cancel()
        epochCollectorJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive && _isServiceRunning.value) {
                delay(30000L) // 30-second epoch tick

                val (epochAvgAct, epochMaxAct) = synchronized(epochActigraphySamples) {
                    val avg = if (epochActigraphySamples.isNotEmpty()) epochActigraphySamples.average().toFloat() else lastActigraphy
                    val max = if (epochActigraphySamples.isNotEmpty()) epochActigraphySamples.maxOrNull() ?: lastActigraphy else lastActigraphy
                    epochActigraphySamples.clear()
                    Pair(avg, max)
                }

                val (epochMeanHr, epochHrStdDev) = synchronized(epochHeartRateSamples) {
                    val count = epochHeartRateSamples.size
                    val mean = if (count > 0) epochHeartRateSamples.average().toFloat() else -1f
                    val stdDev = if (count > 3 && mean > 0f) {
                        val variance = epochHeartRateSamples.map { (it - mean) * (it - mean) }.average().toFloat()
                        kotlin.math.sqrt(variance)
                    } else 0f
                    epochHeartRateSamples.clear()
                    Pair(mean, stdDev)
                }

                val now = System.currentTimeMillis()
                val currentCfg = app.userPreferencesRepository.cueConfig.value
                val isDualActive = currentCfg.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL
                val dualState = if (isDualActive) _dualEngineState.value else null

                val isEcgConnected = app.ecgBleManager.connectionState.value == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED
                val isLeadsOff = app.ecgBleManager.isLeadsOff.value
                val ecgHr = app.ecgBleManager.currentHeartRate.value
                val isEcgFresh = app.ecgBleManager.isDataFresh(6000L)
                val isEcgActiveAndHealthy = isDualActive && isEcgConnected && !isLeadsOff && (ecgHr in 36..200) && isEcgFresh

                val isHrFresh = lastHeartRateReceivedTimeMs > 0L && (now - lastHeartRateReceivedTimeMs) < 45000L
                val bandEvaluatedHr = if (isHrFresh && epochMeanHr > 0f) {
                    epochMeanHr.toInt().coerceIn(36, 220)
                } else {
                    -1
                }

                val evaluatedHr = if (!isEcgLatchedOff && !isShadowWarmingUp && isEcgActiveAndHealthy && ecgHr > 0) {
                    ecgHr
                } else {
                    bandEvaluatedHr
                }

                val isEcgPrimary = !isEcgLatchedOff && !isShadowWarmingUp && isEcgActiveAndHealthy

                val audioState = app.audioAnalyzer.state.value
                val isEogActiveEpoch = currentCfg.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.EOG_ASSISTED_AI
                val eogSummary = app.eogBleManager.consumeEpochSummary()
                val stagingResult = app.remEngine.evaluateEpoch(
                    heartRate = evaluatedHr,
                    actigraphyMagnitude = epochAvgAct,
                    audioIrregularity = if (audioState.isAudioReliable) audioState.irregularityScore else -1.0f,
                    isAudioReliable = audioState.isAudioReliable,
                    currentTimeMs = now,
                    peakActigraphy = epochMaxAct,
                    intraEpochHrStdDev = if (evaluatedHr > 0) epochHrStdDev else 0f,
                    isEcgPrimary = isEcgPrimary,
                    eogBursts = if (isEogActiveEpoch) eogSummary.burstCount30s else 0,
                    isEogContactOk = if (isEogActiveEpoch) eogSummary.isContactOk else false,
                    isEogClipped = if (isEogActiveEpoch) eogSummary.hasClipping else false
                )

                _liveStaging.value = stagingResult

                // Maintain continuous 1Hz heart rate streaming throughout all sleep phases (Mi Band PPG Hot Standby Watchdog)
                val hrIdleMs = if (lastHeartRateReceivedTimeMs > 0L) now - lastHeartRateReceivedTimeMs else 0L
                if (_isServiceRunning.value && app.bleManager.connectionState.value == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED) {
                    if (hrIdleMs > 75000L) {
                        if (now - lastBandReconnectAttemptMs >= 10_000L) {
                            lastBandReconnectAttemptMs = now
                            Log.e(TAG, "Mi Band HR stream stalled for ${hrIdleMs}ms (>75s). Underlying GATT client appears locked. Triggering silent reconnect (10s debounce)...")
                            app.bleManager.reconnectSilently()
                        }
                    } else if (hrIdleMs > 45000L) {
                        Log.w(TAG, "Mi Band HR stream quiet for ${hrIdleMs}ms (>45s). Stage 1 recovery: sending soft refresh...")
                        app.bleManager.setHeartRateStreamingMode(true, force = true)
                    }
                }

                // Record epoch in DB (record peak actigraphy so movement spikes are faithfully captured)
                app.sleepRepository.recordEpoch(
                    sessionId = sessionId,
                    timestamp = stagingResult.timestamp,
                    stage = stagingResult.stage,
                    heartRate = evaluatedHr,
                    actigraphy = epochMaxAct,
                    audioIrregularity = stagingResult.audioIrregularity,
                    confidence = stagingResult.confidence
                )

                // If Lucid Dream Cue triggered!
                if (stagingResult.isDreamCueTriggered) {
                    val config = app.userPreferencesRepository.cueConfig.value
                    val activePattern = config.getActivePattern()
                    Log.i(TAG, "LUCID DREAM CUE TRIGGERED! Pattern: ${activePattern.name}")

                    // 1. Dispatch Wrist Motor Vibration (if enabled)
                    if (config.enableWristVibration) {
                        app.bleManager.triggerCustomVibration(activePattern, useTotalDuration = true)
                    }

                    // 2. Dispatch Audio Whisper Cue (if enabled)
                    if (config.enableAudioPlayback) {
                        app.audioPlayer.playCueAudio(
                            filePath = config.customAudioPath,
                            durationSeconds = config.audioDurationSeconds,
                            volumePercent = config.audioVolumePercent
                        )
                    }

                    // 3. Record cue event
                    val methodDescription = buildString {
                        if (config.enableWristVibration) append("手环微震[${activePattern.name}] ")
                        if (config.enableAudioPlayback) append("手机音频[${config.customAudioName}]")
                    }.trim()

                    val cueId = app.sleepRepository.recordCue(
                        sessionId = sessionId,
                        timestamp = stagingResult.timestamp,
                        cadenceName = methodDescription.ifBlank { activePattern.name },
                        confidence = stagingResult.confidence,
                        heartRate = if (evaluatedHr > 0) evaluatedHr else lastHeartRate,
                        triggerReason = stagingResult.triggerReason
                    )

                    _activeCue.value = ActiveCueState(
                        cueId = cueId,
                        sessionId = sessionId,
                        triggerTimeMs = stagingResult.timestamp,
                        cadenceName = methodDescription.ifBlank { activePattern.name }
                    )

                    serviceScope.launch {
                        val windowSec = kotlin.math.max(
                            if (config.enableWristVibration) activePattern.durationSeconds else 0,
                            if (config.enableAudioPlayback) config.audioDurationSeconds else 0
                        ).coerceAtLeast(10)
                        delay((windowSec + 5) * 1000L)
                        val currentCue = _activeCue.value
                        if (currentCue?.cueId == cueId && !currentCue.isAcknowledged) {
                            _activeCue.value = null
                        }
                    }

                    updateNotification("✨ 黄金触梦已激发 ($methodDescription | 置信度 ${(stagingResult.confidence * 100).toInt()}%)")
                } else {
                    val stateTag = if (isDualActive) {
                        when (dualState) {
                            DualEngineState.ECG_PRIMARY -> "🫀心电双模"
                            DualEngineState.SHADOW_PREWARMING -> "⏳心电预热(${app.remEngine.getHrvBufferSize()}/21)"
                            DualEngineState.LATCH_BAND -> "🔒手环锁存"
                            null -> ""
                        }
                    } else if (currentCfg.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.ML_MODEL) {
                        val w = app.remEngine.getHrvGainWeight()
                        val hrvState = app.remEngine.getHrvAdaptationState()
                        if (w > 0.0f) {
                            "🫀1Hz+HRV增益(${(w * 100).toInt()}%)"
                        } else if (hrvState == com.flashalarm.miband.domain.algorithm.HrvAdaptationController.State.CONTACT_DEBOUNCING) {
                            "⏳HRV校准中"
                        } else {
                            "📱1Hz基座"
                        }
                    } else if (currentCfg.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.EOG_ASSISTED_AI) {
                        val eogQuality = app.remEngine.eogController.signalQuality
                        when (eogQuality) {
                            com.flashalarm.miband.domain.algorithm.EogSignalQuality.CLEAN_BURSTING -> "👁️EOG活跃(+${"%.1f".format(app.remEngine.eogController.currentLogitBoost)})"
                            com.flashalarm.miband.domain.algorithm.EogSignalQuality.CLEAN_RESTING -> "👁️EOG静息"
                            com.flashalarm.miband.domain.algorithm.EogSignalQuality.NOISY_SATURATED -> "⚠️EOG伪迹"
                            com.flashalarm.miband.domain.algorithm.EogSignalQuality.LEADS_OFF -> "⚠️EOG脱落"
                            com.flashalarm.miband.domain.algorithm.EogSignalQuality.OFFLINE -> "👁️EOG未连"
                        }
                    } else {
                        ""
                    }
                    val phaseDesc = when (stagingResult.sessionPhase) {
                        SleepSessionPhase.DETECTING_ONSET -> "监测入睡中"
                        SleepSessionPhase.PROTECTION_PERIOD -> "深睡保护期(余${stagingResult.protectionRemainingMinutes}分)"
                        SleepSessionPhase.DREAM_WINDOW_ACTIVE -> "触梦雷达全程开启"
                    }
                    val extraTag = if (stateTag.isNotBlank()) " | $stateTag" else ""
                    updateNotification("$phaseDesc | ${stagingResult.stage.displayName}$extraTag | 心率 $evaluatedHr bpm")
                }
            }
        }
    }

    private fun stopSleepGuard() {
        epochCollectorJob?.cancel()
        epochCollectorJob = null
        isEcgLatchedOff = false
        isShadowWarmingUp = false
        leadsOffStartTimeMs = 0L
        _dualEngineState.value = null

        val app = applicationContext as FlashAlarmApp
        app.remEngine.setShadowPreWarming(false)
        try {
            app.audioAnalyzer.stopAnalysis()
            app.audioPlayer.stopAudio()
            app.bleManager.stopVibration()
            app.bleManager.setHeartRateStreamingMode(false)
            app.bleManager.disableSensorNotifications()
            app.ecgBleManager.disconnect()
            app.eogBleManager.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping peripherals in stopSleepGuard", e)
        }

        serviceScope.launch {
            if (currentActiveSessionId > 0L) {
                try {
                    app.sleepRepository.finalizeSession(currentActiveSessionId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error finalizing session in DB", e)
                }
            }
            _isServiceRunning.value = false
            _currentSessionId.value = null
            _liveStaging.value = null
            _activeCue.value = null

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SleepGuardService::class.java).apply {
            action = ACTION_STOP_GUARD
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle("FlashAlarm 睡眠守护中")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(R.drawable.ic_stat_moon, "结束守护", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "睡眠守护服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持后台手环多模态睡眠分期与触梦守护"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        epochCollectorJob?.cancel()
        epochCollectorJob = null
        isEcgLatchedOff = false
        isShadowWarmingUp = false
        leadsOffStartTimeMs = 0L
        _dualEngineState.value = null

        try {
            val app = applicationContext as? FlashAlarmApp
            app?.remEngine?.setShadowPreWarming(false)
            app?.audioAnalyzer?.stopAnalysis()
            app?.audioPlayer?.stopAudio()
            app?.bleManager?.stopVibration()
            app?.bleManager?.setHeartRateStreamingMode(false)
            app?.bleManager?.disableSensorNotifications()
            app?.ecgBleManager?.disconnect()
            app?.eogBleManager?.disconnect()
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wakelock or stopping streaming", e)
        }
        serviceScope.cancel()
        _isServiceRunning.value = false
        _currentSessionId.value = null
        _liveStaging.value = null
        _activeCue.value = null
    }
}
