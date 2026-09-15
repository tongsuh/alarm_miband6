package com.flashalarm.miband.data.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class AcousticBreathingState(
    val breathingRateBpm: Float = 14.0f,
    val irregularityScore: Float = 0.2f,
    val ambientRms: Float = 0.0f,
    val isAudioReliable: Boolean = false,
    val isAnalyzing: Boolean = false
) {
    val ambientRmsDb: Float
        get() = if (ambientRms > 1.0f) (20.0f * kotlin.math.log10(ambientRms.toDouble()).toFloat()).coerceIn(20.0f, 90.0f) else 20.0f
}

class BreathingAudioAnalyzer(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BreathingAudioAnalyzer"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val SAMPLE_RATES = intArrayOf(44100, 16000, 8000)
        private const val CHUNK_SIZE = 2048
        private const val BREATH_TIMEOUT_MS = 18000L // 18s without detected peak -> mark unreliable
    }

    private var audioRecord: AudioRecord? = null
    private var analysisJob: Job? = null
    private var maxEnvelopeCapacity: Int = 430 // Dynamic: (sampleRate * 20) / CHUNK_SIZE

    private val _state = MutableStateFlow(AcousticBreathingState())
    val state: StateFlow<AcousticBreathingState> = _state.asStateFlow()

    private val envelopeHistory = ArrayDeque<Float>()
    private val peakIntervalsMs = ArrayDeque<Long>()
    private var lastPeakTimeMs = 0L

    @SuppressLint("MissingPermission")
    fun startAnalysis() {
        if (analysisJob != null && analysisJob?.isActive == true) return

        var selectedSampleRate = 44100
        var minBufSize = -1
        for (rate in SAMPLE_RATES) {
            val size = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (size > 0) {
                selectedSampleRate = rate
                minBufSize = size
                break
            }
        }

        if (minBufSize <= 0) {
            Log.e(TAG, "AudioRecord buffer size invalid for all sample rates")
            _state.value = _state.value.copy(isAudioReliable = false, isAnalyzing = false)
            return
        }

        try {
            val bufSize = max(minBufSize, 4096)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                selectedSampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize with sample rate $selectedSampleRate")
                audioRecord?.release()
                audioRecord = null
                _state.value = _state.value.copy(isAudioReliable = false, isAnalyzing = false)
                return
            }

            maxEnvelopeCapacity = ((selectedSampleRate * 20) / CHUNK_SIZE).coerceIn(40, 600)
            audioRecord?.startRecording()
            _state.value = _state.value.copy(isAnalyzing = true)

            analysisJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(CHUNK_SIZE)
                envelopeHistory.clear()
                peakIntervalsMs.clear()
                lastPeakTimeMs = 0L

                while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readCount > 0) {
                        processAudioChunk(buffer, readCount)
                    } else if (readCount < 0) {
                        Log.w(TAG, "AudioRecord read returned error code: $readCount")
                        delay(100L)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting audio analysis", e)
            _state.value = _state.value.copy(isAudioReliable = false, isAnalyzing = false)
        }
    }

    private fun processAudioChunk(buffer: ShortArray, readCount: Int) {
        // Calculate chunk RMS
        var sumSquares = 0.0
        for (i in 0 until readCount) {
            val sample = buffer[i].toDouble()
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / readCount).toFloat()

        // Maintain true 20-second dynamic envelope window
        envelopeHistory.addLast(rms)
        if (envelopeHistory.size > maxEnvelopeCapacity) envelopeHistory.removeFirst()

        val avgRms = envelopeHistory.average().toFloat()
        // Noise floor check: ambient bedroom level (20.0 to 1800.0 RMS)
        val isCleanSnr = avgRms in 20.0f..1800.0f

        // Peak / breath inhalation detection:
        // Requires: 30% above background + minimum 15.0 LSB delta (to filter quantization noise) + 1.5s refractory period
        val now = System.currentTimeMillis()
        val isPeakDetected = (rms > avgRms * 1.30f) && ((rms - avgRms) >= 15.0f) && (now - lastPeakTimeMs > 1500L)
        if (isPeakDetected) {
            if (lastPeakTimeMs > 0L) {
                val interval = now - lastPeakTimeMs
                // Typical human nocturnal breathing cycle: 1800ms - 7000ms (8.5 - 33.3 bpm)
                if (interval in 1800L..7000L) {
                    peakIntervalsMs.addLast(interval)
                    if (peakIntervalsMs.size > 15) peakIntervalsMs.removeFirst()
                }
            }
            lastPeakTimeMs = now
        }

        // Freshness check: if no peak has been detected for > 18s, clear stale intervals
        val isPeakFresh = lastPeakTimeMs > 0L && (now - lastPeakTimeMs) < BREATH_TIMEOUT_MS
        if (!isPeakFresh && peakIntervalsMs.isNotEmpty() && (now - lastPeakTimeMs) > (BREATH_TIMEOUT_MS * 2)) {
            peakIntervalsMs.clear()
        }

        // Strict physiological reliability:
        // Must have clean ambient SNR AND at least 4 consistent breath cycles AND fresh detection (< 18s)
        val isAudioReliable = isCleanSnr && peakIntervalsMs.size >= 4 && isPeakFresh

        // Calculate breathing rate and irregularity
        val (bpm, irregularity) = if (isAudioReliable && peakIntervalsMs.size >= 4) {
            val avgInterval = peakIntervalsMs.average().toFloat()
            val computedBpm = (60000.0f / avgInterval).coerceIn(8.0f, 35.0f)

            // Variance of intervals reflects respiratory irregularity (phasic REM vs tonic NREM)
            val variance = peakIntervalsMs.map {
                val diff = it - avgInterval
                diff * diff
            }.average().toFloat()
            val stdDev = sqrt(variance)
            val cv = (stdDev / avgInterval).coerceIn(0.0f, 1.0f)

            Pair(computedBpm, cv)
        } else {
            Pair(14.0f, 0.15f)
        }

        _state.value = AcousticBreathingState(
            breathingRateBpm = bpm,
            irregularityScore = irregularity,
            ambientRms = avgRms,
            isAudioReliable = isAudioReliable,
            isAnalyzing = true
        )
    }

    fun stopAnalysis() {
        analysisJob?.cancel()
        analysisJob = null

        try {
            val record = audioRecord
            audioRecord = null
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
            record?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio record", e)
        } finally {
            envelopeHistory.clear()
            peakIntervalsMs.clear()
            lastPeakTimeMs = 0L
            _state.value = _state.value.copy(isAnalyzing = false, isAudioReliable = false)
        }
    }
}
