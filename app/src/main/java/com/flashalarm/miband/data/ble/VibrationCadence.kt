package com.flashalarm.miband.data.ble

import com.flashalarm.miband.domain.model.VibrationCadenceType

data class VibrationPattern(
    val type: VibrationCadenceType,
    val sequenceMs: List<Long> // alternating: [vibrate, pause, vibrate, pause, ...]
)

object VibrationCadenceProfiles {
    val profiles = mapOf(
        VibrationCadenceType.DOUBLE_TAP_LONG to VibrationPattern(
            VibrationCadenceType.DOUBLE_TAP_LONG,
            listOf(200L, 200L, 200L, 600L, 800L, 1000L)
        ),
        VibrationCadenceType.TRIPLE_PULSE to VibrationPattern(
            VibrationCadenceType.TRIPLE_PULSE,
            listOf(150L, 150L, 150L, 150L, 150L, 1000L)
        ),
        VibrationCadenceType.GENTLE_WAVE to VibrationPattern(
            VibrationCadenceType.GENTLE_WAVE,
            listOf(300L, 300L, 500L, 300L, 300L, 1200L)
        ),
        VibrationCadenceType.HEARTBEAT to VibrationPattern(
            VibrationCadenceType.HEARTBEAT,
            listOf(120L, 100L, 140L, 1000L)
        )
    )

    fun getPattern(type: VibrationCadenceType): VibrationPattern {
        return profiles[type] ?: profiles.getValue(VibrationCadenceType.DOUBLE_TAP_LONG)
    }
}
