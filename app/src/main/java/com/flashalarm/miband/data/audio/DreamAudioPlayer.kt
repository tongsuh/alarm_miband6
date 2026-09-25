package com.flashalarm.miband.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class DreamAudioPlayer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DreamAudioPlayer"
        private const val CUES_SUBDIR = "custom_cues"
        private const val CUE_FILE_NAME = "active_dream_cue.mp3"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null

    /**
     * Copies a picked Uri into App's private internal storage.
     * This permanently avoids SecurityException / permission loss on app restart!
     * @return Absolute file path in app sandbox
     */
    fun saveCustomAudioToSandbox(uri: Uri): Pair<String, String>? {
        return try {
            val contentResolver = context.contentResolver
            val dir = File(context.filesDir, CUES_SUBDIR).apply { if (!exists()) mkdirs() }
            val destFile = File(dir, CUE_FILE_NAME)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Retrieve display name if possible
            var displayName = "自定义触梦音频.mp3"
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            displayName = cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve display name, using default", e)
            }

            Pair(destFile.absolutePath, displayName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving audio to private sandbox", e)
            null
        }
    }

    fun playCueAudio(
        filePath: String,
        durationSeconds: Int,
        volumePercent: Int = 50,
        onComplete: (() -> Unit)? = null
    ) {
        stopAudio()

        val targetVolume = (volumePercent.coerceIn(5, 100) / 100f)

        playbackJob = scope.launch(Dispatchers.IO) {
            val completionDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
            try {
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
                    )

                    val audioFile = if (filePath.isNotBlank()) File(filePath) else null
                    if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                        setDataSource(audioFile.absolutePath)
                    } else {
                        // Fallback to default system gentle notification ringtone
                        val defaultUri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
                        setDataSource(context, defaultUri)
                    }

                    // Fix: Subconscious whisper should NOT loop aggressively without gap!
                    isLooping = false
                    setOnCompletionListener {
                        completionDeferred.complete(Unit)
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        completionDeferred.complete(Unit)
                        true
                    }

                    setVolume(0.05f, 0.05f) // Start faint
                    prepare()
                    start()
                }
                mediaPlayer = player

                val trackDurationMs = try { player.duration.toLong() } catch (e: Exception) { -1L }
                val maxLimitMs = durationSeconds * 1000L

                // Adaptive smooth volume fade-in: short whispers fade in quickly to preserve leading words
                val fadeInDurationMs = if (trackDurationMs in 1..8000) {
                    minOf(800L, trackDurationMs / 4L).coerceAtLeast(200L)
                } else {
                    2000L
                }
                val fadeSteps = (fadeInDurationMs / 100L).toInt().coerceIn(3, 20)
                val stepDelay = fadeInDurationMs / fadeSteps

                for (step in 1..fadeSteps) {
                    if (!isActive || mediaPlayer == null) break
                    val currentVol = (targetVolume * (step.toFloat() / fadeSteps)).coerceIn(0.05f, 1.0f)
                    try {
                        mediaPlayer?.setVolume(currentVol, currentVol)
                    } catch (e: Exception) {
                        break
                    }
                    delay(stepDelay)
                }

                // If track duration is known and shorter than the max configured duration:
                // Play once completely and naturally without double-repeating!
                if (trackDurationMs in 1..maxLimitMs) {
                    val waitRemainingMs = (trackDurationMs - fadeInDurationMs).coerceAtLeast(0L)
                    kotlinx.coroutines.withTimeoutOrNull(waitRemainingMs + 1500L) {
                        completionDeferred.await()
                    }
                } else {
                    // Long ambient audio or unknown duration: maintain playback until max duration expires
                    val remainingMs = (maxLimitMs - fadeInDurationMs - 1000L).coerceAtLeast(500L)
                    delay(remainingMs)

                    // Smooth 1-second fade out for capped long tracks
                    for (step in 5 downTo 1) {
                        if (!isActive || mediaPlayer == null) break
                        val currentVol = (targetVolume * (step.toFloat() / 5)).coerceIn(0.0f, 1.0f)
                        try {
                            mediaPlayer?.setVolume(currentVol, currentVol)
                        } catch (e: Exception) {
                            break
                        }
                        delay(200L)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed", e)
            } finally {
                stopAudioInternal()
                onComplete?.invoke()
            }
        }
    }

    fun stopAudio() {
        playbackJob?.cancel()
        playbackJob = null
        stopAudioInternal()
    }

    private fun stopAudioInternal() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player", e)
        } finally {
            mediaPlayer = null
        }
    }
}
