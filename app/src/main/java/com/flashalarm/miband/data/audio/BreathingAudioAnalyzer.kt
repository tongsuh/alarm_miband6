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
)

class BreathingAudioAnalyzer(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BreathingAudioAnalyzer"
        private const val SAMPLE_RATE = 8000 // 8kHz for low power consumption
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var analysisJob: Job? = null

    private val _state = MutableStateFlow(AcousticBreathingState())
    val state: StateFlow<AcousticBreathingState> = _state.asStateFlow()

    private val envelopeHistory = ArrayDeque<Float>()
    private val peakIntervalsMs = ArrayDeque<Long>()
    private var lastPeakTimeMs = 0L

    @SuppressLint("MissingPermission")
    fun startAnalysis() {
        if (analysisJob != null && analysisJob?.isActive == true) return

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize <= 0) {
            Log.e(TAG, "AudioRecord buffer size invalid: $minBufSize")
            _state.value = _state.value.copy(isAudioReliable = false, isAnalyzing = false)
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                max(minBufSize, 4096)
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                _state.value = _state.value.copy(isAudioReliable = false, isAnalyzing = false)
                return
            }

            audioRecord?.startRecording()
            _state.value = _state.value.copy(isAnalyzing = true)

            analysisJob = scope.launch(Dispatchers.Default) {
                val buffer = ShortArray(2048)
                envelopeHistory.clear()
                peakIntervalsMs.clear()

                while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readCount > 0) {
                        processAudioChunk(buffer, readCount)
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

        // Maintain envelope window (~20 seconds of audio chunks)
        envelopeHistory.addLast(rms)
        if (envelopeHistory.size > 80) envelopeHistory.removeFirst()

        val avgRms = envelopeHistory.average().toFloat()
        // Noise floor detection: very noisy (> 2500 RMS) or dead silent (< 15 RMS)
        val isCleanSnr = avgRms in 20.0f..1800.0f

        // Peak / breath inhalation detection
        val now = System.currentTimeMillis()
        if (rms > avgRms * 1.35f && (now - lastPeakTimeMs) > 1500L) {
            if (lastPeakTimeMs > 0L) {
                val interval = now - lastPeakTimeMs
                // Typical breathing cycle: 2000ms - 6000ms (10 - 30 bpm)
                if (interval in 1800L..6500L) {
                    peakIntervalsMs.addLast(interval)
                    if (peakIntervalsMs.size > 15) peakIntervalsMs.removeFirst()
                }
            }
            lastPeakTimeMs = now
        }

        // Calculate breathing rate and irregularity
        val (bpm, irregularity) = if (peakIntervalsMs.size >= 4) {
            val avgInterval = peakIntervalsMs.average().toFloat()
            val computedBpm = (60000.0f / avgInterval).coerceIn(8.0f, 35.0f)

            // Variance of intervals reflects respiratory irregularity
            val variance = peakIntervalsMs.map {
                val diff = it - avgInterval
                diff * diff
            }.average().toFloat()
            val stdDev = sqrt(variance)
            val cv = (stdDev / avgInterval).coerceIn(0.0f, 1.0f) // Irregularity score

            Pair(computedBpm, cv)
        } else {
            Pair(14.0f, 0.15f)
        }

        _state.value = AcousticBreathingState(
            breathingRateBpm = bpm,
            irregularityScore = irregularity,
            ambientRms = avgRms,
            isAudioReliable = isCleanSnr,
            isAnalyzing = true
        )
    }

    fun stopAnalysis() {
        analysisJob?.cancel()
        analysisJob = null

        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio record", e)
        } finally {
            audioRecord = null
            _state.value = _state.value.copy(isAnalyzing = false, isAudioReliable = false)
        }
    }
}
