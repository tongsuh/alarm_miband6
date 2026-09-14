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
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var wakeLock: PowerManager.WakeLock? = null
    private var epochCollectorJob: Job? = null

    // Cache latest values for epoch evaluation
    private var lastHeartRate = 60
    private var lastActigraphy = 0.0f
    private var currentActiveSessionId: Long = 0L

    inner class LocalBinder : Binder() {
        fun getService(): SleepGuardService = this@SleepGuardService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()

        // Immediate promotion to foreground service in onCreate to guarantee system 5-second FGS contract
        val notification = buildNotification("正在监测睡眠体动与心率...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting connectedDevice foreground service in onCreate", e)
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
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
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

            // 4. Start Audio Analyzer if configured and permitted
            if (enableAudio) {
                try {
                    app.audioAnalyzer.startAnalysis()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed starting audio analyzer", e)
                }
            }

            // 5. Collect incoming sensor flows
            launch {
                app.bleManager.heartRateFlow.collect { hr ->
                    lastHeartRate = hr
                }
            }
            launch {
                app.bleManager.actigraphyFlow.collect { act ->
                    lastActigraphy = act
                }
            }

            // 6. Start 30-second epoch loop
            startEpochLoop(app, sessionId)
        }
    }

    private fun startEpochLoop(app: FlashAlarmApp, sessionId: Long) {
        epochCollectorJob?.cancel()
        epochCollectorJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive && _isServiceRunning.value) {
                delay(30000L) // 30-second epoch tick

                val audioState = app.audioAnalyzer.state.value
                val stagingResult = app.remEngine.evaluateEpoch(
                    heartRate = lastHeartRate,
                    actigraphyMagnitude = lastActigraphy,
                    audioIrregularity = if (audioState.isAudioReliable) audioState.irregularityScore else -1.0f,
                    isAudioReliable = audioState.isAudioReliable,
                    currentTimeMs = System.currentTimeMillis()
                )

                _liveStaging.value = stagingResult

                // Differential Heart Rate Sampling adjustment:
                // When in DREAM_WINDOW_ACTIVE, switch band to continuous 1Hz sampling
                if (stagingResult.sessionPhase == SleepSessionPhase.DREAM_WINDOW_ACTIVE) {
                    app.bleManager.setHeartRateStreamingMode(true)
                }

                // Record epoch in DB
                app.sleepRepository.recordEpoch(
                    sessionId = sessionId,
                    timestamp = stagingResult.timestamp,
                    stage = stagingResult.stage,
                    heartRate = lastHeartRate,
                    actigraphy = lastActigraphy,
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

                    app.sleepRepository.recordCue(
                        sessionId = sessionId,
                        timestamp = stagingResult.timestamp,
                        cadenceName = methodDescription.ifBlank { activePattern.name },
                        confidence = stagingResult.confidence,
                        heartRate = lastHeartRate,
                        triggerReason = stagingResult.triggerReason
                    )

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

        val app = applicationContext as FlashAlarmApp
        app.audioAnalyzer.stopAnalysis()
        app.audioPlayer.stopAudio()
        app.bleManager.stopVibration()

        serviceScope.launch {
            if (currentActiveSessionId > 0L) {
                app.sleepRepository.finalizeSession(currentActiveSessionId)
            }
            _isServiceRunning.value = false
            _currentSessionId.value = null
            _liveStaging.value = null

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
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wakelock", e)
        }
        serviceScope.cancel()
        _isServiceRunning.value = false
    }
}
