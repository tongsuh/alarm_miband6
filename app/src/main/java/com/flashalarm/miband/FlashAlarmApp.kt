package com.flashalarm.miband

import android.app.Application
import com.flashalarm.miband.data.audio.BreathingAudioAnalyzer
import com.flashalarm.miband.data.audio.DreamAudioPlayer
import com.flashalarm.miband.data.ble.MiBandBleManager
import com.flashalarm.miband.data.db.SleepDatabase
import com.flashalarm.miband.data.repository.SleepRepository
import com.flashalarm.miband.data.repository.UserPreferencesRepository
import com.flashalarm.miband.domain.algorithm.MultiModalRemEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FlashAlarmApp : Application() {
    val applicationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val database by lazy { SleepDatabase.getDatabase(this) }
    val sleepRepository by lazy { SleepRepository(database) }
    val userPreferencesRepository by lazy { UserPreferencesRepository(this) }
    val bleManager by lazy { MiBandBleManager(this, applicationScope) }
    val audioPlayer by lazy { DreamAudioPlayer(this, applicationScope) }
    val audioAnalyzer by lazy { BreathingAudioAnalyzer(applicationScope) }
    val remEngine by lazy { MultiModalRemEngine(userPreferencesRepository.cueConfig.value) }

    override fun onCreate() {
        super.onCreate()
        // Preload saved target device if available
        val mac = userPreferencesRepository.getDeviceMac()
        val authKey = userPreferencesRepository.getAuthKeyHex()
        val use2021 = userPreferencesRepository.getUse2021Protocol()
        if (mac.isNotBlank()) {
            bleManager.setTargetDevice("Mi Smart Band 6", mac, authKey, use2021)
        }
    }
}
