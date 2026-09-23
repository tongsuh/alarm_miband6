package com.flashalarm.miband.domain.algorithm

import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real-time True-HRV & Actigraphy Dual-Modality Feature Extractor for FlashAlarm.
 * Trained on PAAWS R2 (141 Subjects, 253 Nights).
 *
 * Buffers:
 *   - Millisecond R-R intervals from AD8232 / ESP32-C3
 *   - 3-axis Actigraphy vector magnitude from Xiaomi Mi Band 6
 *   - Maintains rolling 21-epoch buffer (5m past + current + 5m future)
 */
class RemHrvFeatureExtractor {

    companion object {
        const val EPOCH_DURATION_MS = 30_000L
        const val WINDOW_SIZE = 21       // 10 past + 1 current + 10 future epochs (~10.5 minutes)
        const val TARGET_INDEX = 10      // Center epoch (5-min latency allowance)
    }

    data class EpochRawData(
        val epochIndex: Long,
        val meanRr: Double,
        val hr: Double,
        val rmssd: Double,
        val sdnn: Double,
        val pnn50: Double,
        val cvRr: Double,
        val hrSurge: Double,
        val autonomicBalance: Double,
        val motionMean: Double,
        val motionMax: Double
    )

    private val currentEpochRrs = ArrayList<Double>()
    private val currentEpochMotions = ArrayList<Double>()
    private var epochStartTimeMs: Long = 0L
    private var currentEpochIndex: Long = 0L

    // Online running statistics for Z-score normalization
    private var statCount = 0
    private var sumMeanRr = 0.0; private var sumSqMeanRr = 0.0
    private var sumHr = 0.0;     private var sumSqHr = 0.0
    private var sumRmssd = 0.0;  private var sumSqRmssd = 0.0
    private var sumSdnn = 0.0;   private var sumSqSdnn = 0.0
    private var sumPnn50 = 0.0;  private var sumSqPnn50 = 0.0
    private var sumCv = 0.0;     private var sumSqCv = 0.0
    private var sumAuto = 0.0;   private var sumSqAuto = 0.0
    private var sumMotM = 0.0;   private var sumSqMotM = 0.0
    private var sumMotX = 0.0;   private var sumSqMotX = 0.0
    private var minHrTracked = 200.0

    private val buffer = ArrayDeque<EpochRawData>()

    /**
     * Push incoming millisecond R-R interval from ESP32-C3 / BLE Heart Rate Service.
     */
    @Synchronized
    fun pushRrInterval(rrMs: Double, timestampMs: Long = 0L) {
        if (rrMs in 300.0..2000.0) {
            currentEpochRrs.add(rrMs)
        }
    }

    /**
     * Push incoming Actigraphy motion magnitude from Mi Band 6.
     */
    @Synchronized
    fun pushMotion(motionG: Double, timestampMs: Long = 0L) {
        currentEpochMotions.add(motionG)
    }

    private var consecutiveSparseEpochs = 0

    /**
     * Direct push at the end of an epoch (if synchronized externally).
     * @return 18-element feature array for RemHrvClassifierModel.score(), or null if buffering
     */
    @Synchronized
    fun pushEpoch(
        epochIdx: Long,
        meanRr: Double,
        hr: Double,
        rmssd: Double,
        sdnn: Double,
        pnn50: Double,
        cvRr: Double,
        motionMean: Double,
        motionMax: Double
    ): DoubleArray? {
        minHrTracked = min(minHrTracked, hr)
        val hrSurge = (hr - minHrTracked) / max(40.0, minHrTracked)
        val autonomicBalance = sdnn / max(1.0, rmssd)

        updateStats(meanRr, hr, rmssd, sdnn, pnn50, cvRr, autonomicBalance, motionMean, motionMax)

        val epoch = EpochRawData(
            epochIndex = epochIdx,
            meanRr = meanRr,
            hr = hr,
            rmssd = rmssd,
            sdnn = sdnn,
            pnn50 = pnn50,
            cvRr = cvRr,
            hrSurge = hrSurge,
            autonomicBalance = autonomicBalance,
            motionMean = motionMean,
            motionMax = motionMax
        )

        // Enforce temporal continuity with short-term gap tolerance:
        // Gap == 2L: exactly 1 epoch missed -> bridge with prior epoch to prevent clearing buffer
        // Gap > 2L: substantial disruption (>60s) -> reset buffer
        if (buffer.isNotEmpty()) {
            val gap = epoch.epochIndex - buffer.last().epochIndex
            if (gap == 2L) {
                val bridgeEpoch = buffer.last().copy(epochIndex = buffer.last().epochIndex + 1L)
                buffer.addLast(bridgeEpoch)
                if (buffer.size > WINDOW_SIZE) buffer.removeFirst()
            } else if (gap > 2L || gap < 1L) {
                buffer.clear()
            }
        }

        buffer.addLast(epoch)
        if (buffer.size > WINDOW_SIZE) buffer.removeFirst()

        return if (buffer.size == WINDOW_SIZE) extractFeatures() else null
    }

