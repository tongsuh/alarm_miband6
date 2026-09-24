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
 * 1. Opportunistic Residual Boosting: EOG boosts REM probability when clean saccade bursts occur (+1.2 ~ +2.2 logit).
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
        const val MIN_BURSTS_FOR_BURSTING_STATE = 2
    }

    var signalQuality: EogSignalQuality = EogSignalQuality.OFFLINE
        private set

    var currentLogitBoost: Float = 0.0f
        private set

    var consecutiveBurstEpochs: Int = 0
        private set

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
            burstCount >= MIN_BURSTS_FOR_BURSTING_STATE -> {
                signalQuality = EogSignalQuality.CLEAN_BURSTING
                consecutiveBurstEpochs++

                // Logit boost scaling:
                // Base burst gives +1.3f. Each additional burst over threshold adds +0.15f,
                // plus a consecutive epoch persistence bonus (+0.2f), capped between [+1.2f, +2.2f].
                val countBonus = ((burstCount - MIN_BURSTS_FOR_BURSTING_STATE) * 0.15f).coerceIn(0.0f, 0.6f)
                val persistenceBonus = if (consecutiveBurstEpochs >= 2) 0.2f else 0.0f
                currentLogitBoost = (1.3f + countBonus + persistenceBonus).coerceIn(1.2f, 2.2f)

                Log.i(TAG, "Clean EOG burst detected (bursts=$burstCount, consecutive=$consecutiveBurstEpochs). Logit boost = +$currentLogitBoost")
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
    }
}
