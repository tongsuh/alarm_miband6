package com.flashalarm.miband.domain.algorithm

import android.util.Log

/**
 * EOG Signal Quality state enum.
 */
enum class EogSignalQuality(val displayName: String) {
    OFFLINE("EOG未连"),
    LEADS_OFF("电极脱落"),
    NOISY_SATURATED("伪迹饱和"),
    CLEAN_RESTING("静息常态"),
    CLEAN_BURSTING("眼动爆发")
}

/**
 * EogAdaptationController
 * Manages opportunistic residual boosting from ESP32-EOG signals for REM sleep staging.
 *
 * Ground rules:
 * 1. Opportunistic Residual Boosting: EOG raw boost (+1.20 ~ +1.60 logit), yielding effective boost (+0.50 ~ +0.95 logit) via Hermite soft gating.
 * 2. Never Veto Base Model: When offline, resting, detached, clipped, or contaminated by wrist motion,
 *    EOG weight/boost immediately drops to 0.0f, smoothly falling back to the 1Hz wrist base model.
 * 3. Multi-tier Artifact Gating:
 *    - Hardware leads-off (!isContactOk) -> LEADS_OFF
 *    - Rail-to-rail clipping / pillow contact (isClipped) -> NOISY_SATURATED
 *    - Cross-modal wrist motion interference (wristMotionMean > 0.09g) -> NOISY_SATURATED
 */
class EogAdaptationController {

    companion object {
        private const val TAG = "EogAdaptation"
        const val WRIST_MOTION_SUPPRESSION_THRESHOLD_G = 0.09f
        const val MIN_BURSTS_FOR_BURSTING_STATE = 3
        const val SACCADE_REFRACTORY_MS = 450L // 450ms refractory window (400ms~500ms) to merge bipolar double-edges
    }

    var signalQuality: EogSignalQuality = EogSignalQuality.OFFLINE
        private set

    var currentLogitBoost: Float = 0.0f
        private set

    var consecutiveBurstEpochs: Int = 0
        private set

    var lastSaccadePulseTimeMs: Long = 0L
        private set

    var debouncedBurstCountInEpoch: Int = 0
        private set

    /**
     * Filters incoming raw saccade pulse detections using a physiological refractory period (450ms).
     * Saccadic eye movements in electrooculography naturally create a bipolar voltage excursion:
     * an initial step followed by a return step / settling jitter within 250~350ms.
     * This filter merges adjacent pulses within the 400ms~500ms refractory window into one single
     * eye movement event, preventing double-edge false triggers.
     *
     * @param timestampMs Timestamp of the detected pulse
     * @return true if this is an accepted new eye movement event, false if it is a debounced/suppressed rebound
     */
    @Synchronized
    fun filterSaccadePulse(timestampMs: Long = System.currentTimeMillis()): Boolean {
        if (lastSaccadePulseTimeMs > 0L && (timestampMs - lastSaccadePulseTimeMs) < SACCADE_REFRACTORY_MS) {
            Log.d(TAG, "Suppressed rebound saccade pulse within ${SACCADE_REFRACTORY_MS}ms refractory window (${timestampMs - lastSaccadePulseTimeMs}ms)")
            return false
        }
        lastSaccadePulseTimeMs = timestampMs
        debouncedBurstCountInEpoch++
        return true
    }

    /**
     * Alias for filterSaccadePulse.
     */
    fun onSaccadeDetected(timestampMs: Long = System.currentTimeMillis()): Boolean = filterSaccadePulse(timestampMs)

    /**
     * Evaluates a 30-second epoch of EOG data with artifact cross-rejection.
     *
     * @param burstCount Number of eye movement bursts detected during the 30-second epoch.
     * @param isContactOk True if skin-electrode impedance/contact was valid throughout the epoch.
     * @param isClipped True if amplitude saturation/clipping occurred during the epoch.
     * @param wristMotionMean Average wrist movement magnitude from the band during the epoch (g).
     * @param isBleConnected True if the EOG peripheral BLE link is active.
     * @return Logit residual boost to be added to the 1Hz base classifier logit.
     */
    @Synchronized
    fun updateEpoch(
        burstCount: Int,
        isContactOk: Boolean,
        isClipped: Boolean,
        wristMotionMean: Float,
        isBleConnected: Boolean = true
    ): Float {
        val effectiveBursts = if (debouncedBurstCountInEpoch > 0) {
            debouncedBurstCountInEpoch
        } else {
            burstCount
        }
        debouncedBurstCountInEpoch = 0

        when {
            // 1. BLE Disconnected
            !isBleConnected -> {
                signalQuality = EogSignalQuality.OFFLINE
                currentLogitBoost = 0.0f
                consecutiveBurstEpochs = 0
            }

            // 2. Hardware Leads-Off (LOD)
            !isContactOk -> {
                signalQuality = EogSignalQuality.LEADS_OFF
                currentLogitBoost = 0.0f
                consecutiveBurstEpochs = 0
                Log.d(TAG, "EOG leads-off detected. Boost zeroed.")
            }

            // 3. Amplifier Saturation (pillow contact / rail clamping) OR Wrist Motion Cross-Contamination
            isClipped || wristMotionMean > WRIST_MOTION_SUPPRESSION_THRESHOLD_G -> {
                signalQuality = EogSignalQuality.NOISY_SATURATED
                currentLogitBoost = 0.0f
                consecutiveBurstEpochs = 0
                if (isClipped) {
                    Log.d(TAG, "EOG saturation clipping detected. Boost zeroed.")
                } else {
                    Log.d(TAG, "Wrist motion (${wristMotionMean}g > ${WRIST_MOTION_SUPPRESSION_THRESHOLD_G}g) suppressed EOG. Boost zeroed.")
                }
            }

            // 4. Clean Eye Movement Bursts (REM Confirmation)
            effectiveBursts >= MIN_BURSTS_FOR_BURSTING_STATE -> {
                signalQuality = EogSignalQuality.CLEAN_BURSTING
                consecutiveBurstEpochs++

                // Decisive, morphology-gated Logit boost scaling:
                // When clean saccades are validated (3+ bursts, no motion, no clipping),
                // base burst gives +1.20f. Each additional burst over threshold adds +0.10f,
                // plus a consecutive epoch persistence bonus (+0.20f), capped between [+1.20f, +1.60f].
                val countBonus = ((effectiveBursts - MIN_BURSTS_FOR_BURSTING_STATE) * 0.10f).coerceIn(0.0f, 0.40f)
                val persistenceBonus = if (consecutiveBurstEpochs >= 2) 0.20f else 0.0f
                currentLogitBoost = (1.20f + countBonus + persistenceBonus).coerceIn(1.20f, 1.60f)

                Log.i(TAG, "Clean EOG burst detected (bursts=$effectiveBursts, consecutive=$consecutiveBurstEpochs). Logit boost = +$currentLogitBoost")
            }

            // 5. Clean Resting (Baseline / NREM or quiet intervals)
            else -> {
                signalQuality = EogSignalQuality.CLEAN_RESTING
                currentLogitBoost = 0.0f
                consecutiveBurstEpochs = 0
            }
        }

        return currentLogitBoost
    }

    @Synchronized
    fun reset() {
        signalQuality = EogSignalQuality.OFFLINE
        currentLogitBoost = 0.0f
        consecutiveBurstEpochs = 0
        lastSaccadePulseTimeMs = 0L
        debouncedBurstCountInEpoch = 0
    }
}