    private fun checkEpochComplete(timestampMs: Long): DoubleArray? {
        if (epochStartTimeMs == 0L) epochStartTimeMs = timestampMs

        if (timestampMs - epochStartTimeMs >= EPOCH_DURATION_MS) {
            val epoch = completeEpoch(currentEpochIndex++)
            currentEpochRrs.clear()
            currentEpochMotions.clear()
            epochStartTimeMs = timestampMs

            if (epoch != null) {
                if (buffer.isNotEmpty()) {
                    val gap = epoch.epochIndex - buffer.last().epochIndex
                    if (gap == 2L) {
                        val bridgeEpoch = buffer.last().copy(epochIndex = buffer.last().epochIndex + 1L)
                        buffer.addLast(bridgeEpoch)
                        if (buffer.size > WINDOW_SIZE) buffer.removeFirst()
                    } else if (gap > 2L || gap < 1L) {
                        buffer.clear()
                    }
                }
                buffer.addLast(epoch)
                if (buffer.size > WINDOW_SIZE) buffer.removeFirst()
                if (buffer.size == WINDOW_SIZE) return extractFeatures()
            }
        }
        return null
    }

    /**
     * Triggered directly at the 30-second epoch boundary.
     * Completes current epoch buffer and returns 18-dim feature array if 21-epoch context window is full.
     */
    @Synchronized
    fun onEpochTick(epochIdx: Long, fallbackMotionMean: Double = 0.0, fallbackMotionMax: Double = 0.0): DoubleArray? {
        if (currentEpochMotions.isEmpty() && (fallbackMotionMean > 0.0 || fallbackMotionMax > 0.0)) {
            currentEpochMotions.add(fallbackMotionMean)
            currentEpochMotions.add(fallbackMotionMax)
        }
        val epoch = completeEpoch(epochIdx)
        currentEpochRrs.clear()
        currentEpochMotions.clear()
        epochStartTimeMs = 0L

        if (epoch != null) {
            // Check for temporal continuity with short-term bridge tolerance
            if (buffer.isNotEmpty()) {
                val gap = epoch.epochIndex - buffer.last().epochIndex
                if (gap == 2L) {
                    // Exactly 1 epoch missed: bridge it with last valid epoch to protect 21-epoch context
                    val bridgeEpoch = buffer.last().copy(epochIndex = buffer.last().epochIndex + 1L)
                    buffer.addLast(bridgeEpoch)
                    if (buffer.size > WINDOW_SIZE) buffer.removeFirst()
                } else if (gap > 2L || gap < 1L) {
                    // Severe gap > 1 epoch (>60s) or out-of-order: clear buffer
                    buffer.clear()
                }
            }
            buffer.addLast(epoch)
            if (buffer.size > WINDOW_SIZE) buffer.removeFirst()
            if (buffer.size == WINDOW_SIZE) return extractFeatures()
        }
        return null
    }

    /**
     * Signal leads-off event with debouncing support.
     * @param isConfirmed If true (debounced >= 10-15s), performs complete purge of the 21-epoch buffer.
     *                    If false (transient 1~2s contact flicker), only clears current raw epoch buffers
     *                    without destroying the historical 21-epoch rolling window context.
     */
    @Synchronized
    fun onLeadsOff(isConfirmed: Boolean = false) {
        currentEpochRrs.clear()
        currentEpochMotions.clear()
        epochStartTimeMs = 0L
        if (isConfirmed) {
            buffer.clear()
            consecutiveSparseEpochs = 0
        }
    }

