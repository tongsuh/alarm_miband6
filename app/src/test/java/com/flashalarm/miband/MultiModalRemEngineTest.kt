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
            sleepOnsetProtectionHours = 70f / 60f,
            confidenceThreshold = 0.80f
        )
        engine = MultiModalRemEngine(config)
        engine.startSession(startTimeMs = 0L)
    }

    @Test
    fun `test sustained wake movement across epochs confirms AWAKE stage`() {
        engine.markSleepOnset(0L)
        // Feed still baseline to establish deep sleep
        for (i in 0 until 10) {
            engine.evaluateEpoch(heartRate = 55, actigraphyMagnitude = 0.01f, currentTimeMs = 1000L * i)
        }

        // 1st moving epoch (isolated movement arousal): does not flip to AWAKE
        val epoch1 = engine.evaluateEpoch(
            heartRate = 75,
            actigraphyMagnitude = 0.35f,
            currentTimeMs = 15000L
        )
        assertEquals("Isolated rollover should not flip to AWAKE immediately", SleepStage.DEEP, epoch1.stage)

        // 2nd vigorous moving epoch
        engine.evaluateEpoch(
            heartRate = 75,
            actigraphyMagnitude = 0.35f,
            currentTimeMs = 45000L
        )

        // 3rd moving epoch confirms sustained wakefulness
        val epoch3 = engine.evaluateEpoch(
            heartRate = 78,
            actigraphyMagnitude = 0.35f,
            currentTimeMs = 75000L
        )
        assertTrue("Sustained movement should veto REM staging", epoch3.isVetoedByMovement)
        assertEquals(SleepStage.AWAKE, epoch3.stage)
        assertEquals(0.0f, epoch3.confidence, 0.001f)
        assertFalse("Cannot trigger dream cue when awake", epoch3.isDreamCueTriggered)
    }

    @Test
    fun `test transient 60s autonomic surge does not trigger 1-minute REM`() {
        engine.markSleepOnset(0L)
        // Establish baseline
        for (i in 0 until 10) {
            engine.evaluateEpoch(heartRate = 56, actigraphyMagnitude = 0.01f, currentTimeMs = 1000L * i)
        }

        val remTimeMs = 80 * 60 * 1000L

        // Feed only 2 epochs (60 seconds) of autonomic surge
        val epoch1 = engine.evaluateEpoch(
            heartRate = 76,
            actigraphyMagnitude = 0.008f,
            audioIrregularity = 0.65f,
            isAudioReliable = true,
            currentTimeMs = remTimeMs
        )
        val epoch2 = engine.evaluateEpoch(
            heartRate = 78,
            actigraphyMagnitude = 0.008f,
            audioIrregularity = 0.65f,
            isAudioReliable = true,
            currentTimeMs = remTimeMs + 30000L
        )

        // Fluctuation ends on epoch 3
        val epoch3 = engine.evaluateEpoch(
            heartRate = 60,
            actigraphyMagnitude = 0.01f,
            currentTimeMs = remTimeMs + 60000L
        )

        assertTrue("60s surge must not trigger 1-minute REM on epoch 1", epoch1.stage != SleepStage.REM)
        assertTrue("60s surge must not trigger 1-minute REM on epoch 2", epoch2.stage != SleepStage.REM)
        assertTrue("Must remain non-REM on epoch 3", epoch3.stage != SleepStage.REM)
    }

    @Test
    fun `test micro-movement does not veto dream cue or flip REM stage`() {
        engine.markSleepOnset(0L)
        val remTimeMs = 80 * 60 * 1000L

        // Feed strong REM physiological signals
        for (i in 0 until 12) {
            val hrVar = if (i % 2 == 0) 75 else 68
            engine.evaluateEpoch(
                heartRate = hrVar,
                actigraphyMagnitude = 0.008f,
                audioIrregularity = 0.65f,
                isAudioReliable = true,
                currentTimeMs = remTimeMs + (i * 1000L)
            )
        }

        // Simulate isolated micro-twitch (peak spike 0.22g, but epoch average actigraphy is low 0.03g)
        val microResult = engine.evaluateEpoch(
            heartRate = 74,
            actigraphyMagnitude = 0.03f,
            peakActigraphy = 0.22f,
            audioIrregularity = 0.65f,
            isAudioReliable = true,
            currentTimeMs = remTimeMs + 20000L
        )

        assertEquals(SleepStage.REM, microResult.stage)
        assertFalse("Micro movement must NOT veto cue trigger", microResult.isVetoedByMovement)
        assertTrue("Dream cue should still be triggerable during micro-twitch", microResult.isDreamCueTriggered)
    }

    @Test
    fun `test sleep onset window protection - suppresses cue before 70 minutes`() {
        engine.markSleepOnset(0L)
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
        engine.markSleepOnset(0L)
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
        engine.markSleepOnset(0L)
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
        engine.markSleepOnset(0L)
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

    @Test
    fun `test sleep onset detection requires stillness and physiological HR dip`() {
        // Reset session
        engine.startSession(startTimeMs = 0L)

        // 1. Initial 8 epochs: Bedtime quietness, awake HR around 70 bpm
        for (i in 0 until 8) {
            val res = engine.evaluateEpoch(
                heartRate = 70,
                actigraphyMagnitude = 0.02f,
                peakActigraphy = 0.03f,
                currentTimeMs = 30000L * i
            )
            assertFalse("Should not detect onset immediately", res.isSleepOnsetDetected)
        }

        // 2. Next 8 epochs: Stillness maintained and HR drops to 65 bpm (5 bpm dip)
        var finalResult = engine.evaluateEpoch(
            heartRate = 65,
            actigraphyMagnitude = 0.015f,
            peakActigraphy = 0.02f,
            currentTimeMs = 30000L * 8
        )
        for (i in 9 until 17) {
            finalResult = engine.evaluateEpoch(
                heartRate = 65,
                actigraphyMagnitude = 0.015f,
                peakActigraphy = 0.02f,
                currentTimeMs = 30000L * i
            )
        }

        assertTrue("Sleep onset should be confirmed after 16 sustained still epochs with HR dip", finalResult.isSleepOnsetDetected)
    }

    @Test
    fun `test normal rollover does not poison subsequent epochs into awake`() {
        engine.markSleepOnset(0L)
        val remTimeMs = 80 * 60 * 1000L

        // Feed sustained REM signals
        for (i in 0 until 10) {
            val hrVar = if (i % 2 == 0) 74 else 68
            engine.evaluateEpoch(
                heartRate = hrVar,
                actigraphyMagnitude = 0.008f,
                audioIrregularity = 0.65f,
                isAudioReliable = true,
                currentTimeMs = remTimeMs + (i * 30000L)
            )
        }

        // Simulate 1 normal bed rollover epoch (0.16g, resting heart rate 64 bpm)
        val rolloverEpoch = engine.evaluateEpoch(
            heartRate = 64,
            actigraphyMagnitude = 0.16f,
            peakActigraphy = 0.25f,
            currentTimeMs = remTimeMs + (10 * 30000L)
        )
        // Stage during rollover remains REM or LIGHT via hysteresis, never forced to AWAKE
        assertTrue("Rollover should not be scored as AWAKE", rolloverEpoch.stage != SleepStage.AWAKE)

        // Immediately following epoch: user is back still in REM
        val nextEpoch = engine.evaluateEpoch(
            heartRate = 72,
            actigraphyMagnitude = 0.008f,
            audioIrregularity = 0.65f,
            isAudioReliable = true,
            currentTimeMs = remTimeMs + (11 * 30000L)
        )
        // Must stay in REM, not artificially dragged into AWAKE
        assertEquals("Subsequent epoch must remain REM", SleepStage.REM, nextEpoch.stage)
    }

    @Test
    fun `test boundary crossing rollover micro-movements across 2 epochs does not trigger AWAKE`() {
        engine.markSleepOnset(0L)
        // Establish stable deep baseline
        for (i in 0 until 10) {
            engine.evaluateEpoch(heartRate = 54, actigraphyMagnitude = 0.01f, currentTimeMs = 1000L * i)
        }

        // Epoch 1 of boundary rollover: brief turn peak 0.24g, mean 0.04g, HR 62 bpm
        val boundaryEpoch1 = engine.evaluateEpoch(
            heartRate = 62,
            actigraphyMagnitude = 0.04f,
            peakActigraphy = 0.24f,
            currentTimeMs = 15000L
        )
        assertTrue("Epoch 1 of boundary rollover must not be AWAKE", boundaryEpoch1.stage != SleepStage.AWAKE)

        // Epoch 2 of boundary rollover: tail of turn peak 0.21g, mean 0.035f, HR 60 bpm
        val boundaryEpoch2 = engine.evaluateEpoch(
            heartRate = 60,
            actigraphyMagnitude = 0.035f,
            peakActigraphy = 0.21f,
            currentTimeMs = 45000L
        )
        assertTrue("Epoch 2 of boundary rollover must not be AWAKE", boundaryEpoch2.stage != SleepStage.AWAKE)
    }

    @Test
    fun `test fast recovery from AWAKE when subject becomes quiet and still`() {
        engine.markSleepOnset(0L)
        // Establish baseline
        for (i in 0 until 10) {
            engine.evaluateEpoch(heartRate = 55, actigraphyMagnitude = 0.01f, currentTimeMs = 1000L * i)
        }

        // Trigger genuine sustained awake with 2 epochs of vigorous out-of-bed motion
        engine.evaluateEpoch(heartRate = 80, actigraphyMagnitude = 0.38f, currentTimeMs = 15000L)
        val awakeEpoch = engine.evaluateEpoch(heartRate = 82, actigraphyMagnitude = 0.38f, currentTimeMs = 45000L)
        assertEquals("Sustained vigorous movement must be AWAKE", SleepStage.AWAKE, awakeEpoch.stage)

        // Subject immediately lies back down motionless (actigraphy < 0.04g, peak < 0.12g, HR 56)
        val recoveredEpoch = engine.evaluateEpoch(
            heartRate = 56,
            actigraphyMagnitude = 0.01f,
            peakActigraphy = 0.02f,
            currentTimeMs = 75000L
        )
        // Fast recovery path should immediately transition out of AWAKE in 1 epoch without 60s sticky delay
        assertEquals("Should recover to LIGHT on 1st quiet epoch", SleepStage.LIGHT, recoveredEpoch.stage)
    }
}
