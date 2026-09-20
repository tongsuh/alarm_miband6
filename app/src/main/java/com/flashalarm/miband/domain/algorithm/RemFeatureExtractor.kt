package com.flashalarm.miband.domain.algorithm

import kotlin.math.max
import kotlin.math.sqrt

/**
 * RemFeatureExtractor
 * Maintains an online sliding window of 21 30-second epochs (~10.5 minutes),
 * allowing decision of the target epoch (index 10, i.e., 5 minutes in the past)
 * with complete future atonia and HR confirmation within the 5-minute latency tolerance.
 */
class RemFeatureExtractor {

    data class RawEpoch(
        val epochIndex: Int,
        val meanHr: Float,
        val stdHr: Float,
        val meanMotion: Float,
        val peakMotion: Float
    )

    private val epochBuffer = ArrayDeque<RawEpoch>()
    private val hrHistory30m = ArrayDeque<Float>() // Up to 60 epochs (30 mins) for dynamic baseline

    fun reset() {
        epochBuffer.clear()
        hrHistory30m.clear()
    }

    /**
     * Push a newly completed 30-second epoch into the buffer.
     * @return 11-dimensional double array for RemClassifierModel.score(features) if buffer is ready (>=21 epochs),
     *         or null if initial 5-minute warm-up buffer is still filling.
     */
    fun pushEpoch(
        epochIndex: Int,
        meanHr: Float,
        stdHr: Float,
        meanMotion: Float,
        peakMotion: Float
    ): DoubleArray? {
        val epoch = RawEpoch(epochIndex, meanHr, stdHr, meanMotion, peakMotion)
        epochBuffer.addLast(epoch)
        hrHistory30m.addLast(meanHr)
        if (hrHistory30m.size > 60) hrHistory30m.removeFirst()

        // We need at least 21 epochs to evaluate the target epoch (index 10)
        if (epochBuffer.size < 21) {
            return null
        }
        if (epochBuffer.size > 21) {
            epochBuffer.removeFirst()
        }

        // Target epoch is exactly in the center (index 10): 10 past epochs, 1 target, 10 future epochs
        val target = epochBuffer[10]

        // 1. Current epoch features
        val hrMeanCurr = target.meanHr.toDouble()
        val hrStdCurr = target.stdHr.toDouble()
        val motionMeanCurr = target.meanMotion.toDouble()
        val motionMaxCurr = target.peakMotion.toDouble()

        // 2. Past 5m (indices 0..9)
        var sumPastHr = 0.0
        var sumPastMotion = 0.0
        for (i in 0 until 10) {
            sumPastHr += epochBuffer[i].meanHr
            sumPastMotion += epochBuffer[i].meanMotion
        }
        val hrMeanPast5m = sumPastHr / 10.0
        val motionMeanPast5m = sumPastMotion / 10.0

        // 3. Future 5m (indices 11..20) [5-minute confirmation buffer]
        var sumFutureHr = 0.0
        var sumFutureMotion = 0.0
        for (i in 11 until 21) {
            sumFutureHr += epochBuffer[i].meanHr
            sumFutureMotion += epochBuffer[i].meanMotion
        }
        val hrMeanFuture5m = sumFutureHr / 10.0
        val motionMeanFuture5m = sumFutureMotion / 10.0

        // 4. Centered 10m context HR standard deviation
        var sumAllHr = 0.0
        for (ep in epochBuffer) sumAllHr += ep.meanHr
        val meanAllHr = sumAllHr / 21.0
        var sumSqDiff = 0.0
        for (ep in epochBuffer) {
            val diff = ep.meanHr - meanAllHr
            sumSqDiff += diff * diff
        }
        val hrStdContext = sqrt(sumSqDiff / 21.0)

        // 5. Dynamic 30-minute nocturnal baseline (10th percentile of past 30 minutes)
        val sortedHr = hrHistory30m.sorted()
        val p10Index = (sortedHr.size * 0.10f).toInt().coerceIn(0, sortedHr.size - 1)
        val baselineHr = max(sortedHr[p10Index].toDouble(), 40.0)
        val hrSurgeBaseline = (hrMeanCurr - baselineHr) / baselineHr

        // 6. Time since recording start in minutes
        val timeSinceStartMin = (target.epochIndex * 0.5)

        // Must match FEATURE_NAMES order exactly:
        return doubleArrayOf(
            hrMeanCurr,           // [0]
            hrStdCurr,            // [1]
            motionMeanCurr,       // [2]
            motionMaxCurr,        // [3]
            hrSurgeBaseline,      // [4]
            hrMeanPast5m,         // [5]
            hrMeanFuture5m,       // [6]
            hrStdContext,         // [7]
            motionMeanPast5m,     // [8]
            motionMeanFuture5m,   // [9]
            timeSinceStartMin     // [10]
        )
    }
}
