package com.flashalarm.miband.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.flashalarm.miband.domain.model.DreamCueConfig
import com.flashalarm.miband.domain.model.VibrationCadenceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserPreferencesRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("flashalarm_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_MAC = "pref_device_mac"
        private const val KEY_AUTH_KEY = "pref_auth_key"
        private const val KEY_CADENCE = "pref_cadence_type"
        private const val KEY_COOLDOWN = "pref_cooldown_min"
        private const val KEY_ONSET_MIN = "pref_onset_min"
        private const val KEY_ENABLE_AUDIO = "pref_enable_audio"
        private const val KEY_ENABLE_FLASH = "pref_enable_flash"
        private const val KEY_ENABLE_WHISPER = "pref_enable_whisper"
    }

    private val _cueConfig = MutableStateFlow(loadCueConfig())
    val cueConfig: StateFlow<DreamCueConfig> = _cueConfig.asStateFlow()

    fun getDeviceMac(): String = prefs.getString(KEY_MAC, "") ?: ""
    fun saveDeviceMac(mac: String) = prefs.edit().putString(KEY_MAC, mac).apply()

    fun getAuthKeyHex(): String = prefs.getString(KEY_AUTH_KEY, "") ?: ""
    fun saveAuthKeyHex(authKey: String) = prefs.edit().putString(KEY_AUTH_KEY, authKey).apply()

    fun updateCueConfig(config: DreamCueConfig) {
        prefs.edit()
            .putString(KEY_CADENCE, config.cadenceType.name)
            .putInt(KEY_COOLDOWN, config.cooldownMinutes)
            .putInt(KEY_ONSET_MIN, config.minSleepOnsetMinutes)
            .putBoolean(KEY_ENABLE_AUDIO, config.enableAudioVerification)
            .putBoolean(KEY_ENABLE_FLASH, config.enableScreenRedFlash)
            .putBoolean(KEY_ENABLE_WHISPER, config.enableVoiceWhisper)
            .apply()
        _cueConfig.value = config
    }

    private fun loadCueConfig(): DreamCueConfig {
        val cadenceName = prefs.getString(KEY_CADENCE, VibrationCadenceType.DOUBLE_TAP_LONG.name)
        val cadence = try {
            VibrationCadenceType.valueOf(cadenceName ?: VibrationCadenceType.DOUBLE_TAP_LONG.name)
        } catch (e: Exception) {
            VibrationCadenceType.DOUBLE_TAP_LONG
        }

        return DreamCueConfig(
            cadenceType = cadence,
            cooldownMinutes = prefs.getInt(KEY_COOLDOWN, 25),
            minSleepOnsetMinutes = prefs.getInt(KEY_ONSET_MIN, 75),
            enableAudioVerification = prefs.getBoolean(KEY_ENABLE_AUDIO, true),
            enableScreenRedFlash = prefs.getBoolean(KEY_ENABLE_FLASH, false),
            enableVoiceWhisper = prefs.getBoolean(KEY_ENABLE_WHISPER, false)
        )
    }
}
