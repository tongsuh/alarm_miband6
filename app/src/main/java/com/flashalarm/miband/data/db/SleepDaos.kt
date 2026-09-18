package com.flashalarm.miband.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SleepSessionEntity): Long

    @Update
    suspend fun updateSession(session: SleepSessionEntity)

    @Query("SELECT * FROM sleep_sessions ORDER BY sessionId DESC")
    fun getAllSessions(): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM sleep_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): SleepSessionEntity?

    @Query("SELECT * FROM sleep_sessions ORDER BY sessionId DESC LIMIT 1")
    fun getLatestSession(): Flow<SleepSessionEntity?>

    @Query("DELETE FROM sleep_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: Long)
}

@Dao
interface SleepEpochDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpoch(epoch: SleepEpochEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpochs(epochs: List<SleepEpochEntity>)

    @Query("SELECT * FROM sleep_epochs WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getEpochsForSession(sessionId: Long): Flow<List<SleepEpochEntity>>

    @Query("SELECT * FROM sleep_epochs WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getEpochsListForSession(sessionId: Long): List<SleepEpochEntity>

    @Query("DELETE FROM sleep_epochs WHERE sessionId = :sessionId")
    suspend fun deleteEpochsForSession(sessionId: Long)
}

@Dao
interface DreamCueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCue(cue: DreamCueEntity): Long

    @Query("SELECT * FROM dream_cues WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getCuesForSession(sessionId: Long): Flow<List<DreamCueEntity>>

    @Query("SELECT COUNT(*) FROM dream_cues WHERE sessionId = :sessionId")
    suspend fun getCueCountForSession(sessionId: Long): Int

    @Query("UPDATE dream_cues SET acknowledged = 1 WHERE cueId = :cueId")
    suspend fun markCueAcknowledged(cueId: Long)

    @Query("DELETE FROM dream_cues WHERE sessionId = :sessionId")
    suspend fun deleteCuesForSession(sessionId: Long)
}
