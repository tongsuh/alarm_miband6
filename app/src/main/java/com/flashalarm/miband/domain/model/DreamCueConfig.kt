package com.flashalarm.miband.domain.model

enum class PatternType(val displayName: String) {
    CRESCENDO("渐强唤醒"),
    DECRESCENDO("渐弱轻拂"),
    HEARTBEAT("拟真心跳"),
    STEADY("恒定微震"),
    PULSE_WAVE("律动波浪");
}

data class CustomizableVibrationPattern(
    val id: String,
    val name: String,
    val type: PatternType,
    val startIntensityPercent: Int = 20, // 10% - 100%
    val endIntensityPercent: Int = 80,   // 10% - 100%
    val pulseMs: Int = 120,              // 50ms - 500ms
    val pauseMs: Int = 250,              // 50ms - 1000ms
    val durationSeconds: Int = 15        // 5s - 300s (up to 5 min!)
)

data class DreamCueConfig(
    // 1. Current active vibration profile
    val activePatternId: String = "crescendo",
    val patterns: List<CustomizableVibrationPattern> = defaultPatterns(),

    // 2. Dual Channels (Vibration & Audio Decoupling)
    val enableWristVibration: Boolean = true,
    val enableAudioPlayback: Boolean = false,
    val audioDurationSeconds: Int = 15,    // 5s - 300s (up to 5 min!)
    val audioVolumePercent: Int = 50,      // 10% - 100%
    val customAudioPath: String = "",       // Internal sandbox file path (permanent)
    val customAudioName: String = "默认潜意识耳语",

    // 3. Sleep Segmentation & Relative Timing
    val sleepOnsetProtectionHours: Float = 2.5f, // Hours after sleep onset before cues open (1.0h ~ 4.5h)
    val stage1HrSampleRateSeconds: Int = 30,     // Stage 1 (Deep sleep): 10s, 30s, 60s
    val stage2HrSampleRateSeconds: Int = 1,      // Stage 2 (REM window): 1s, 2s, 5s

    // 4. Algorithm & Safety
    val cooldownMinutes: Int = 20,
    val enableAudioVerification: Boolean = true,
    val confidenceThreshold: Float = 0.85f
) {
    fun getActivePattern(): CustomizableVibrationPattern {
        return patterns.firstOrNull { it.id == activePatternId } ?: patterns.first()
    }

    companion object {
        fun defaultPatterns(): List<CustomizableVibrationPattern> {
            return listOf(
                CustomizableVibrationPattern(
                    id = "crescendo",
                    name = "渐强唤醒 (推荐)",
                    type = PatternType.CRESCENDO,
                    startIntensityPercent = 20,
                    endIntensityPercent = 80,
                    pulseMs = 150,
                    pauseMs = 250,
                    durationSeconds = 20
                ),
                CustomizableVibrationPattern(
                    id = "heartbeat",
                    name = "拟真心跳",
                    type = PatternType.HEARTBEAT,
                    startIntensityPercent = 40,
                    endIntensityPercent = 60,
                    pulseMs = 120,
                    pauseMs = 600,
                    durationSeconds = 15
                ),
                CustomizableVibrationPattern(
                    id = "decrescendo",
                    name = "渐弱轻拂",
                    type = PatternType.DECRESCENDO,
                    startIntensityPercent = 75,
                    endIntensityPercent = 20,
                    pulseMs = 150,
                    pauseMs = 250,
                    durationSeconds = 15
                ),
                CustomizableVibrationPattern(
                    id = "steady",
                    name = "恒定微律",
                    type = PatternType.STEADY,
                    startIntensityPercent = 40,
                    endIntensityPercent = 40,
                    pulseMs = 100,
                    pauseMs = 200,
                    durationSeconds = 15
                )
            )
        }

        fun getDefaultPattern(id: String): CustomizableVibrationPattern {
            return defaultPatterns().firstOrNull { it.id == id } ?: defaultPatterns().first()
        }
    }
}
