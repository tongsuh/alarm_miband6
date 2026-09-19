package com.flashalarm.miband

import com.flashalarm.miband.data.db.SleepEpochEntity
import com.flashalarm.miband.data.repository.SleepRepository
import com.flashalarm.miband.domain.model.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepEpochSmoothingTest {

    private fun makeEpoch(stage: SleepStage, hr: Int = 65, index: Int = 0): SleepEpochEntity {
        return SleepEpochEntity(
            epochId = index.toLong(),
            sessionId = 1L,
            timestamp = 1000000L + (index * 30000L),
            stage = stage.code,
            heartRate = hr,
            actigraphy = 0.01f,
            audioIrregularity = 0.1f,
            confidence = if (stage == SleepStage.REM) 0.9f else 0.0f
        )
    }

    @Test
    fun `test isolated 1-epoch AWAKE inside LIGHT sleep is smoothed to LIGHT`() {
        val raw = listOf(
            makeEpoch(SleepStage.LIGHT, index = 0),
            makeEpoch(SleepStage.LIGHT, index = 1),
            makeEpoch(SleepStage.AWAKE, hr = 82, index = 2), // 30s rollover arousal
            makeEpoch(SleepStage.LIGHT, index = 3),
            makeEpoch(SleepStage.LIGHT, index = 4)
        )

        val smoothed = SleepRepository.smoothEpochs(raw)

        assertEquals(SleepStage.LIGHT.code, smoothed[2].stage)
    }

    @Test
    fun `test isolated 1-epoch AWAKE inside REM sleep is smoothed to REM`() {
        val raw = listOf(
            makeEpoch(SleepStage.REM, index = 0),
            makeEpoch(SleepStage.REM, index = 1),
            makeEpoch(SleepStage.AWAKE, hr = 84, index = 2), // 30s movement arousal
            makeEpoch(SleepStage.REM, index = 3),
            makeEpoch(SleepStage.REM, index = 4)
        )

        val smoothed = SleepRepository.smoothEpochs(raw)

        assertEquals(SleepStage.REM.code, smoothed[2].stage)
    }

    @Test
    fun `test isolated 2-epoch AWAKE between LIGHT and REM is smoothed`() {
        val raw = listOf(
            makeEpoch(SleepStage.LIGHT, index = 0),
            makeEpoch(SleepStage.LIGHT, index = 1),
            makeEpoch(SleepStage.AWAKE, hr = 80, index = 2),
            makeEpoch(SleepStage.AWAKE, hr = 78, index = 3),
            makeEpoch(SleepStage.REM, index = 4),
            makeEpoch(SleepStage.REM, index = 5)
        )

        val smoothed = SleepRepository.smoothEpochs(raw)

        assertEquals("First awake epoch smoothed to REM or LIGHT", SleepStage.REM.code, smoothed[2].stage)
        assertEquals("Second awake epoch smoothed to REM or LIGHT", SleepStage.REM.code, smoothed[3].stage)
    }

    @Test
    fun `test isolated 1-epoch or 2-epoch REM blip inside LIGHT is smoothed to LIGHT`() {
        val raw = listOf(
            makeEpoch(SleepStage.LIGHT, index = 0),
            makeEpoch(SleepStage.LIGHT, index = 1),
            makeEpoch(SleepStage.REM, index = 2), // 30s fake autonomic surge
            makeEpoch(SleepStage.REM, index = 3), // 60s total fake REM
            makeEpoch(SleepStage.LIGHT, index = 4),
            makeEpoch(SleepStage.LIGHT, index = 5)
        )

        val smoothed = SleepRepository.smoothEpochs(raw)

        assertEquals(SleepStage.LIGHT.code, smoothed[2].stage)
        assertEquals(SleepStage.LIGHT.code, smoothed[3].stage)
    }

    @Test
    fun `test genuine sustained REM period of 4 or more epochs is preserved`() {
        val raw = listOf(
            makeEpoch(SleepStage.LIGHT, index = 0),
            makeEpoch(SleepStage.REM, index = 1),
            makeEpoch(SleepStage.REM, index = 2),
            makeEpoch(SleepStage.REM, index = 3),
            makeEpoch(SleepStage.REM, index = 4),
            makeEpoch(SleepStage.LIGHT, index = 5)
        )

        val smoothed = SleepRepository.smoothEpochs(raw)

        assertEquals(SleepStage.REM.code, smoothed[1].stage)
        assertEquals(SleepStage.REM.code, smoothed[2].stage)
        assertEquals(SleepStage.REM.code, smoothed[3].stage)
        assertEquals(SleepStage.REM.code, smoothed[4].stage)
    }

    @Test
    fun `test genuine sustained AWAKE of 3 or more epochs is preserved`() {
        val raw = listOf(
            makeEpoch(SleepStage.LIGHT, index = 0),
            makeEpoch(SleepStage.AWAKE, hr = 85, index = 1),
            makeEpoch(SleepStage.AWAKE, hr = 88, index = 2),
            makeEpoch(SleepStage.AWAKE, hr = 82, index = 3),
            makeEpoch(SleepStage.LIGHT, index = 4)
        )

        val smoothed = SleepRepository.smoothEpochs(raw)

        assertEquals(SleepStage.AWAKE.code, smoothed[1].stage)
        assertEquals(SleepStage.AWAKE.code, smoothed[2].stage)
        assertEquals(SleepStage.AWAKE.code, smoothed[3].stage)
    }
}