    private fun completeEpoch(epochIdx: Long): EpochRawData? {
        val n = currentEpochRrs.size
        if (n >= 3) {
            consecutiveSparseEpochs = 0
            val meanRr = currentEpochRrs.average()
            if (meanRr <= 0.0) return null
            val hr = 60000.0 / meanRr

            var varSum = 0.0
            for (rr in currentEpochRrs) varSum += (rr - meanRr).pow(2)
            val sdnn = sqrt(varSum / n)

            var diffSqSum = 0.0
            var count50 = 0
            for (i in 0 until n - 1) {
                val diff = currentEpochRrs[i + 1] - currentEpochRrs[i]
                diffSqSum += diff * diff
                if (kotlin.math.abs(diff) > 50.0) count50++
            }
            val rmssd = if (n > 1) sqrt(diffSqSum / (n - 1)) else 0.0
            val pnn50 = if (n > 1) (count50.toDouble() / (n - 1)) * 100.0 else 0.0
            val cvRr = if (meanRr > 0) sdnn / meanRr else 0.0

            minHrTracked = min(minHrTracked, hr)
            val hrSurge = (hr - minHrTracked) / max(40.0, minHrTracked)
            val autonomicBalance = sdnn / max(1.0, rmssd)

            val motionMean = if (currentEpochMotions.isNotEmpty()) currentEpochMotions.average() else 0.0
            val motionMax = if (currentEpochMotions.isNotEmpty()) currentEpochMotions.maxOrNull() ?: 0.0 else 0.0

            updateStats(meanRr, hr, rmssd, sdnn, pnn50, cvRr, autonomicBalance, motionMean, motionMax)

            return EpochRawData(
                epochIndex = epochIdx,
                meanRr = meanRr,
                hr = hr,
                rmssd = rmssd,
                sdnn = sdnn,
                pnn50 = pnn50,
                cvRr = cvRr,
                hrSurge = hrSurge,
                autonomicBalance = autonomicBalance,
                motionMean = motionMean,
                motionMax = motionMax
            )
        }

        // Short-term fault tolerance:
        // If n < 3 (dropped beats or transient silence) and buffer has prior valid data:
        // Allow forward-fill imputation for up to 1 single isolated epoch
        if (buffer.isNotEmpty() && consecutiveSparseEpochs < 1) {
            consecutiveSparseEpochs++
            val last = buffer.last()
            val motionMean = if (currentEpochMotions.isNotEmpty()) currentEpochMotions.average() else last.motionMean
            val motionMax = if (currentEpochMotions.isNotEmpty()) currentEpochMotions.maxOrNull() ?: last.motionMax else last.motionMax

            return EpochRawData(
                epochIndex = epochIdx,
                meanRr = last.meanRr,
                hr = last.hr,
                rmssd = last.rmssd,
                sdnn = last.sdnn,
                pnn50 = last.pnn50,
                cvRr = last.cvRr,
                hrSurge = last.hrSurge,
                autonomicBalance = last.autonomicBalance,
                motionMean = motionMean,
                motionMax = motionMax
            )
        }

        // Multiple consecutive sparse epochs -> signal starved
        consecutiveSparseEpochs++
        return null
    }

    private fun updateStats(meanRr: Double, hr: Double, rmssd: Double, sdnn: Double, pnn50: Double,
                            cvRr: Double, auto: Double, motM: Double, motX: Double) {
        statCount++
        sumMeanRr += meanRr; sumSqMeanRr += meanRr * meanRr
        sumHr += hr;         sumSqHr += hr * hr
        sumRmssd += rmssd;   sumSqRmssd += rmssd * rmssd
        sumSdnn += sdnn;     sumSqSdnn += sdnn * sdnn
        sumPnn50 += pnn50;   sumSqPnn50 += pnn50 * pnn50
        sumCv += cvRr;       sumSqCv += cvRr * cvRr
        sumAuto += auto;     sumSqAuto += auto * auto
        sumMotM += motM;     sumSqMotM += motM * motM
        sumMotX += motX;     sumSqMotX += motX * motX
    }

    private fun calcZ(value: Double, sum: Double, sumSq: Double, count: Int): Double {
        if (count < 2) return 0.0
        val mean = sum / count
        val variance = max(1e-6, (sumSq / count) - mean * mean)
        return (value - mean) / sqrt(variance)
    }

