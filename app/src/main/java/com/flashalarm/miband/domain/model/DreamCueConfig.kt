package com.flashalarm.miband.domain.model

enum class PatternType(val displayName: String) {
    WATERDROP("水滴轻触"),
    SHORT("轻柔微律"),
    STEADY("恒定微震"),
    CRESCENDO("阶梯渐强"),
    HEARTBEAT("拟真心跳"),
    DECRESCENDO("渐弱轻拂"),
    PULSE_WAVE("律动波浪");
}

data class CustomizableVibrationPattern(
    val id: String,
    val name: String,
    val type: PatternType,
    val startIntensityPercent: Int = 20, // 10% - 100%
    val endIntensityPercent: Int = 80,   // 10% - 100%
    val pulseMs: Int = 200,              // 100ms - 1000ms
    val pauseMs: Int = 600,              // 400ms - 2000ms (>=500ms required for firmware call state machine reset)
    val durationSeconds: Int = 15,       // 5s - 300s (up to 5 min!)
    val repeatCount: Int = 3             // 1 - 10 times
)

data class DreamCueConfig(
    // 1. Current active vibration profile
    val activePatternId: String = "waterdrop",
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
    val confidenceThreshold: Float = 0.72f
) {
    fun getActivePattern(): CustomizableVibrationPattern {
        return patterns.firstOrNull { it.id == activePatternId } ?: patterns.first()
    }

    companion object {
        fun defaultPatterns(): List<CustomizableVibrationPattern> {
            return listOf(
                CustomizableVibrationPattern(
                    id = "waterdrop",
                    name = "水滴轻触 (推荐)",
                    type = PatternType.WATERDROP,
                    startIntensityPercent = 20,
                    endIntensityPercent = 20,
                    pulseMs = 180,
                    pauseMs = 1000,
                    durationSeconds = 15,
                    repeatCount = 3
                ),
                CustomizableVibrationPattern(
                    id = "short",
                    name = "轻柔微律",
                    type = PatternType.SHORT,
                    startIntensityPercent = 35,
                    endIntensityPercent = 35,
                    pulseMs = 200,
                    pauseMs = 600,
                    durationSeconds = 15,
                    repeatCount = 3
                ),
                CustomizableVibrationPattern(
                    id = "steady",
                    name = "恒定微震",
                    type = PatternType.STEADY,
                    startIntensityPercent = 40,
                    endIntensityPercent = 40,
                    pulseMs = 250,
                    pauseMs = 650,
                    durationSeconds = 15,
                    repeatCount = 3
                ),
                CustomizableVibrationPattern(
                    id = "crescendo",
                    name = "阶梯渐强",
                    type = PatternType.CRESCENDO,
                    startIntensityPercent = 20,
                    endIntensityPercent = 80,
                    pulseMs = 220,
                    pauseMs = 550,
                    durationSeconds = 20,
                    repeatCount = 4
                ),
                CustomizableVibrationPattern(
                    id = "heartbeat",
                    name = "拟真心跳",
                    type = PatternType.HEARTBEAT,
                    startIntensityPercent = 40,
                    endIntensityPercent = 60,
                    pulseMs = 160,
                    pauseMs = 700,
                    durationSeconds = 15,
                    repeatCount = 3
                )
            )
        }

        fun getDefaultPattern(id: String): CustomizableVibrationPattern {
            return defaultPatterns().firstOrNull { it.id == id } ?: defaultPatterns().first()
        }
    }
}
