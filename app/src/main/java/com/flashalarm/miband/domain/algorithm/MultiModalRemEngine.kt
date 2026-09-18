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

    // Dual sliding buffers
    private val shortTermHeartRates = ArrayDeque<Int>() // Last 6 samples ~ 3 minutes (fast REM surge reaction)
    private val longTermHeartRates = ArrayDeque<Int>()  // Last 60 samples ~ 30 minutes (nocturnal baseline tracking)
    private val recentActigraphy = ArrayDeque<Float>()  // Last 8 samples ~ 4 minutes (muscle atonia context)
    private val recentAudioIrregularities = ArrayDeque<Float>()

    // Nocturnal baseline tracking (established during stable deep sleep)
    private var deepSleepBaselineHr: Float = 60.0f
    private var baselineEstablished = false

    // Initial bedtime resting HR tracking for sleep onset dip detection
    private var bedtimeBaselineHr: Float = 0.0f
    private var bedtimeSamplesCount: Int = 0

    // Movement classification and stage hysteresis state
    private var consecutiveMovingEpochs: Int = 0
    private var lastEstablishedStage: SleepStage = SleepStage.AWAKE
    private var pendingStage: SleepStage? = null
    private var pendingStageCount: Int = 0

    fun updateConfig(config: DreamCueConfig) {
        this.cueConfig = config
    }

    fun startSession(startTimeMs: Long = System.currentTimeMillis()) {
        sessionStartTimeMs = startTimeMs
        sleepOnsetDetectedTimeMs = 0L
        isSleepOnsetDetected = false
        sustainedStillnessEpochs = 0
        lastCueTriggerTimeMs = 0L
        shortTermHeartRates.clear()
        longTermHeartRates.clear()
        recentActigraphy.clear()
        recentAudioIrregularities.clear()
        baselineEstablished = false
        deepSleepBaselineHr = 60.0f
        bedtimeBaselineHr = 0.0f
        bedtimeSamplesCount = 0
        consecutiveMovingEpochs = 0
        lastEstablishedStage = SleepStage.AWAKE
        pendingStage = null
        pendingStageCount = 0
    }

    /**
     * Feed continuous sensor inputs into the staging engine:
     * @param heartRate Current epoch mean heart rate in BPM (or -1 if offline)
     * @param actigraphyMagnitude Wrist acceleration magnitude delta or count (g)
     * @param audioIrregularity Breathing irregularity score [0.0 - 1.0], or -1.0 if audio unavailable
     * @param isAudioReliable true if ambient SNR is clean and audio permission enabled
     * @param currentTimeMs Timestamp of evaluation
     * @param peakActigraphy Peak wrist acceleration spike in current 30s epoch (g)
     * @param intraEpochHrStdDev Standard deviation of ~30 1Hz heart rate readings within current epoch
     */
    fun evaluateEpoch(
        heartRate: Int,
        actigraphyMagnitude: Float,
        audioIrregularity: Float = -1.0f,
        isAudioReliable: Boolean = false,
        currentTimeMs: Long = System.currentTimeMillis(),
        peakActigraphy: Float = actigraphyMagnitude,
        intraEpochHrStdDev: Float = 0.0f
    ): RemStagingResult {
        // 1. Maintain sliding windows
        if (heartRate in 36..219) {
            shortTermHeartRates.addLast(heartRate)
            if (shortTermHeartRates.size > 6) shortTermHeartRates.removeFirst() // 3 minutes

            longTermHeartRates.addLast(heartRate)
            if (longTermHeartRates.size > 60) longTermHeartRates.removeFirst() // 30 minutes

            if (!isSleepOnsetDetected && bedtimeSamplesCount < 8) {
                bedtimeBaselineHr = (bedtimeBaselineHr * bedtimeSamplesCount + heartRate) / (bedtimeSamplesCount + 1)
                bedtimeSamplesCount++
            }
        }

        recentActigraphy.addLast(actigraphyMagnitude)
        if (recentActigraphy.size > 8) recentActigraphy.removeFirst() // 4 minutes

        if (isAudioReliable && audioIrregularity >= 0f) {
            recentAudioIrregularities.addLast(audioIrregularity)
            if (recentAudioIrregularities.size > 30) recentAudioIrregularities.removeFirst()
        }

        // 2. Wrist Actigraphy: Muscle Atonia & Awakening Detection
        val avgMovement = if (recentActigraphy.isNotEmpty()) recentActigraphy.average().toFloat() else actigraphyMagnitude
        // Atonia score: 1.0 when completely motionless (<0.015g), drops toward 0 when moving
        val atoniaScore = (1.0f - (avgMovement / 0.08f)).coerceIn(0.0f, 1.0f)

        // Movement Classification:
        // A. Any movement spike (including brief micro-twitch):
        val isAnyMovement = peakActigraphy > 0.18f || actigraphyMagnitude > 0.08f
        if (isAnyMovement) {
            consecutiveMovingEpochs++
        } else {
            consecutiveMovingEpochs = 0
        }

        // B. Vigorous waking movement (e.g. sitting up, getting out of bed):
        // High acceleration (>=0.30g) accompanied by elevated wake heart rate (>=72 bpm or >=20% surge), or extreme movement (>=0.45g)
        val isVigorousWakeMovement = (actigraphyMagnitude >= 0.30f && (heartRate >= 72 || (deepSleepBaselineHr > 0 && (heartRate - deepSleepBaselineHr) / deepSleepBaselineHr >= 0.20f))) ||
                actigraphyMagnitude >= 0.45f

        // C. Sustained macroscopic movement across epochs (>= 2 epochs with movement or multi-minute elevated average):
        val isSustainedMovement = (consecutiveMovingEpochs >= 2 && actigraphyMagnitude >= 0.10f) ||
                consecutiveMovingEpochs >= 3 ||
                avgMovement > 0.18f

        // True Awakening requires either vigorous waking movement or sustained movement across epochs:
        val isSustainedAwake = isVigorousWakeMovement || isSustainedMovement

        // Per user requirement:
        // Micro-movements / normal isolated rollovers DO NOT veto dream cue vibrations! Only sustained awakening vetoes vibration.
        val isVetoedByMovement = isSustainedAwake

        // 3. Autonomic PPG Heart Rate & Dispersion
        val shortTermMeanHr = if (shortTermHeartRates.isNotEmpty()) shortTermHeartRates.average().toFloat() else heartRate.toFloat()

        val hrVariance = if (shortTermHeartRates.size > 3) {
            val mean = shortTermMeanHr
            shortTermHeartRates.map { (it - mean) * (it - mean) }.average().toFloat()
        } else 0f
        val shortTermHrStdDev = sqrt(hrVariance)
        val hrvCv = if (shortTermMeanHr > 0) (shortTermHrStdDev / shortTermMeanHr) else 0f

        // Combined autonomic dispersion: combines intra-epoch micro-instability with 3-minute macro fluctuation
        val combinedDispersion = kotlin.math.max(intraEpochHrStdDev, shortTermHrStdDev)

        // Update deep sleep baseline during prolonged quiet, low-HR epochs:
        if (atoniaScore > 0.85f && combinedDispersion < 1.2f && shortTermMeanHr in 42.0f..82.0f) {
            deepSleepBaselineHr = if (!baselineEstablished) {
                baselineEstablished = true
                shortTermMeanHr
            } else {
                deepSleepBaselineHr * 0.98f + shortTermMeanHr * 0.02f
            }
        }

        // Calculate HR surge over deep sleep baseline using fast 3-minute short-term window:
        val hrSurgePercent = if (deepSleepBaselineHr > 0) {
            ((shortTermMeanHr - deepSleepBaselineHr) / deepSleepBaselineHr).coerceAtLeast(0f)
        } else 0f

        // 4. Sleep Onset Detection State Machine (Cole-Kripke stillness + resting HR dip)
        if (!isSleepOnsetDetected) {
            val isStill = avgMovement < 0.045f && peakActigraphy < 0.12f && atoniaScore >= 0.60f
            if (isStill) {
                sustainedStillnessEpochs++
                // Physiological Sleep Onset Dip check:
                // Bedtime resting HR typically dips 2.5+ bpm below initial bedtime level, with autonomic stabilization
                val hasHrDipped = bedtimeSamplesCount >= 6 && bedtimeBaselineHr > 0 && (bedtimeBaselineHr - shortTermMeanHr >= 2.5f)
                val isHrvStable = combinedDispersion < 1.3f

                if ((sustainedStillnessEpochs >= 16 && (hasHrDipped || isHrvStable)) || sustainedStillnessEpochs >= 24) {
                    isSleepOnsetDetected = true
                    sleepOnsetDetectedTimeMs = currentTimeMs
                }
            } else if (peakActigraphy > 0.15f || avgMovement > 0.10f) {
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

        // 6. Ultradian 90-110m Sleep Cycle Soft Prior Model
        val elapsedMinutes = if (isSleepOnsetDetected) elapsedSinceOnsetMs / 60000f else 0f
        val ultradianPrior = if (isSleepOnsetDetected) calculateUltradianRemPrior(elapsedMinutes) else 0.05f

        // 7. Staging Decision & 3-Epoch Temporal Hysteresis Filter
        // REM physiological pattern: Autonomic storm surge + intra-epoch or short-term dispersion
        val isAutonomicSurge = (hrSurgePercent >= 0.07f && combinedDispersion >= 1.5f) ||
                (hrSurgePercent >= 0.11f) ||
                (combinedDispersion >= 2.0f && hrSurgePercent >= 0.04f)

        var tentativeStage: SleepStage
        var tentativeRemConfidence = 0.0f

        if (isSustainedAwake) {
            tentativeStage = SleepStage.AWAKE
            tentativeRemConfidence = 0.0f
        } else if (atoniaScore > 0.70f) {
            if (isAutonomicSurge) {
                tentativeStage = SleepStage.REM

                // Sensor raw score (Atonia 35%, Surge 40%, Dispersion 25%)
                val wristScore = (atoniaScore * 0.35f) +
                        (hrSurgePercent.coerceIn(0.06f, 0.20f) / 0.20f * 0.40f) +
                        (combinedDispersion.coerceIn(1.0f, 3.0f) / 3.0f * 0.25f)

                val multiModalSensorScore = if (isAudioReliable && avgAudioIrregularity >= 0f) {
                    (wristScore * 0.75f) + (avgAudioIrregularity.coerceIn(0f, 1f) * 0.25f)
                } else {
                    wristScore
                }

                // Soft Prior Bayesian Fusion: 78% Physical Sensors + 22% Ultradian Cycle Prior
                tentativeRemConfidence = ((multiModalSensorScore * 0.78f) + (ultradianPrior * 0.22f)).coerceIn(0.0f, 1.0f)

                // Ground-truth override: If physical sensor evidence is overwhelming, do not let prior suppress it
                if (multiModalSensorScore >= 0.85f) {
                    tentativeRemConfidence = kotlin.math.max(tentativeRemConfidence, multiModalSensorScore)
                }
            } else if (hrSurgePercent < 0.04f && combinedDispersion < 1.1f) {
                tentativeStage = SleepStage.DEEP
                tentativeRemConfidence = 0.0f
            } else {
                tentativeStage = SleepStage.LIGHT
                tentativeRemConfidence = 0.12f * ultradianPrior
            }
        } else {
            tentativeStage = if (avgMovement > 0.18f || consecutiveMovingEpochs >= 2) SleepStage.AWAKE else SleepStage.LIGHT
            tentativeRemConfidence = 0.05f
        }

        // 3-Epoch Temporal Hysteresis Filter:
        // Eliminates 1-minute isolated chattering between stages (e.g. 1m AWAKE next to 1m REM)
        val determinedStage: SleepStage
        if (isVigorousWakeMovement) {
            // Immediate vigorous waking action (e.g. sitting up in bed): output AWAKE for this epoch
            determinedStage = SleepStage.AWAKE
            if (consecutiveMovingEpochs >= 2) {
                lastEstablishedStage = SleepStage.AWAKE
                pendingStage = null
                pendingStageCount = 0
            } else {
                pendingStage = SleepStage.AWAKE
                pendingStageCount = 1
            }
        } else if (isSustainedMovement) {
            // Confirmed sustained movement across >= 2 epochs
            lastEstablishedStage = SleepStage.AWAKE
            pendingStage = null
            pendingStageCount = 0
            determinedStage = SleepStage.AWAKE
        } else if (tentativeStage == lastEstablishedStage) {
            pendingStage = null
            pendingStageCount = 0
            determinedStage = lastEstablishedStage
        } else {
            if (pendingStage == tentativeStage) {
                pendingStageCount++
                if (pendingStageCount >= 2) {
                    // Confirmed transition across 2 consecutive epochs (60s)
                    lastEstablishedStage = tentativeStage
                    pendingStage = null
                    pendingStageCount = 0
                    determinedStage = tentativeStage
                } else {
                    // Hold established stage for 1 epoch to smooth away transient artifacts
                    determinedStage = lastEstablishedStage
                }
            } else {
                pendingStage = tentativeStage
                pendingStageCount = 1
                determinedStage = lastEstablishedStage
            }
        }

        val remConfidence = if (determinedStage == SleepStage.REM) {
            if (tentativeStage == SleepStage.REM) tentativeRemConfidence else 0.72f
        } else if (determinedStage == SleepStage.AWAKE) {
            0.0f
        } else {
            tentativeRemConfidence.coerceAtMost(0.15f)
        }

        // 8. Lucid Dream Cueing Eligibility & Cooldown
        val cooldownMs = cueConfig.cooldownMinutes * 60 * 1000L
        val isCooldownPassed = (currentTimeMs - lastCueTriggerTimeMs) >= cooldownMs
        val meetsConfidence = remConfidence >= cueConfig.confidenceThreshold

        val isEligible = determinedStage == SleepStage.REM &&
                !isVetoedByMovement &&
                isWithinTimingWindow &&
                meetsConfidence

        val shouldTrigger = isEligible && isCooldownPassed

        val triggerReason = when {
            shouldTrigger -> "多模态印证命中REM期 (置信度 ${(remConfidence * 100).toInt()}% | 周期先验 ${(ultradianPrior * 100).toInt()}%)"
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
            hrvDispersion = combinedDispersion,
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

    /**
     * Ultradian 90-110m sleep cycle soft prior probability.
     * Computes expected REM probability based on elapsed sleep time.
     */
    fun calculateUltradianRemPrior(elapsedMinutes: Float): Float {
        if (elapsedMinutes < 45f) return 0.05f
        val standardCycleLen = 95.0f
        val phaseInCycle = elapsedMinutes % standardCycleLen
        val cycleIndex = (elapsedMinutes / standardCycleLen).toInt() + 1

        val phasePrior = when {
            phaseInCycle in 0.0f..45.0f -> 0.10f
            phaseInCycle in 45.0f..65.0f -> 0.10f + ((phaseInCycle - 45.0f) / 20.0f) * 0.55f
            else -> 0.65f + ((phaseInCycle - 65.0f) / 30.0f) * 0.25f // 65-95 min: 0.65 -> 0.90
        }

        val cycleMultiplier = when (cycleIndex) {
            1 -> 0.6f
            2 -> 1.0f
            3 -> 1.25f
            else -> 1.4f
        }

        return (phasePrior * cycleMultiplier).coerceIn(0.05f, 0.95f)
    }

    fun markSleepOnset(onsetMs: Long) {
        isSleepOnsetDetected = true
        sleepOnsetDetectedTimeMs = onsetMs
    }

    fun forceResetCooldown() {
        lastCueTriggerTimeMs = 0L
    }
}
