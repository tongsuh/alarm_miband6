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

        enum class DualEngineState {
            ECG_PRIMARY,       // AD8232 为主，真心电双模态 ML
            SHADOW_PREWARMING, // 8232 蓝牙重连后在后台静默攒数据预热 (需连续 21 个 Epoch/10.5分钟无异常)
            LATCH_BAND         // 电极真脱落，单向锁存到手环模式，整夜不再切回 8232
        }

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

            // 3.5 Connect AD8232 / ESP32-C3 BLE if Dual-Modality configured
            val ecgMac = prefs.getEcgMac()
            val isDualMode = config.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL
            isEcgLatchedOff = false
            isShadowWarmingUp = false
            leadsOffStartTimeMs = 0L
            if (isDualMode && ecgMac.isNotBlank()) {
                val isAlreadyConnected = app.ecgBleManager.connectionState.value == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED
                val isLeadsOff = app.ecgBleManager.isLeadsOff.value
                if (isAlreadyConnected && !isLeadsOff) {
                    isShadowWarmingUp = false
                    app.remEngine.setShadowPreWarming(false)
                    _dualEngineState.value = DualEngineState.ECG_PRIMARY
                } else {
                    isShadowWarmingUp = true
                    app.remEngine.setShadowPreWarming(true)
                    _dualEngineState.value = DualEngineState.SHADOW_PREWARMING
                }
                if (!isAlreadyConnected) {
                    app.ecgBleManager.setTargetDevice(ecgMac)
                    app.ecgBleManager.connect(ecgMac)
                }
            } else {
                _dualEngineState.value = null
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
                    if (!isEcgLatchedOff) {
                        app.remEngine.pushRrInterval(rrMs)
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
                    if (dualActive && !isEcgLatchedOff) {
                        if (connState == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED) {
                            val isLeadsOff = app.ecgBleManager.isLeadsOff.value
                            if (!isLeadsOff) {
                                Log.i(TAG, "ECG connected/reconnected! Entering SHADOW_PREWARMING.")
                                isShadowWarmingUp = true
                                app.remEngine.setShadowPreWarming(true)
                                _dualEngineState.value = DualEngineState.SHADOW_PREWARMING
                                app.remEngine.onLeadsOff(confirmed = false)
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
                    val currentCfg = app.userPreferencesRepository.cueConfig.value
                    val dualActive = currentCfg.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL
                    val ecgTarget = currentCfg.ad8232MacAddress.ifBlank { ecgMac }

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
                    val mean = if (count > 0) epochHeartRateSamples.average().toFloat() else lastHeartRate.toFloat()
                    val stdDev = if (count > 3) {
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
                val bandEvaluatedHr = if (isHrFresh) {
                    epochMeanHr.toInt().coerceIn(36, 220)
                } else {
                    lastHeartRate
                }

                val evaluatedHr = if (!isEcgLatchedOff && !isShadowWarmingUp && isEcgActiveAndHealthy && ecgHr > 0) {
                    ecgHr
                } else {
                    bandEvaluatedHr
                }

                val isEcgPrimary = !isEcgLatchedOff && !isShadowWarmingUp && isEcgActiveAndHealthy

                val audioState = app.audioAnalyzer.state.value
                val stagingResult = app.remEngine.evaluateEpoch(
                    heartRate = evaluatedHr,
                    actigraphyMagnitude = epochAvgAct,
                    audioIrregularity = if (audioState.isAudioReliable) audioState.irregularityScore else -1.0f,
                    isAudioReliable = audioState.isAudioReliable,
                    currentTimeMs = now,
                    peakActigraphy = epochMaxAct,
                    intraEpochHrStdDev = epochHrStdDev,
                    isEcgPrimary = isEcgPrimary
                )

                _liveStaging.value = stagingResult

                // Maintain continuous 1Hz heart rate streaming throughout all sleep phases (Mi Band PPG Hot Standby Watchdog)
                val hrIdleMs = if (lastHeartRateReceivedTimeMs > 0L) now - lastHeartRateReceivedTimeMs else 0L
                if (_isServiceRunning.value && app.bleManager.connectionState.value == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED) {
                    if (hrIdleMs > 75000L) {
                        Log.e(TAG, "Mi Band HR stream stalled for ${hrIdleMs}ms (>75s). Underlying GATT client appears locked. Triggering silent reconnect...")
                        lastHeartRateReceivedTimeMs = now // Reset baseline while reconnect is in progress
                        app.bleManager.reconnectSilently()
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
                        heartRate = lastHeartRate,
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
                    val stateTag = when (dualState) {
                        DualEngineState.ECG_PRIMARY -> "🫀心电双模"
                        DualEngineState.SHADOW_PREWARMING -> "⏳心电预热(${app.remEngine.getHrvBufferSize()}/21)"
                        DualEngineState.LATCH_BAND -> "🔒手环锁存"
                        null -> ""
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