    private fun extractFeatures(): DoubleArray {
        val list = buffer.toList()
        if (list.size < WINDOW_SIZE) return DoubleArray(18)
        val target = list[TARGET_INDEX]

        val meanRrZ = calcZ(target.meanRr, sumMeanRr, sumSqMeanRr, statCount)
        val hrZ = calcZ(target.hr, sumHr, sumSqHr, statCount)
        val rmssdZ = calcZ(target.rmssd, sumRmssd, sumSqRmssd, statCount)
        val sdnnZ = calcZ(target.sdnn, sumSdnn, sumSqSdnn, statCount)
        val pnn50Z = calcZ(target.pnn50, sumPnn50, sumSqPnn50, statCount)
        val cvRrZ = calcZ(target.cvRr, sumCv, sumSqCv, statCount)
        val hrSurgeZ = target.hrSurge
        val autoZ = calcZ(target.autonomicBalance, sumAuto, sumSqAuto, statCount)
        val motMZ = calcZ(target.motionMean, sumMotM, sumSqMotM, statCount)
        val motXZ = calcZ(target.motionMax, sumMotX, sumSqMotX, statCount)

        var hrPastSum = 0.0; var rmssdPastSum = 0.0; var cvPastSum = 0.0; var motPastSum = 0.0
        for (i in 0 until TARGET_INDEX) {
            hrPastSum += calcZ(list[i].hr, sumHr, sumSqHr, statCount)
            rmssdPastSum += calcZ(list[i].rmssd, sumRmssd, sumSqRmssd, statCount)
            cvPastSum += calcZ(list[i].cvRr, sumCv, sumSqCv, statCount)
            motPastSum += calcZ(list[i].motionMean, sumMotM, sumSqMotM, statCount)
        }
        val hrZPast5m = hrPastSum / TARGET_INDEX
        val rmssdZPast5m = rmssdPastSum / TARGET_INDEX
        val cvZPast5m = cvPastSum / TARGET_INDEX
        val motPast5m = motPastSum / TARGET_INDEX

        var hrFutSum = 0.0; var rmssdFutSum = 0.0; var cvFutSum = 0.0; var motFutSum = 0.0
        val futCount = WINDOW_SIZE - TARGET_INDEX - 1
        for (i in (TARGET_INDEX + 1) until WINDOW_SIZE) {
            hrFutSum += calcZ(list[i].hr, sumHr, sumSqHr, statCount)
            rmssdFutSum += calcZ(list[i].rmssd, sumRmssd, sumSqRmssd, statCount)
            cvFutSum += calcZ(list[i].cvRr, sumCv, sumSqCv, statCount)
            motFutSum += calcZ(list[i].motionMean, sumMotM, sumSqMotM, statCount)
        }
        val hrZFut5m = hrFutSum / futCount
        val rmssdZFut5m = rmssdFutSum / futCount
        val cvZFut5m = cvFutSum / futCount
        val motFut5m = motFutSum / futCount

        return doubleArrayOf(
            meanRrZ, hrZ, rmssdZ, sdnnZ, pnn50Z, cvRrZ, hrSurgeZ, autoZ,
            motMZ, motXZ,
            hrZPast5m, hrZFut5m,
            rmssdZPast5m, rmssdZFut5m,
            cvZPast5m, cvZFut5m,
            motPast5m, motFut5m
        )
    }

    @Synchronized
    fun reset() {
        currentEpochRrs.clear()
        currentEpochMotions.clear()
        epochStartTimeMs = 0L
        currentEpochIndex = 0L
        buffer.clear()
        consecutiveSparseEpochs = 0
        statCount = 0
        sumMeanRr = 0.0; sumSqMeanRr = 0.0
        sumHr = 0.0;     sumSqHr = 0.0
        sumRmssd = 0.0;  sumSqRmssd = 0.0
        sumSdnn = 0.0;   sumSqSdnn = 0.0
        sumPnn50 = 0.0;  sumSqPnn50 = 0.0
        sumCv = 0.0;     sumSqCv = 0.0
        sumAuto = 0.0;   sumSqAuto = 0.0
        sumMotM = 0.0;   sumSqMotM = 0.0
        sumMotX = 0.0;   sumSqMotX = 0.0
        minHrTracked = 200.0
    }

    @Synchronized
    fun getBufferSize(): Int = buffer.size

    fun isBufferFull(): Boolean = synchronized(this) { buffer.size >= WINDOW_SIZE }
}
