package com.flashalarm.miband

import com.flashalarm.miband.domain.algorithm.MultiModalRemEngine
import com.flashalarm.miband.domain.model.DreamCueConfig
import com.flashalarm.miband.domain.model.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MultiModalRemEngineTest {

    private lateinit var engine: MultiModalRemEngine

    @Before
    fun setUp() {
        val config = DreamCueConfig(
            cooldownMinutes = 20,
            minSleepOnsetMinutes = 70,
            confidenceThreshold = 0.80f
        )
        engine = MultiModalRemEngine(config)
        engine.startSession(startTimeMs = 0L)
    }

    @Test
    fun `test movement veto rule - roll over or arm lift immediately vetoes REM`() {
        // Feed still baseline to establish deep sleep
        for (i in 0 until 10) {
            engine.evaluateEpoch(heartRate = 55, actigraphyMagnitude = 0.01f, currentTimeMs = 1000L * i)
        }

        // Simulate sudden arm movement (e.g. 0.35g)
        val result = engine.evaluateEpoch(
            heartRate = 75,
            actigraphyMagnitude = 0.35f,
            currentTimeMs = 15000L
        )

        assertTrue("Movement should veto REM staging", result.isVetoedByMovement)
        assertEquals(SleepStage.AWAKE, result.stage)
        assertEquals(0.0f, result.confidence, 0.001f)
        assertFalse("Cannot trigger dream cue when moving", result.isDreamCueTriggered)
    }

    @Test
    fun `test sleep onset window protection - suppresses cue before 70 minutes`() {
        // Fast-forward 30 minutes after onset (under 70 minutes)
        val elapsedMs = 30 * 60 * 1000L

        // Feed strong REM physiological signals (still wrist + surging HR)
        for (i in 0 until 10) {
            engine.evaluateEpoch(
                heartRate = 78,
                actigraphyMagnitude = 0.01f,
                audioIrregularity = 0.7f,
                isAudioReliable = true,
                currentTimeMs = elapsedMs + (i * 1000L)
            )
        }

        val result = engine.evaluateEpoch(
            heartRate = 80,
            actigraphyMagnitude = 0.01f,
            audioIrregularity = 0.7f,
            isAudioReliable = true,
            currentTimeMs = elapsedMs + 15000L
        )

        assertFalse("Must be protected during early NREM deep sleep window", result.isWithinTimingWindow)
        assertFalse("Dream cue must not trigger before 70 min window", result.isDreamCueTriggered)
    }

    @Test
    fun `test dual verification triggers lucid cue after 70 minutes with high confidence`() {
        // 1. Establish baseline at 56 bpm during early deep sleep
        for (i in 0 until 15) {
            engine.evaluateEpoch(
                heartRate = 56,
                actigraphyMagnitude = 0.01f,
                currentTimeMs = 1000L * i
            )
        }

        // 2. Advance time past 75 minutes (into 2nd cycle REM window)
        val remTimeMs = 80 * 60 * 1000L

        // 3. Feed Autonomic Storm (HR surges to 72 bpm with high CV) + Muscle atonia + Irregular breathing
        for (i in 0 until 12) {
            val hrVar = if (i % 2 == 0) 75 else 68
            engine.evaluateEpoch(
                heartRate = hrVar,
                actigraphyMagnitude = 0.008f, // Skeletal muscle atonia
                audioIrregularity = 0.65f,     // Irregular breathing
                isAudioReliable = true,
                currentTimeMs = remTimeMs + (i * 1000L)
            )
        }

        val result = engine.evaluateEpoch(
            heartRate = 74,
            actigraphyMagnitude = 0.008f,
            audioIrregularity = 0.70f,
            isAudioReliable = true,
            currentTimeMs = remTimeMs + 20000L
        )

        assertEquals(SleepStage.REM, result.stage)
        assertTrue("Confidence should exceed 85%", result.confidence >= 0.85f)
        assertTrue("Dream cue should be triggered", result.isDreamCueTriggered)
    }

    @Test
    fun `test graceful degradation when audio is disabled or noisy`() {
        // Advance past 75 min
        val timeMs = 90 * 60 * 1000L

        // Feed wrist metrics only (audio is noisy / unreliable)
        for (i in 0 until 10) {
            engine.evaluateEpoch(
                heartRate = 74,
                actigraphyMagnitude = 0.009f,
                audioIrregularity = 0.0f,
                isAudioReliable = false, // Degraded to pure band mode
                currentTimeMs = timeMs + (i * 1000L)
            )
        }

        val result = engine.evaluateEpoch(
            heartRate = 75,
            actigraphyMagnitude = 0.009f,
            audioIrregularity = 0.0f,
            isAudioReliable = false,
            currentTimeMs = timeMs + 12000L
        )

        // Band dual-sensor (Actigraphy + HR/HRV) determines REM independently
        assertEquals(SleepStage.REM, result.stage)
        assertTrue("Band-only confidence must be calculated", result.confidence > 0.70f)
    }

    @Test
    fun `test cue cooldown prevents repeated waking stimuli`() {
        val timeMs = 80 * 60 * 1000L

        // Trigger first cue
        for (i in 0 until 10) {
            engine.evaluateEpoch(
                heartRate = 75,
                actigraphyMagnitude = 0.008f,
                audioIrregularity = 0.7f,
                isAudioReliable = true,
                currentTimeMs = timeMs + (i * 1000L)
            )
        }

        val firstCue = engine.evaluateEpoch(
            heartRate = 76,
            actigraphyMagnitude = 0.008f,
            audioIrregularity = 0.7f,
            isAudioReliable = true,
            currentTimeMs = timeMs + 15000L
        )
        assertTrue(firstCue.isDreamCueTriggered)

        // 5 minutes later (cooldown is 20 min)
        val secondCheckTime = timeMs + (5 * 60 * 1000L)
        val secondCue = engine.evaluateEpoch(
            heartRate = 76,
            actigraphyMagnitude = 0.008f,
            audioIrregularity = 0.7f,
            isAudioReliable = true,
            currentTimeMs = secondCheckTime
        )

        assertFalse("Second cue must be blocked by cooldown", secondCue.isDreamCueTriggered)
    }
}
