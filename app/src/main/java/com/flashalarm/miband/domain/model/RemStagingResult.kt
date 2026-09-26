package com.flashalarm.miband.domain.model

enum class SleepSessionPhase(val displayName: String) {
    DETECTING_ONSET("入睡监测中"),
    PROTECTION_PERIOD("深睡保护期"),
    DREAM_WINDOW_ACTIVE("触梦雷达开启");
}

data class RemStagingResult(
    val stage: SleepStage,
    val confidence: Float,
    val isDreamCueEligible: Boolean,
    val isDreamCueTriggered: Boolean,
    val triggerReason: String,
    val atoniaScore: Float,
    val hrSurgePercent: Float,
    val hrvDispersion: Float,
    val audioIrregularity: Float,
    val isVetoedByMovement: Boolean,
    val isWithinTimingWindow: Boolean,
    val sessionPhase: SleepSessionPhase = SleepSessionPhase.DETECTING_ONSET,
    val isSleepOnsetDetected: Boolean = false,
    val protectionRemainingMinutes: Int = 0,
    val sleepOnsetDetectedTimeMs: Long = 0L,
    val baseRemProb: Float = 0f,
    val eogBursts: Int = 0,
    val eogSignalQuality: String = "CLEAN_RESTING",
    val alphaGating: Float = 0f,
    val rawLogitBoost: Float = 0f,
    val effectiveLogitBoost: Float = 0f,
    val fusedRemProb: Float = 0f,
    val confidenceBoost: Float = 0f,
    val effectiveThreshold: Float = 0.65f,
    val consecutiveRemCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)
