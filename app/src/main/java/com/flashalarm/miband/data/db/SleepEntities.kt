package com.flashalarm.miband.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sleep_sessions")
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val sessionId: Long = 0L,
    val startTime: Long,
    val endTime: Long,
    val netSleepMinutes: Int,
    val timeInBedMinutes: Int,
    val sleepScore: Int,
    val efficiency: Int,
    val deepMinutes: Int,
    val remMinutes: Int,
    val lightMinutes: Int,
    val awakeMinutes: Int,
    val cueCount: Int,
    val sessionTitle: String = "夜间睡眠"
)

@Entity(
    tableName = "sleep_epochs",
    indices = [Index(value = ["sessionId"]), Index(value = ["timestamp"])]
)
data class SleepEpochEntity(
    @PrimaryKey(autoGenerate = true)
    val epochId: Long = 0L,
    val sessionId: Long,
    val timestamp: Long,
    val stage: Int, // 0: AWAKE, 1: REM, 2: LIGHT, 3: DEEP
    val heartRate: Int,
    val actigraphy: Float,
    val audioIrregularity: Float,
    val confidence: Float
)

@Entity(
    tableName = "dream_cues",
    indices = [Index(value = ["sessionId"])]
)
data class DreamCueEntity(
    @PrimaryKey(autoGenerate = true)
    val cueId: Long = 0L,
    val sessionId: Long,
    val timestamp: Long,
    val cadenceName: String,
    val confidence: Float,
    val heartRate: Int,
    val triggerReason: String,
    val acknowledged: Boolean = false
)

@Entity(
    tableName = "algorithm_diagnostics",
    indices = [Index(value = ["sessionId"]), Index(value = ["timestamp"])]
)
data class AlgorithmDiagnosticEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: Long,
    val timestamp: Long,
    val stage: Int, // 0: AWAKE, 1: REM, 2: LIGHT, 3: DEEP
    val heartRate: Int,
    val hrSurgePercent: Float,
    val atoniaScore: Float,
    val baseRemProb: Float,
    val eogBursts: Int,
    val eogSignalQuality: String,
    val alphaGating: Float,
    val rawLogitBoost: Float,
    val effectiveLogitBoost: Float,
    val fusedRemProb: Float,
    val confidenceBoost: Float,
    val effectiveThreshold: Float,
    val isCueTriggered: Boolean,
    val isCueEligible: Boolean,
    val consecutiveRemCount: Int,
    val triggerReason: String
)
