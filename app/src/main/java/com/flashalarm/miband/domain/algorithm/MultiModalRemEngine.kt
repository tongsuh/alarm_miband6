package com.flashalarm.miband.domain.algorithm

import com.flashalarm.miband.domain.model.DreamCueConfig
import com.flashalarm.miband.domain.model.RemStagingResult
import com.flashalarm.miband.domain.model.SleepSessionPhase
import com.flashalarm.miband.domain.model.SleepStage
import kotlin.math.sqrt

/**
 * MultiModalRemEngine
 * Implements the 75% Wrist Actigraphy + PPG HR/HRV & 25% Phone Acoustic Breathing
 * layered fusion REM staging and relative-onset lucid dream cue dispatch engine.
 */
class MultiModalRemEngine(
    private var cueConfig: DreamCueConfig = DreamCueConfig()
) {
    // Session state
    private var sessionStartTimeMs: Long = 0L
    private var sleepOnsetDetectedTimeMs: Long = 0L
    private var isSleepOnsetDetected: Boolean = false
    private var sustainedStillnessEpochs: Int = 0
    private var lastCueTriggerTimeMs: Long = 0L

    // Sliding buffers (last N samples / epochs)
    private val recentHeartRates = ArrayDeque<Int>()
    private val recentActigraphy = ArrayDeque<Float>()
    private val recentAudioIrregularities = ArrayDeque<Float>()

    // Nocturnal baseline tracking (established during stable deep sleep)
    private var deepSleepBaselineHr: Float = 60.0f
    private var baselineEstablished = false

    fun updateConfig(config: DreamCueConfig) {
        this.cueConfig = config
    }

    fun startSession(startTimeMs: Long = System.currentTimeMillis()) {
        sessionStartTimeMs = startTimeMs
        sleepOnsetDetectedTimeMs = 0L
        isSleepOnsetDetected = false
        sustainedStillnessEpochs = 0
        lastCueTriggerTimeMs = 0L
        recentHeartRates.clear()
        recentActigraphy.clear()
        recentAudioIrregularities.clear()
        baselineEstablished = false
        deepSleepBaselineHr = 60.0f
    }

    /**
     * Feed continuous sensor inputs into the staging engine:
     * @param heartRate Current heart rate in BPM
     * @param actigraphyMagnitude Wrist acceleration magnitude delta or count (g)
     * @param audioIrregularity Breathing irregularity score [0.0 - 1.0], or -1.0 if audio unavailable
     * @param isAudioReliable true if ambient SNR is clean and audio permission enabled
     * @param currentTimeMs Timestamp of evaluation
     */
    fun evaluateEpoch(
        heartRate: Int,
        actigraphyMagnitude: Float,
        audioIrregularity: Float = -1.0f,
        isAudioReliable: Boolean = false,
        currentTimeMs: Long = System.currentTimeMillis(),
        peakActigraphy: Float = actigraphyMagnitude
    ): RemStagingResult {
        // 1. Maintain sliding window (last 60 samples ~ 30 minutes)
        if (heartRate in 36..219) {
            recentHeartRates.addLast(heartRate)
            if (recentHeartRates.size > 60) recentHeartRates.removeFirst()
        }

        recentActigraphy.addLast(actigraphyMagnitude)
        if (recentActigraphy.size > 60) recentActigraphy.removeFirst()

        if (isAudioReliable && audioIrregularity >= 0f) {
            recentAudioIrregularities.addLast(audioIrregularity)
            if (recentAudioIrregularities.size > 30) recentAudioIrregularities.removeFirst()
        }

        // 2. Wrist Actigraphy: Muscle Atonia & VETO Trigger
        val maxRecentMovement = kotlin.math.max(recentActigraphy.maxOrNull() ?: actigraphyMagnitude, peakActigraphy)
        val avgMovement = if (recentActigraphy.isNotEmpty()) recentActigraphy.average().toFloat() else actigraphyMagnitude
        val isVetoedByMovement = maxRecentMovement > 0.14f || peakActigraphy > 0.18f

        // Atonia score: 1.0 when completely still (actigraphy < 0.02g), drops to 0 when moving
        val atoniaScore = (1.0f - (avgMovement / 0.10f)).coerceIn(0.0f, 1.0f)

        // 3. Autonomic PPG Heart Rate & HRV (CV = SD / Mean)
        val currentMeanHr = if (recentHeartRates.isNotEmpty()) recentHeartRates.average().toFloat() else heartRate.toFloat()
        val hrVariance = if (recentHeartRates.size > 5) {
            val mean = currentMeanHr
            recentHeartRates.map { (it - mean) * (it - mean) }.average().toFloat()
        } else 0f
        val hrStdDev = sqrt(hrVariance)
        val hrvCv = if (currentMeanHr > 0) (hrStdDev / currentMeanHr) else 0f

        // Update deep sleep baseline during prolonged quiet, low-HR epochs
        if (atoniaScore > 0.85f && hrvCv < 0.04f && currentMeanHr in 45.0f..85.0f) {
            deepSleepBaselineHr = if (!baselineEstablished) {
                baselineEstablished = true
                currentMeanHr
            } else {
                deepSleepBaselineHr * 0.95f + currentMeanHr * 0.05f
            }
        }

        // Calculate HR surge over deep sleep baseline
        val hrSurgePercent = if (deepSleepBaselineHr > 0) {
            ((currentMeanHr - deepSleepBaselineHr) / deepSleepBaselineHr).coerceAtLeast(0f)
        } else 0f

        // 4. Sleep Onset Detection State Machine (Cole-Kripke stillness + resting HR dip)
        if (!isSleepOnsetDetected) {
            val isStill = avgMovement < 0.045f && peakActigraphy < 0.12f && atoniaScore >= 0.60f
            if (isStill) {
                sustainedStillnessEpochs++
                // Physiological Sleep Onset Dip check:
                // Bedtime resting HR typically dips 2.5+ bpm below initial bedtime level, with HRV stabilizing
                val initialHr = if (recentHeartRates.size >= 8) recentHeartRates.take(8).average().toFloat() else currentMeanHr
                val hasHrDipped = initialHr > 0 && currentMeanHr > 0 && (initialHr - currentMeanHr >= 2.5f)
                val isHrvStable = hrvCv in 0.01f..0.045f

                // Sleep onset confirmed if:
                // 1) 16 sustained quiet epochs (8 mins) AND (HR dipped or HRV stabilized)
                // 2) OR unbroken stillness for 24 epochs (12 mins) as physiological fallback
                if ((sustainedStillnessEpochs >= 16 && (hasHrDipped || isHrvStable)) || sustainedStillnessEpochs >= 24) {
                    isSleepOnsetDetected = true
                    sleepOnsetDetectedTimeMs = currentTimeMs
                }
            } else if (peakActigraphy > 0.15f || avgMovement > 0.10f) {
                // User rolled over or got up
                sustainedStillnessEpochs = (sustainedStillnessEpochs - 3).coerceAtLeast(0)
            }
        }

        // Determine session phase & relative protection timing
        val protectionDurationMs = (cueConfig.sleepOnsetProtectionHours * 3600 * 1000L).toLong()
        val elapsedSinceOnsetMs = if (isSleepOnsetDetected) currentTimeMs - sleepOnsetDetectedTimeMs else 0L

        val sessionPhase: SleepSessionPhase
        val protectionRemainingMinutes: Int
        val isWithinTimingWindow: Boolean

        if (!isSleepOnsetDetected) {
            sessionPhase = SleepSessionPhase.DETECTING_ONSET
            protectionRemainingMinutes = (cueConfig.sleepOnsetProtectionHours * 60).toInt()
            isWithinTimingWindow = false
        } else if (elapsedSinceOnsetMs < protectionDurationMs) {
            sessionPhase = SleepSessionPhase.PROTECTION_PERIOD
            protectionRemainingMinutes = ((protectionDurationMs - elapsedSinceOnsetMs) / 60000L).toInt().coerceAtLeast(1)
            isWithinTimingWindow = false
        } else {
            sessionPhase = SleepSessionPhase.DREAM_WINDOW_ACTIVE
            protectionRemainingMinutes = 0
            isWithinTimingWindow = true
        }

        // 5. Acoustic Respiratory Analysis
        val avgAudioIrregularity = if (recentAudioIrregularities.isNotEmpty()) {
            recentAudioIrregularities.average().toFloat()
        } else {
            -1.0f
        }

        // 6. Staging Decision
        var determinedStage = SleepStage.AWAKE
        var remConfidence = 0.0f

        if (isVetoedByMovement || avgMovement > 0.25f) {
            determinedStage = SleepStage.AWAKE
            remConfidence = 0.0f
        } else if (atoniaScore > 0.70f) {
            if (hrSurgePercent >= 0.08f && (hrvCv >= 0.05f || hrStdDev >= 3.5f)) {
                determinedStage = SleepStage.REM

                // 75% Wrist Actigraphy & PPG HR/HRV Score
                val wristScore = (atoniaScore * 0.40f) +
                        (hrSurgePercent.coerceIn(0.08f, 0.25f) / 0.25f * 0.35f) +
                        (hrvCv.coerceIn(0.04f, 0.12f) / 0.12f * 0.25f)

                if (isAudioReliable && avgAudioIrregularity >= 0f) {
                    val audioScore = avgAudioIrregularity.coerceIn(0.0f, 1.0f)
                    remConfidence = (wristScore * 0.75f) + (audioScore * 0.25f)
                } else {
                    remConfidence = wristScore.coerceIn(0.0f, 1.0f)
                }
            } else if (hrSurgePercent < 0.05f && hrvCv < 0.04f) {
                determinedStage = SleepStage.DEEP
                remConfidence = 0.0f
            } else {
                determinedStage = SleepStage.LIGHT
                remConfidence = 0.15f
            }
        } else {
            determinedStage = SleepStage.LIGHT
            remConfidence = 0.05f
        }

        if (isVetoedByMovement) {
            remConfidence = 0.0f
        }

        // 7. Lucid Dream Cueing Eligibility & Cooldown
        val cooldownMs = cueConfig.cooldownMinutes * 60 * 1000L
        val isCooldownPassed = (currentTimeMs - lastCueTriggerTimeMs) >= cooldownMs
        val meetsConfidence = remConfidence >= cueConfig.confidenceThreshold

        val isEligible = determinedStage == SleepStage.REM &&
                !isVetoedByMovement &&
                isWithinTimingWindow &&
                meetsConfidence

        val shouldTrigger = isEligible && isCooldownPassed

        val triggerReason = when {
            shouldTrigger -> "双重印证命中REM高置信期 (置信度 ${(remConfidence * 100).toInt()}%)"
            isVetoedByMovement -> "手腕体动一票否决"
            !isSleepOnsetDetected -> "正在监测入睡状态 (静息沉淀 ${sustainedStillnessEpochs}/16)"
            sessionPhase == SleepSessionPhase.PROTECTION_PERIOD -> "处于前半夜深睡保护期 (剩余 $protectionRemainingMinutes 分钟)"
            !isCooldownPassed -> "处于击发冷却间隔中 (${((cooldownMs - (currentTimeMs - lastCueTriggerTimeMs)) / 60000)}分钟后解锁)"
            !meetsConfidence -> "置信度不足 ${(remConfidence * 100).toInt()}% / ${(cueConfig.confidenceThreshold * 100).toInt()}%"
            else -> "非做梦期"
        }

        if (shouldTrigger) {
            lastCueTriggerTimeMs = currentTimeMs
        }

        return RemStagingResult(
            stage = determinedStage,
            confidence = remConfidence,
            isDreamCueEligible = isEligible,
            isDreamCueTriggered = shouldTrigger,
            triggerReason = triggerReason,
            atoniaScore = atoniaScore,
            hrSurgePercent = hrSurgePercent,
            hrvDispersion = hrvCv,
            audioIrregularity = if (avgAudioIrregularity >= 0f) avgAudioIrregularity else 0f,
            isVetoedByMovement = isVetoedByMovement,
            isWithinTimingWindow = isWithinTimingWindow,
            sessionPhase = sessionPhase,
            isSleepOnsetDetected = isSleepOnsetDetected,
            protectionRemainingMinutes = protectionRemainingMinutes,
            sleepOnsetDetectedTimeMs = sleepOnsetDetectedTimeMs,
            timestamp = currentTimeMs
        )
    }

    fun markSleepOnset(onsetMs: Long) {
        isSleepOnsetDetected = true
        sleepOnsetDetectedTimeMs = onsetMs
    }

    fun forceResetCooldown() {
        lastCueTriggerTimeMs = 0L
    }
}
