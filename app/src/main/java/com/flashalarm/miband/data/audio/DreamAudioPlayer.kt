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
            try {
                mediaPlayer = MediaPlayer().apply {
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

                    isLooping = true
                    setVolume(0.05f, 0.05f) // Start faint
                    prepare()
                    start()
                }

                // Smooth 4-second volume fade-in
                val fadeSteps = 20
                val targetVol = targetVolume
                for (step in 1..fadeSteps) {
                    if (!isActive || mediaPlayer == null) break
                    val currentVol = (targetVol * (step.toFloat() / fadeSteps)).coerceIn(0.05f, 1.0f)
                    try {
                        mediaPlayer?.setVolume(currentVol, currentVol)
                    } catch (e: Exception) {
                        break
                    }
                    delay(200L)
                }

                // Maintain playback until duration expires
                val totalMs = durationSeconds * 1000L
                val remainingMs = (totalMs - 4000L).coerceAtLeast(1000L)
                delay(remainingMs)

                // Smooth 1-second fade out
                for (step in 5 downTo 1) {
                    if (!isActive || mediaPlayer == null) break
                    val currentVol = (targetVol * (step.toFloat() / 5)).coerceIn(0.0f, 1.0f)
                    try {
                        mediaPlayer?.setVolume(currentVol, currentVol)
                    } catch (e: Exception) {
                        break
                    }
                    delay(200L)
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
