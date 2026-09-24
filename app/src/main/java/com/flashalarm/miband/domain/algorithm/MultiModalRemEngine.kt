package com.flashalarm.miband.domain.algorithm

import com.flashalarm.miband.domain.model.DreamCueConfig
import com.flashalarm.miband.domain.model.RemEngineMode
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
    private var consecutiveSubstantialMovingEpochs: Int = 0
    private var lastEstablishedStage: SleepStage = SleepStage.AWAKE
    private var pendingStage: SleepStage? = null
    private var pendingStageCount: Int = 0

    // ML Classifier Support
    private val remFeatureExtractor = RemFeatureExtractor()
    private val remHrvFeatureExtractor = RemHrvFeatureExtractor()
    private var sessionEpochCounter: Int = 0

    var isShadowPreWarming: Boolean = false
        private set

    fun setShadowPreWarming(warming: Boolean) {
        isShadowPreWarming = warming
    }

    fun isHrvBufferFull(): Boolean = remHrvFeatureExtractor.isBufferFull()

    fun getHrvBufferSize(): Int = remHrvFeatureExtractor.getBufferSize()

    fun updateConfig(config: DreamCueConfig) {
        this.cueConfig = config
    }

    fun pushRrInterval(rrMs: Double, timestampMs: Long = System.currentTimeMillis()) {
        remHrvFeatureExtractor.pushRrInterval(rrMs, timestampMs)
    }

    fun pushActigraphy(actG: Double, timestampMs: Long = System.currentTimeMillis()) {
        remHrvFeatureExtractor.pushMotion(actG, timestampMs)
    }

    fun onLeadsOff(isConfirmed: Boolean = false) {
        remHrvFeatureExtractor.onLeadsOff(isConfirmed = isConfirmed)
    }

    fun startSession(startTimeMs: Long = System.currentTimeMillis()) {
        sessionStartTimeMs = startTimeMs
        sleepOnsetDetectedTimeMs = 0L
        isSleepOnsetDetected = false
        sustainedStillnessEpochs = 0
        lastCueTriggerTimeMs = 0L
        isShadowPreWarming = false
        shortTermHeartRates.clear()
        longTermHeartRates.clear()
        recentActigraphy.clear()
        recentAudioIrregularities.clear()
        baselineEstablished = false
        deepSleepBaselineHr = 60.0f
        bedtimeBaselineHr = 0.0f
        bedtimeSamplesCount = 0
        consecutiveMovingEpochs = 0
        consecutiveSubstantialMovingEpochs = 0
        lastEstablishedStage = SleepStage.AWAKE
        pendingStage = null
        pendingStageCount = 0
        remFeatureExtractor.reset()
        remHrvFeatureExtractor.reset()
        sessionEpochCounter = 0
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
     * @param isEcgPrimary true if AD8232 is fully healthy and in ECG_PRIMARY mode
     */
    fun evaluateEpoch(
        heartRate: Int,
        actigraphyMagnitude: Float,
        audioIrregularity: Float = -1.0f,
        isAudioReliable: Boolean = false,
        currentTimeMs: Long = System.currentTimeMillis(),
        peakActigraphy: Float = actigraphyMagnitude,
        intraEpochHrStdDev: Float = 0.0f,
        isEcgPrimary: Boolean = true
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

        // Movement Classification (AASM-aligned):
        // A. Micro-movements: brief 3-15s isolated twitches or single rollovers
        val isMicroMovement = peakActigraphy > 0.16f || actigraphyMagnitude > 0.045f

        // B. Substantial movements: continuous physical activity sustained across the 30s epoch
        val isSubstantialMovement = actigraphyMagnitude >= 0.12f || (peakActigraphy >= 0.32f && actigraphyMagnitude >= 0.06f)

        if (isSubstantialMovement) {
            consecutiveSubstantialMovingEpochs++
            consecutiveMovingEpochs++
        } else if (isMicroMovement) {
            consecutiveMovingEpochs++
            consecutiveSubstantialMovingEpochs = (consecutiveSubstantialMovingEpochs - 1).coerceAtLeast(0)
        } else {
            consecutiveMovingEpochs = 0
            consecutiveSubstantialMovingEpochs = 0
        }

        // C. Vigorous waking movement (e.g. sitting up, getting out of bed):
        // High sustained epoch acceleration (>=0.28g) accompanied by wake-level heart rate (>=76 bpm or >=22% surge over deep baseline), or intense gross movement (>=0.42g)
        val hasWakeHrElevation = heartRate >= 76 || (deepSleepBaselineHr > 0 && (heartRate - deepSleepBaselineHr) / deepSleepBaselineHr >= 0.22f)
        val isVigorousWakeMovement = (actigraphyMagnitude >= 0.28f && hasWakeHrElevation) || actigraphyMagnitude >= 0.42f

        // D. Sustained macroscopic movement across epochs:
        // Under AASM guidelines, an isolated rollover or postural shift (<15s) in sleep is a movement micro-arousal, NOT Stage Wake.
        // True awakening requires sustained physical movement across >= 4 epochs (2 minutes), or vigorous waking movement across >= 2 epochs, or high multi-minute average with wake HR:
        val isSustainedAwake = (consecutiveSubstantialMovingEpochs >= 4) ||
                (consecutiveMovingEpochs >= 2 && isVigorousWakeMovement) ||
                (avgMovement > 0.20f && hasWakeHrElevation) ||
                (avgMovement > 0.26f)

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

        // 6.5 Push continuous metrics to ML Feature Extractor (maintains 17-epoch 3-min delay buffer)
        sessionEpochCounter++
        val mlFeatures = remFeatureExtractor.pushEpoch(
            epochIndex = sessionEpochCounter,
            meanHr = if (heartRate in 36..219) heartRate.toFloat() else deepSleepBaselineHr,
            stdHr = intraEpochHrStdDev,
            meanMotion = actigraphyMagnitude,
            peakMotion = peakActigraphy,
            timeSinceOnsetMin = if (isSleepOnsetDetected) elapsedMinutes.toDouble() else (sessionEpochCounter * 0.5)
        )
        val mlRemProbability: Float? = if (mlFeatures != null) {
            try {
                val probs = RemClassifierModel.score(mlFeatures)
                if (probs != null && probs.size >= 2) probs[1].toFloat() else null
            } catch (e: Exception) {
                null
            }
        } else null

        val isDualEcgMode = cueConfig.engineMode == RemEngineMode.AD8232_DUAL
        val isMlMode = cueConfig.engineMode == RemEngineMode.ML_MODEL

        // 6.6 Push continuous metrics to PAAWS R2 True-HRV & Actigraphy Dual-Modality Extractor
        val hrvFeatures = remHrvFeatureExtractor.onEpochTick(
            epochIdx = sessionEpochCounter.toLong(),
            fallbackMotionMean = actigraphyMagnitude.toDouble(),
            fallbackMotionMax = peakActigraphy.toDouble()
        )
        val hrvRemProbability: Float? = if (hrvFeatures != null) {
            try {
                val probs = RemHrvClassifierModel.score(hrvFeatures)
                if (probs != null && probs.size >= 2) probs[1].toFloat() else null
            } catch (e: Exception) {
                null
            }
        } else null

        // 7. Staging Decision & 3-Epoch Temporal Hysteresis Filter
        // Option 1: PAAWS R2 True-HRV model is active in dual mode when ECG is primary and not shadow pre-warming
        val isPaawsActive = isDualEcgMode && hrvRemProbability != null && !isShadowPreWarming && isEcgPrimary

        // REM physiological pattern: Autonomic storm surge + intra-epoch or short-term dispersion
        val isAutonomicSurge = (hrSurgePercent >= 0.07f && combinedDispersion >= 1.5f) ||
                (hrSurgePercent >= 0.11f) ||
                (combinedDispersion >= 2.0f && hrSurgePercent >= 0.04f)

        var tentativeStage: SleepStage
        var tentativeRemConfidence = 0.0f

        if (isSustainedAwake) {
            tentativeStage = SleepStage.AWAKE
            tentativeRemConfidence = 0.0f
        } else if (isPaawsActive) {
            // Option 1: PAAWS R2 True-HRV + Actigraphy Dual-Modality Decision Path (141 subjects ground truth)
            val prob = hrvRemProbability!!
            if (prob >= cueConfig.confidenceThreshold && atoniaScore > 0.50f) {
                tentativeStage = SleepStage.REM
                tentativeRemConfidence = prob
            } else if (hrSurgePercent < 0.04f && combinedDispersion < 1.1f && atoniaScore > 0.80f) {
                tentativeStage = SleepStage.DEEP
                tentativeRemConfidence = 0.0f
            } else {
                tentativeStage = SleepStage.LIGHT
                tentativeRemConfidence = prob * 0.3f
            }
        } else if ((isMlMode || isDualEcgMode) && mlRemProbability != null) {
            // Option 2: AI Machine Learning Decision Path (Mi Band 16-dim BIDSleep tree model)
            // Primary path for ML_MODEL mode, and Hot-Standby Failover for AD8232_DUAL mode!
            if (mlRemProbability >= cueConfig.confidenceThreshold && atoniaScore > 0.60f) {
                tentativeStage = SleepStage.REM
                tentativeRemConfidence = mlRemProbability
            } else if (hrSurgePercent < 0.04f && combinedDispersion < 1.1f && atoniaScore > 0.80f) {
                tentativeStage = SleepStage.DEEP
                tentativeRemConfidence = 0.0f
            } else {
                tentativeStage = SleepStage.LIGHT
                tentativeRemConfidence = mlRemProbability * 0.3f
            }
        } else if (atoniaScore > 0.70f) {
            // Option 3: Heuristic Rule Decision Path (Fallback when ML is unavailable)
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
            tentativeStage = if (isSustainedAwake) SleepStage.AWAKE else SleepStage.LIGHT
            tentativeRemConfidence = 0.05f
        }

        // 7.1 AASM Physiological State Machine Circuit Breakers (Clinical Ground Truth Guardrails)
        if (tentativeStage == SleepStage.REM) {
            // Circuit Breaker 1: Muscle Atonia Veto (One-Vote Veto)
            // REM sleep physiologically mandates postural muscle tone abolition.
            if (atoniaScore < 0.60f || isMicroMovement || isSubstantialMovement) {
                tentativeStage = if (isSubstantialMovement) SleepStage.AWAKE else SleepStage.LIGHT
                tentativeRemConfidence = 0.0f
            }
            // Circuit Breaker 2: Sleep Onset 60-Minute Latency Gate
            // Physiological REM latency is >=60-90 min; REM occurring before 60m is physiologically anomalous.
            else if (!isSleepOnsetDetected || elapsedMinutes < 60.0f) {
                tentativeStage = SleepStage.LIGHT
                tentativeRemConfidence = 0.10f
            }
            // Circuit Breaker 3: Deep Sleep Direct Jump Taboo (N3 -> REM forbidden)
            // AASM sleep architecture strictly requires cycling through N2 (Light) before entering REM.
            else if (lastEstablishedStage == SleepStage.DEEP) {
                tentativeStage = SleepStage.LIGHT
                tentativeRemConfidence = 0.15f
            }
        }

        // Temporal Hysteresis Filter (AASM Circuit Breaker 4: 90-Second Consecutive Smoothing):
        // Eliminates transient artifacts; REM strictly requires 3 consecutive epochs (90s continuous confirmation)
        val determinedStage: SleepStage
        if (isSustainedAwake) {
            // Confirmed sustained waking movement across epochs
            lastEstablishedStage = SleepStage.AWAKE
            pendingStage = null
            pendingStageCount = 0
            determinedStage = SleepStage.AWAKE
        } else if (tentativeStage == lastEstablishedStage) {
            pendingStage = null
            pendingStageCount = 0
            determinedStage = lastEstablishedStage
        } else {
            // Stage transition requested.
            // Entering REM strictly requires 3 consecutive epochs (90 seconds).
            val requiredConfirmEpochs = when {
                tentativeStage == SleepStage.REM -> 3 // 90 seconds consecutive REM smoothing
                lastEstablishedStage == SleepStage.REM -> 3
                tentativeStage == SleepStage.AWAKE -> 3
                lastEstablishedStage == SleepStage.AWAKE -> {
                    // Fast recovery from AWAKE when subject is quiet and resting
                    if (actigraphyMagnitude < 0.04f && peakActigraphy < 0.12f) 1 else 2
                }
                else -> 2
            }

            if (pendingStage == tentativeStage) {
                pendingStageCount++
                if (pendingStageCount >= requiredConfirmEpochs) {
                    lastEstablishedStage = tentativeStage
                    pendingStage = null
                    pendingStageCount = 0
                    determinedStage = tentativeStage
                } else {
                    // Hold established stage to smooth away transient artifacts
                    determinedStage = lastEstablishedStage
                }
            } else {
                pendingStage = tentativeStage
                pendingStageCount = 1
                if (pendingStageCount >= requiredConfirmEpochs) {
                    // Fast path for 1-epoch confirmation (e.g. recovering from AWAKE when motionless)
                    lastEstablishedStage = tentativeStage
                    pendingStage = null
                    pendingStageCount = 0
                    determinedStage = tentativeStage
                } else {
                    determinedStage = lastEstablishedStage
                }
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
                (isSleepOnsetDetected && elapsedMinutes >= 60.0f) &&
                meetsConfidence

        val shouldTrigger = isEligible && isCooldownPassed

        val triggerReason = when {
            shouldTrigger -> {
                if (isPaawsActive) {
                    "PAAWS R2 真心电双模态命中做梦期 (置信度 ${(remConfidence * 100).toInt()}% >= 门槛 ${(cueConfig.confidenceThreshold * 100).toInt()}%)"
                } else if (isDualEcgMode && mlRemProbability != null) {
                    "手环16维AI模型(双模态热备接管)命中做梦期 (置信度 ${(remConfidence * 100).toInt()}% >= 门槛 ${(cueConfig.confidenceThreshold * 100).toInt()}%)"
                } else if (isMlMode && mlRemProbability != null) {
                    "BIDSleep 16维AI模型印证命中REM期 (置信度 ${(remConfidence * 100).toInt()}% >= 门槛 ${(cueConfig.confidenceThreshold * 100).toInt()}%)"
                } else {
                    "多模态规则印证命中REM期 (置信度 ${(remConfidence * 100).toInt()}% | 周期先验 ${(ultradianPrior * 100).toInt()}%)"
                }
            }
            isVetoedByMovement -> "手腕体动一票否决"
            !isSleepOnsetDetected -> "正在监测入睡状态 (静息沉淀 ${sustainedStillnessEpochs}/16)"
            elapsedMinutes < 60f -> "处于入睡前60分钟REM生理潜伏期 (已入睡 ${elapsedMinutes.toInt()}/60分钟)"
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
