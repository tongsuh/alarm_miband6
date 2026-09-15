package com.flashalarm.miband.data.repository

import com.flashalarm.miband.data.db.DreamCueDao
import com.flashalarm.miband.data.db.DreamCueEntity
import com.flashalarm.miband.data.db.SleepDatabase
import com.flashalarm.miband.data.db.SleepEpochDao
import com.flashalarm.miband.data.db.SleepEpochEntity
import com.flashalarm.miband.data.db.SleepSessionDao
import com.flashalarm.miband.data.db.SleepSessionEntity
import com.flashalarm.miband.domain.model.SleepStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Random

class SleepRepository(
    private val database: SleepDatabase
) {
    private val sessionDao: SleepSessionDao = database.sleepSessionDao()
    private val epochDao: SleepEpochDao = database.sleepEpochDao()
    private val cueDao: DreamCueDao = database.dreamCueDao()

    val allSessions: Flow<List<SleepSessionEntity>> = sessionDao.getAllSessions()
    val latestSession: Flow<SleepSessionEntity?> = sessionDao.getLatestSession()

    fun getEpochsForSession(sessionId: Long): Flow<List<SleepEpochEntity>> =
        epochDao.getEpochsForSession(sessionId)

    fun getCuesForSession(sessionId: Long): Flow<List<DreamCueEntity>> =
        cueDao.getCuesForSession(sessionId)

    suspend fun getSessionById(sessionId: Long): SleepSessionEntity? = withContext(Dispatchers.IO) {
        sessionDao.getSessionById(sessionId)
    }

    suspend fun createSession(startTimeMs: Long = System.currentTimeMillis(), title: String = "夜间睡眠"): Long =
        withContext(Dispatchers.IO) {
            val initialSession = SleepSessionEntity(
                startTime = startTimeMs,
                endTime = startTimeMs,
                netSleepMinutes = 0,
                timeInBedMinutes = 0,
                sleepScore = 0,
                efficiency = 0,
                deepMinutes = 0,
                remMinutes = 0,
                lightMinutes = 0,
                awakeMinutes = 0,
                cueCount = 0,
                sessionTitle = title
            )
            sessionDao.insertSession(initialSession)
        }

    suspend fun recordEpoch(
        sessionId: Long,
        timestamp: Long,
        stage: SleepStage,
        heartRate: Int,
        actigraphy: Float,
        audioIrregularity: Float,
        confidence: Float
    ) = withContext(Dispatchers.IO) {
        val epoch = SleepEpochEntity(
            sessionId = sessionId,
            timestamp = timestamp,
            stage = stage.code,
            heartRate = heartRate,
            actigraphy = actigraphy,
            audioIrregularity = audioIrregularity,
            confidence = confidence
        )
        epochDao.insertEpoch(epoch)
    }

    suspend fun recordCue(
        sessionId: Long,
        timestamp: Long,
        cadenceName: String,
        confidence: Float,
        heartRate: Int,
        triggerReason: String
    ) = withContext(Dispatchers.IO) {
        val cue = DreamCueEntity(
            sessionId = sessionId,
            timestamp = timestamp,
            cadenceName = cadenceName,
            confidence = confidence,
            heartRate = heartRate,
            triggerReason = triggerReason
        )
        cueDao.insertCue(cue)
    }

    suspend fun finalizeSession(sessionId: Long, endTimeMs: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            val session = sessionDao.getSessionById(sessionId) ?: return@withContext
            val epochs = epochDao.getEpochsListForSession(sessionId)
            val cueCount = cueDao.getCueCountForSession(sessionId)

            val totalEpochs = epochs.size
            if (totalEpochs == 0) {
                sessionDao.deleteSession(sessionId)
                return@withContext
            }

            // Each epoch is exactly 30 seconds (0.5 minutes)
            var awakeCount = 0
            var remCount = 0
            var lightCount = 0
            var deepCount = 0

            epochs.forEach {
                when (SleepStage.fromCode(it.stage)) {
                    SleepStage.AWAKE -> awakeCount++
                    SleepStage.REM -> remCount++
                    SleepStage.LIGHT -> lightCount++
                    SleepStage.DEEP -> deepCount++
                }
            }

            val awakeMinutes = (awakeCount * 30) / 60
            val remMinutes = (remCount * 30) / 60
            val lightMinutes = (lightCount * 30) / 60
            val deepMinutes = (deepCount * 30) / 60

            val netSleepMinutes = remMinutes + lightMinutes + deepMinutes
            val timeInBedMinutes = netSleepMinutes + awakeMinutes

            val efficiency = if (timeInBedMinutes > 0) {
                ((netSleepMinutes.toDouble() / timeInBedMinutes) * 100).toInt().coerceIn(0, 100)
            } else 0

            // Sleep score formula (Oura style):
            // Base 100 - penalize low duration, low efficiency, low deep/rem ratio
            var score = 50
            if (netSleepMinutes >= 420) score += 25 // 7h+
            else if (netSleepMinutes >= 360) score += 20 // 6h+
            else score += (netSleepMinutes / 20)

            score += (efficiency * 0.25).toInt()
            if (deepMinutes >= 60) score += 5
            if (remMinutes >= 60) score += 5

            val finalScore = score.coerceIn(40, 99)

            val updatedSession = session.copy(
                endTime = endTimeMs,
                netSleepMinutes = netSleepMinutes,
                timeInBedMinutes = timeInBedMinutes,
                sleepScore = finalScore,
                efficiency = efficiency,
                deepMinutes = deepMinutes,
                remMinutes = remMinutes,
                lightMinutes = lightMinutes,
                awakeMinutes = awakeMinutes,
                cueCount = cueCount
            )
            sessionDao.updateSession(updatedSession)
        }

    suspend fun deleteSession(sessionId: Long) = withContext(Dispatchers.IO) {
        cueDao.deleteCuesForSession(sessionId)
        epochDao.deleteEpochsForSession(sessionId)
        sessionDao.deleteSession(sessionId)
    }

    /**
     * Seeds realistic mock sleep data for demonstration and immediate UI preview.
     */
    suspend fun seedMockSleepSession(): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val start = now - (7 * 3600 * 1000L + 24 * 60 * 1000L) // 7h 24m ago

        val sessionId = sessionDao.insertSession(
            SleepSessionEntity(
                startTime = start,
                endTime = now,
                netSleepMinutes = 405, // 6h 45m
                timeInBedMinutes = 444, // 7h 24m
                sleepScore = 88,
                efficiency = 91,
                deepMinutes = 95,
                remMinutes = 110,
                lightMinutes = 200,
                awakeMinutes = 39,
                cueCount = 3,
                sessionTitle = "昨夜睡眠 #101"
            )
        )

        // Generate hypnogram step epochs
        val epochs = mutableListOf<SleepEpochEntity>()
        val totalMinutes = 444
        val random = Random(42)

        for (m in 0 until totalMinutes) {
            val epochTime = start + (m * 60 * 1000L)
            val stage = when {
                m < 15 -> SleepStage.AWAKE // Falling asleep
                m in 15..70 -> SleepStage.DEEP // Early deep sleep
                m in 71..105 -> SleepStage.LIGHT
                m in 106..135 -> SleepStage.REM // First REM period
                m in 136..175 -> SleepStage.DEEP
                m in 176..220 -> SleepStage.LIGHT
                m in 221..275 -> SleepStage.REM // Second REM period
                m in 276..285 -> SleepStage.AWAKE // Brief nocturnal awakening
                m in 286..330 -> SleepStage.LIGHT
                m in 331..410 -> SleepStage.REM // Long morning REM period
                m in 411..435 -> SleepStage.LIGHT
                else -> SleepStage.AWAKE
            }

            // Simulated heart rate
            val hrBase = when (stage) {
                SleepStage.DEEP -> 54 + random.nextInt(4) // Deep stable 54-57 bpm
                SleepStage.LIGHT -> 62 + random.nextInt(6) // Light 62-67 bpm
                SleepStage.REM -> 68 + random.nextInt(16) // REM autonomic surge 68-84 bpm
                SleepStage.AWAKE -> 75 + random.nextInt(12) // Awake 75-87 bpm
            }

            // Simulated actigraphy
            val act = when (stage) {
                SleepStage.DEEP -> 0.01f + (random.nextFloat() * 0.01f)
                SleepStage.LIGHT -> 0.03f + (random.nextFloat() * 0.04f)
                SleepStage.REM -> 0.008f + (random.nextFloat() * 0.01f) // Ultra-still muscle atonia
                SleepStage.AWAKE -> 0.22f + (random.nextFloat() * 0.35f)
            }

            epochs.add(
                SleepEpochEntity(
                    sessionId = sessionId,
                    timestamp = epochTime,
                    stage = stage.code,
                    heartRate = hrBase,
                    actigraphy = act,
                    audioIrregularity = if (stage == SleepStage.REM) 0.65f else 0.15f,
                    confidence = if (stage == SleepStage.REM) 0.94f else 0.0f
                )
            )
        }
        epochDao.insertEpochs(epochs)

        // Seed 3 precise dream cues in the REM windows
        cueDao.insertCue(
            DreamCueEntity(
                sessionId = sessionId,
                timestamp = start + (125 * 60 * 1000L),
                cadenceName = "双击-停顿-长震",
                confidence = 0.93f,
                heartRate = 74,
                triggerReason = "首周期REM双重印证命中"
            )
        )
        cueDao.insertCue(
            DreamCueEntity(
                sessionId = sessionId,
                timestamp = start + (250 * 60 * 1000L),
                cadenceName = "双击-停顿-长震",
                confidence = 0.96f,
                heartRate = 79,
                triggerReason = "交感风暴+呼吸变浅吻合"
            )
        )
        cueDao.insertCue(
            DreamCueEntity(
                sessionId = sessionId,
                timestamp = start + (375 * 60 * 1000L),
                cadenceName = "双击-停顿-长震",
                confidence = 0.97f,
                heartRate = 81,
                triggerReason = "清晨大做梦期黄金触发"
            )
        )

        sessionId
    }
}
