package com.flashalarm.miband.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SleepSessionEntity::class,
        SleepEpochEntity::class,
        DreamCueEntity::class,
        AlgorithmDiagnosticEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class SleepDatabase : RoomDatabase() {
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun sleepEpochDao(): SleepEpochDao
    abstract fun dreamCueDao(): DreamCueDao
    abstract fun algorithmDiagnosticDao(): AlgorithmDiagnosticDao

    companion object {
        @Volatile
        private var INSTANCE: SleepDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dream_cues ADD COLUMN acknowledged INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `algorithm_diagnostics` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `stage` INTEGER NOT NULL,
                        `heartRate` INTEGER NOT NULL,
                        `hrSurgePercent` REAL NOT NULL,
                        `atoniaScore` REAL NOT NULL,
                        `baseRemProb` REAL NOT NULL,
                        `eogBursts` INTEGER NOT NULL,
                        `eogSignalQuality` TEXT NOT NULL,
                        `alphaGating` REAL NOT NULL,
                        `rawLogitBoost` REAL NOT NULL,
                        `effectiveLogitBoost` REAL NOT NULL,
                        `fusedRemProb` REAL NOT NULL,
                        `confidenceBoost` REAL NOT NULL,
                        `effectiveThreshold` REAL NOT NULL,
                        `isCueTriggered` INTEGER NOT NULL,
                        `isCueEligible` INTEGER NOT NULL,
                        `consecutiveRemCount` INTEGER NOT NULL,
                        `triggerReason` TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_algorithm_diagnostics_sessionId` ON `algorithm_diagnostics` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_algorithm_diagnostics_timestamp` ON `algorithm_diagnostics` (`timestamp`)")
            }
        }

        fun getDatabase(context: Context): SleepDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SleepDatabase::class.java,
                    "flashalarm_sleep.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
