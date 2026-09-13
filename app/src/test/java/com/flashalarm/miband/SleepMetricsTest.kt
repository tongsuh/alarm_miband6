package com.flashalarm.miband

import com.flashalarm.miband.domain.model.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepMetricsTest {

    @Test
    fun `test sleep stage enum mapping and display names`() {
        assertEquals("深睡", SleepStage.DEEP.displayName)
        assertEquals("浅睡", SleepStage.LIGHT.displayName)
        assertEquals("做梦期", SleepStage.REM.displayName)
        assertEquals("清醒", SleepStage.AWAKE.displayName)

        assertEquals(SleepStage.DEEP, SleepStage.fromCode(3))
        assertEquals(SleepStage.REM, SleepStage.fromCode(1))
        assertEquals(SleepStage.AWAKE, SleepStage.fromCode(0))
    }

    @Test
    fun `test sleep efficiency and duration metrics`() {
        val remMinutes = 110
        val deepMinutes = 95
        val lightMinutes = 200
        val awakeMinutes = 39

        val netSleepMinutes = remMinutes + deepMinutes + lightMinutes // 405 min = 6h 45m
        val timeInBedMinutes = netSleepMinutes + awakeMinutes        // 444 min = 7h 24m

        val efficiency = ((netSleepMinutes.toDouble() / timeInBedMinutes) * 100).toInt()
        assertEquals(91, efficiency)

        // Calculate score
        var score = 50
        if (netSleepMinutes >= 360) score += 20
        score += (efficiency * 0.25).toInt()
        if (deepMinutes >= 60) score += 5
        if (remMinutes >= 60) score += 5

        assertEquals(88, score)
        assertTrue("Score within standard healthy range", score in 85..95)
    }
}
