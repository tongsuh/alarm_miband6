package com.flashalarm.miband.domain.model

enum class VibrationCadenceType(val displayName: String, val patternDescription: String) {
    DOUBLE_TAP_LONG("双击-停顿-长震", "200ms震动 - 200ms停顿 - 200ms震动 - 600ms停顿 - 800ms微震"),
    TRIPLE_PULSE("三重微脉冲", "150ms震动 - 150ms停顿 - 150ms震动 - 150ms停顿 - 150ms震动"),
    GENTLE_WAVE("渐进平滑微震", "300ms轻微 - 500ms适中 - 300ms轻微"),
    HEARTBEAT("律动心跳震感", "100ms - 80ms - 100ms - 1000ms间隔");
}

data class DreamCueConfig(
    val cadenceType: VibrationCadenceType = VibrationCadenceType.DOUBLE_TAP_LONG,
    val cooldownMinutes: Int = 25,
    val minSleepOnsetMinutes: Int = 75,
    val maxCueDurationSeconds: Int = 20,
    val enableAudioVerification: Boolean = true,
    val enableScreenRedFlash: Boolean = false,
    val enableVoiceWhisper: Boolean = false,
    val confidenceThreshold: Float = 0.85f
)
