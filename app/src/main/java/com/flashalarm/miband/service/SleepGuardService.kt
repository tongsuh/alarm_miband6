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
    private var isMiBandPpgSuspended = false

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
            if (isDualMode && ecgMac.isNotBlank()) {
                if (app.ecgBleManager.connectionState.value != com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED) {
                    app.ecgBleManager.setTargetDevice(ecgMac)
                    app.ecgBleManager.connect(ecgMac)
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
                    app.remEngine.pushRrInterval(rrMs)
                }
            }

            // 6. Ensure initial high-frequency sensor streaming
            app.bleManager.setHeartRateStreamingMode(true)
            app.bleManager.enableSensorNotifications()

            // 6.5 Failover & Power-Saving State Machine for Dual-Modality
            // Instant event-driven failover listener for leads-off & zero heart rate
            launch {
                app.ecgBleManager.isLeadsOff.collect { leadsOff ->
                    val currentCfg = app.userPreferencesRepository.cueConfig.value
                    val dualActive = currentCfg.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL
                    if (dualActive && leadsOff && isMiBandPpgSuspended) {
                        Log.w(TAG, "Instant Failover: ECG Leads-off detected via Flow! Immediately resuming Mi Band optical PPG.")
                        app.bleManager.setHeartRateStreamingMode(true)
                        lastHeartRateReceivedTimeMs = System.currentTimeMillis()
                        isMiBandPpgSuspended = false
                    }
                }
            }

            // Periodic composite health assessment and power-saving watchdog (every 1s)
            launch {
                while (isActive) {
                    val currentCfg = app.userPreferencesRepository.cueConfig.value
                    val dualActive = currentCfg.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL
                    val ecgTarget = currentCfg.ad8232MacAddress.ifBlank { ecgMac }
                    if (dualActive && ecgTarget.isNotBlank()) {
                        val isEcgConnected = app.ecgBleManager.connectionState.value == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED
                        val isLeadsOff = app.ecgBleManager.isLeadsOff.value
                        val ecgHr = app.ecgBleManager.currentHeartRate.value
                        val isEcgFresh = app.ecgBleManager.isDataFresh(6000L)

                        // Composite health: Active connection + Leads attached + Valid HR > 0 + Fresh packets within 6s
                        val isEcgHealthy = isEcgConnected && !isLeadsOff && ecgHr > 0 && isEcgFresh

                        if (isEcgHealthy) {
                            // Primary mode: ECG fully healthy. Turn OFF Mi Band optical PPG for power saving
                            if (!isMiBandPpgSuspended) {
                                Log.d(TAG, "ECG healthy & streaming ($ecgHr bpm). Suspending Mi Band PPG for power saving.")
                                app.bleManager.setHeartRateStreamingMode(false)
                                isMiBandPpgSuspended = true
                            }
                        } else {
                            // Failover mode: Leads off, zero HR, data stalled, or disconnected. Resume Mi Band PPG immediately!
                            if (isMiBandPpgSuspended) {
                                Log.w(TAG, "ECG degraded (conn=$isEcgConnected, leadsOff=$isLeadsOff, hr=$ecgHr, fresh=$isEcgFresh). Failover to Mi Band PPG!")
                                app.bleManager.setHeartRateStreamingMode(true)
                                lastHeartRateReceivedTimeMs = System.currentTimeMillis()
                                isMiBandPpgSuspended = false
                            }
                        }
                    } else {
                        // Dual mode disabled or unconfigured. Ensure Band PPG is active if previously suspended
                        if (isMiBandPpgSuspended) {
                            Log.i(TAG, "Dual ECG mode inactive. Resuming Mi Band PPG.")
                            app.bleManager.setHeartRateStreamingMode(true)
                            lastHeartRateReceivedTimeMs = System.currentTimeMillis()
                            isMiBandPpgSuspended = false
                        }
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
                val isEcgActiveAndHealthy = isDualActive &&
                        app.ecgBleManager.connectionState.value == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED &&
                        !app.ecgBleManager.isLeadsOff.value &&
                        app.ecgBleManager.currentHeartRate.value > 0 &&
                        app.ecgBleManager.isDataFresh(6000L)

                val isHrFresh = lastHeartRateReceivedTimeMs > 0L && (now - lastHeartRateReceivedTimeMs) < 45000L
                val ecgHr = app.ecgBleManager.currentHeartRate.value
                val evaluatedHr = if (isEcgActiveAndHealthy && ecgHr > 0) {
                    ecgHr
                } else if (isHrFresh) {
                    epochMeanHr.toInt().coerceIn(36, 220)
                } else {
                    lastHeartRate
                }

                val audioState = app.audioAnalyzer.state.value
                val stagingResult = app.remEngine.evaluateEpoch(
                    heartRate = evaluatedHr,
                    actigraphyMagnitude = epochAvgAct,
                    audioIrregularity = if (audioState.isAudioReliable) audioState.irregularityScore else -1.0f,
                    isAudioReliable = audioState.isAudioReliable,
                    currentTimeMs = now,
                    peakActigraphy = epochMaxAct,
                    intraEpochHrStdDev = epochHrStdDev
                )

                _liveStaging.value = stagingResult

                // Maintain continuous 1Hz heart rate streaming throughout all sleep phases & Watchdog recovery
                // Note: Only run optical PPG watchdog when Band PPG is NOT suspended for ECG power saving
                val hrIdleMs = if (lastHeartRateReceivedTimeMs > 0L) now - lastHeartRateReceivedTimeMs else 0L
                if (!isMiBandPpgSuspended && !isEcgActiveAndHealthy && _isServiceRunning.value && app.bleManager.connectionState.value == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED) {
                    if (hrIdleMs > 75000L) {
                        Log.e(TAG, "Heart rate stream stalled for ${hrIdleMs}ms (>75s). Underlying GATT client appears locked. Triggering silent reconnect...")
                        lastHeartRateReceivedTimeMs = now // Reset baseline while reconnect is in progress
                        app.bleManager.reconnectSilently()
                    } else if (hrIdleMs > 45000L) {
                        Log.w(TAG, "Heart rate stream quiet for ${hrIdleMs}ms (>45s). Stage 1 recovery: sending soft refresh...")
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
                    val phaseDesc = when (stagingResult.sessionPhase) {
                        SleepSessionPhase.DETECTING_ONSET -> "监测入睡中"
                        SleepSessionPhase.PROTECTION_PERIOD -> "深睡保护期(余${stagingResult.protectionRemainingMinutes}分)"
                        SleepSessionPhase.DREAM_WINDOW_ACTIVE -> "触梦雷达全程开启"
                    }
                    updateNotification("$phaseDesc | ${stagingResult.stage.displayName} | 心率 $lastHeartRate bpm")
                }
            }
        }
    }

    private fun stopSleepGuard() {
        epochCollectorJob?.cancel()
        epochCollectorJob = null
        isMiBandPpgSuspended = false

        val app = applicationContext as FlashAlarmApp
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
        isMiBandPpgSuspended = false

        try {
            val app = applicationContext as? FlashAlarmApp
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
