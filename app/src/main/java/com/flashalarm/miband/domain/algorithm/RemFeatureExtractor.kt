package com.flashalarm.miband.domain.algorithm

import kotlin.math.max
import kotlin.math.sqrt

/**
 * RemFeatureExtractor
 * Maintains an online sliding window of 17 30-second epochs (~8.5 minutes),
 * allowing decision of the target epoch (index 10, i.e., 3 minutes in the past)
 * with complete future atonia and HR confirmation within the 3-minute latency tolerance.
 *
 * Extracts a 16-dimensional physiological first-principles feature vector aligned
 * identically with BIDSleep multi-night ML training:
 *   [0] hr_mean_curr
 *   [1] hr_std_curr
 *   [2] motion_mean_curr
 *   [3] motion_peak_curr
 *   [4] hr_surge_slowwave_baseline
 *   [5] hr_mean_past_5m
 *   [6] hr_mean_future_3m
 *   [7] hr_step_contrast
 *   [8] hr_turbulence_1hz
 *   [9] motion_mean_past_5m
 *   [10] motion_future_atonia_ratio
 *   [11] circadian_cycle_sin
 *   [12] circadian_cycle_cos
 *   [13] time_since_onset_min
 *   [14] hr_std_context_10m
 *   [15] motion_entropy_context
 */
class RemFeatureExtractor {

    data class RawEpoch(
        val epochIndex: Int,
        val meanHr: Float,
        val stdHr: Float,
        val meanMotion: Float,
        val peakMotion: Float,
        val timeSinceOnsetMin: Double
    )

    private val epochBuffer = ArrayDeque<RawEpoch>()
    private val hrHistory60m = ArrayDeque<Float>() // Up to 120 epochs (60 mins) for dynamic baseline

    fun reset() {
        epochBuffer.clear()
        hrHistory60m.clear()
    }

