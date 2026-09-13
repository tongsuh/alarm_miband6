package com.flashalarm.miband.domain.model

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
    val timestamp: Long = System.currentTimeMillis()
)