    /**
     * Push a newly completed 30-second epoch into the buffer.
     * @param epochIndex Monotonically increasing epoch sequence index
     * @param meanHr Mean heart rate across the 30-second epoch (bpm)
     * @param stdHr Standard deviation of heart rate within the epoch (bpm)
     * @param meanMotion Mean deviation from 1g Earth gravity (|VM - 1g|)
     * @param peakMotion Peak deviation from 1g Earth gravity in current epoch (g)
     * @param timeSinceOnsetMin Minutes elapsed since physiological sleep onset (or -1.0 if not yet set)
     * @return 16-dimensional double array for RemClassifierModel.score(features) if buffer is ready (>=17 epochs),
     *         or null if initial 3-minute warm-up buffer is still filling.
     */
    fun pushEpoch(
        epochIndex: Int,
        meanHr: Float,
        stdHr: Float,
        meanMotion: Float,
        peakMotion: Float,
        timeSinceOnsetMin: Double = -1.0
    ): DoubleArray? {
        val actualTimeSinceOnset = if (timeSinceOnsetMin >= 0.0) timeSinceOnsetMin else (epochIndex * 0.5)
        val epoch = RawEpoch(epochIndex, meanHr, stdHr, meanMotion, peakMotion, actualTimeSinceOnset)
        epochBuffer.addLast(epoch)
        hrHistory60m.addLast(meanHr)
        if (hrHistory60m.size > 120) hrHistory60m.removeFirst()

        // 17 epochs required: 10 past epochs (indices 0..9) + 1 target (index 10) + 6 future epochs (indices 11..16)
        if (epochBuffer.size < 17) {
            return null
        }
        if (epochBuffer.size > 17) {
            epochBuffer.removeFirst()
        }

        // Target epoch is index 10 (3 minutes in past, supported by 6 future confirmation epochs)
        val target = epochBuffer[10]

        // 0. Current epoch features
        val hrMeanCurr = target.meanHr.toDouble()
        val hrStdCurr = target.stdHr.toDouble()
        val motionMeanCurr = target.meanMotion.toDouble()
        val motionPeakCurr = target.peakMotion.toDouble()

        // 4. Dynamic nocturnal baseline (10th percentile of past 30-60 minutes)
        val sortedHr = hrHistory60m.sorted()
        val p10Index = (sortedHr.size * 0.10f).toInt().coerceIn(0, sortedHr.size - 1)
        val baselineHr = max(sortedHr[p10Index].toDouble(), 40.0)
        val hrSurgeSlowwaveBaseline = (hrMeanCurr - baselineHr) / baselineHr

        // 5 & 9. Past 5 minutes (10 epochs: indices 0..9)
        var sumPastHr = 0.0
        var sumPastMotion = 0.0
        for (i in 0 until 10) {
            sumPastHr += epochBuffer[i].meanHr
            sumPastMotion += epochBuffer[i].meanMotion
        }
        val hrMeanPast5m = sumPastHr / 10.0
        val motionMeanPast5m = sumPastMotion / 10.0

        // 6 & 10. Future 3 minutes (6 epochs: indices 11..16) [3-minute causal confirmation buffer]
        var sumFutureHr = 0.0
        var futureAtoniaCount = 0
        for (i in 11 until 17) {
            sumFutureHr += epochBuffer[i].meanHr
            if (epochBuffer[i].meanMotion < 0.045f) {
                futureAtoniaCount++
            }
        }
        val hrMeanFuture3m = sumFutureHr / 6.0
        val motionFutureAtoniaRatio = futureAtoniaCount / 6.0

        // 7. Future 3m vs Past 5m step contrast
        val hrStepContrast = hrMeanFuture3m - hrMeanPast5m

        // 8. 1Hz pulse local dispersion coefficient (std / mean)
        val hrTurbulence1hz = if (hrMeanCurr > 0.0) hrStdCurr / hrMeanCurr else 0.0

        // 11 & 12. Circadian ultradian cycle sin & cos (90-minute sleep cycle)
        val tMin = target.timeSinceOnsetMin
        val angle = 2.0 * Math.PI * (tMin / 90.0)
        val circadianCycleSin = kotlin.math.sin(angle)
        val circadianCycleCos = kotlin.math.cos(angle)

        // 13. Minutes elapsed since sleep onset
        val timeSinceOnsetVal = tMin

        // 14. Centered context HR standard deviation across all 17 epochs (indices 0..16)
        var sumAllHr = 0.0
        for (ep in epochBuffer) sumAllHr += ep.meanHr
        val meanAllHr = sumAllHr / 17.0
        var sumSqDiff = 0.0
        for (ep in epochBuffer) {
            val diff = ep.meanHr - meanAllHr
            sumSqDiff += diff * diff
        }
        val hrStdContext10m = sqrt(sumSqDiff / 17.0)

        // 15. 5-bin Shannon entropy of motion in context window
        // Bins: [0, 0.01), [0.01, 0.03), [0.03, 0.07), [0.07, 0.15), [0.15, inf)
        val counts = IntArray(5)
        for (ep in epochBuffer) {
            val m = ep.meanMotion
            when {
                m < 0.01f -> counts[0]++
                m < 0.03f -> counts[1]++
                m < 0.07f -> counts[2]++
                m < 0.15f -> counts[3]++
                else -> counts[4]++
            }
        }
        var motionEntropy = 0.0
        val n = 17.0
        for (c in counts) {
            if (c > 0) {
                val p = c / n
                motionEntropy -= p * kotlin.math.ln(p)
            }
        }
        val motionEntropyContext = motionEntropy

        // Vector order strictly matches Python FEATURE_NAMES:
        return doubleArrayOf(
            hrMeanCurr,                 // [0]
            hrStdCurr,                  // [1]
            motionMeanCurr,             // [2]
            motionPeakCurr,             // [3]
            hrSurgeSlowwaveBaseline,    // [4]
            hrMeanPast5m,               // [5]
            hrMeanFuture3m,             // [6]
            hrStepContrast,             // [7]
            hrTurbulence1hz,            // [8]
            motionMeanPast5m,           // [9]
            motionFutureAtoniaRatio,    // [10]
            circadianCycleSin,          // [11]
            circadianCycleCos,          // [12]
            timeSinceOnsetVal,          // [13]
            hrStdContext10m,            // [14]
            motionEntropyContext        // [15]
        )
    }
}
