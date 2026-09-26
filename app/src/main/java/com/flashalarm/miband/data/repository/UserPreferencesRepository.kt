package com.flashalarm.miband.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.flashalarm.miband.domain.model.CustomizableVibrationPattern
import com.flashalarm.miband.domain.model.DreamCueConfig
import com.flashalarm.miband.domain.model.PatternType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class UserPreferencesRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("flashalarm_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "UserPreferencesRepo"
        private const val KEY_MAC = "pref_device_mac"
        private const val KEY_AUTH_KEY = "pref_auth_key"

        // Cue Configuration Keys
        private const val KEY_ACTIVE_PATTERN_ID = "pref_active_pattern_id"
        private const val KEY_PATTERNS_JSON = "pref_patterns_json"
        private const val KEY_ENABLE_WRIST_VIBE = "pref_enable_wrist_vibe"
        private const val KEY_ENABLE_AUDIO_PLAYBACK = "pref_enable_audio_playback"
        private const val KEY_AUDIO_DURATION_SEC = "pref_audio_duration_sec"
        private const val KEY_AUDIO_VOLUME_PCT = "pref_audio_volume_pct"
        private const val KEY_CUSTOM_AUDIO_PATH = "pref_custom_audio_path"
        private const val KEY_CUSTOM_AUDIO_NAME = "pref_custom_audio_name"

        private const val KEY_ONSET_PROTECT_HOURS = "pref_onset_protect_hours"
        private const val KEY_STAGE1_HR_SEC = "pref_stage1_hr_sec"
        private const val KEY_STAGE2_HR_SEC = "pref_stage2_hr_sec"
        private const val KEY_COOLDOWN_MIN = "pref_cooldown_min"
        private const val KEY_ENABLE_AUDIO_VERIFY = "pref_enable_audio_verify"
        private const val KEY_CONFIDENCE_THRESHOLD = "pref_confidence_threshold"
        private const val KEY_USE_2021_PROTOCOL = "pref_use_2021_protocol"
        private const val KEY_ENGINE_MODE = "pref_rem_engine_mode"
        private const val KEY_ENABLE_AD8232_ECG = "pref_enable_ad8232_ecg"
        private const val KEY_AD8232_MAC = "pref_ad8232_mac"
        private const val KEY_AD8232_NAME = "pref_ad8232_name"
        private const val KEY_ENABLE_EOG_DEVICE = "pref_enable_eog_device"
        private const val KEY_EOG_MAC = "pref_eog_mac"
        private const val KEY_EOG_NAME = "pref_eog_name"
        private const val KEY_ENABLE_DIAGNOSTICS = "pref_enable_algorithm_diagnostics"
        private const val KEY_DIAGNOSTIC_RETENTION_DAYS = "pref_diagnostic_retention_days"
    }

    private val _cueConfig = MutableStateFlow(loadCueConfig())
    val cueConfig: StateFlow<DreamCueConfig> = _cueConfig.asStateFlow()

    private val _use2021Protocol = MutableStateFlow(prefs.getBoolean(KEY_USE_2021_PROTOCOL, true))
    val use2021Protocol: StateFlow<Boolean> = _use2021Protocol.asStateFlow()

    fun getUse2021Protocol(): Boolean = _use2021Protocol.value
    fun setUse2021Protocol(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_2021_PROTOCOL, enabled).apply()
        _use2021Protocol.value = enabled
    }

    fun getDeviceMac(): String = prefs.getString(KEY_MAC, "") ?: ""
    fun saveDeviceMac(mac: String) = prefs.edit().putString(KEY_MAC, mac).apply()

    fun getAuthKeyHex(): String = prefs.getString(KEY_AUTH_KEY, "") ?: ""
    fun saveAuthKeyHex(authKey: String) = prefs.edit().putString(KEY_AUTH_KEY, authKey).apply()

    fun updateCueConfig(config: DreamCueConfig) {
        val patternsJson = serializePatterns(config.patterns)

        prefs.edit()
            .putString(KEY_ACTIVE_PATTERN_ID, config.activePatternId)
            .putString(KEY_PATTERNS_JSON, patternsJson)
            .putBoolean(KEY_ENABLE_WRIST_VIBE, config.enableWristVibration)
            .putBoolean(KEY_ENABLE_AUDIO_PLAYBACK, config.enableAudioPlayback)
            .putInt(KEY_AUDIO_DURATION_SEC, config.audioDurationSeconds)
            .putInt(KEY_AUDIO_VOLUME_PCT, config.audioVolumePercent)
            .putString(KEY_CUSTOM_AUDIO_PATH, config.customAudioPath)
            .putString(KEY_CUSTOM_AUDIO_NAME, config.customAudioName)
            .putFloat(KEY_ONSET_PROTECT_HOURS, config.sleepOnsetProtectionHours)
            .putInt(KEY_STAGE1_HR_SEC, config.stage1HrSampleRateSeconds)
            .putInt(KEY_STAGE2_HR_SEC, config.stage2HrSampleRateSeconds)
            .putInt(KEY_COOLDOWN_MIN, config.cooldownMinutes)
            .putBoolean(KEY_ENABLE_AUDIO_VERIFY, config.enableAudioVerification)
            .putFloat(KEY_CONFIDENCE_THRESHOLD, config.confidenceThreshold)
            .putString(KEY_ENGINE_MODE, config.engineMode.name)
            .putBoolean(KEY_ENABLE_AD8232_ECG, config.enableAd8232Ecg)
            .putString(KEY_AD8232_MAC, config.ad8232MacAddress)
            .putString(KEY_AD8232_NAME, config.ad8232DeviceName)
            .putBoolean(KEY_ENABLE_EOG_DEVICE, config.enableEogDevice)
            .putString(KEY_EOG_MAC, config.eogMacAddress)
            .putString(KEY_EOG_NAME, config.eogDeviceName)
            .putBoolean(KEY_ENABLE_DIAGNOSTICS, config.enableAlgorithmDiagnostics)
            .putInt(KEY_DIAGNOSTIC_RETENTION_DAYS, config.diagnosticRetentionDays)
            .apply()

        _cueConfig.value = config
    }

    fun getEcgMac(): String = prefs.getString(KEY_AD8232_MAC, "") ?: ""
    fun saveEcgMac(mac: String) {
        prefs.edit().putString(KEY_AD8232_MAC, mac).apply()
        _cueConfig.value = _cueConfig.value.copy(ad8232MacAddress = mac)
    }

    fun getEogMac(): String = prefs.getString(KEY_EOG_MAC, "") ?: ""
    fun saveEogMac(mac: String) {
        prefs.edit().putString(KEY_EOG_MAC, mac).apply()
        _cueConfig.value = _cueConfig.value.copy(eogMacAddress = mac)
    }

    private fun loadCueConfig(): DreamCueConfig {
        val patternsJson = prefs.getString(KEY_PATTERNS_JSON, null)
        val patterns = if (!patternsJson.isNullOrBlank()) {
            deserializePatterns(patternsJson)
        } else {
            DreamCueConfig.defaultPatterns()
        }

        val engineMode = try {
            val modeStr = prefs.getString(KEY_ENGINE_MODE, com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL.name)
            com.flashalarm.miband.domain.model.RemEngineMode.valueOf(modeStr ?: com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL.name)
        } catch (e: Exception) {
            com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL
        }

        return DreamCueConfig(
            activePatternId = prefs.getString(KEY_ACTIVE_PATTERN_ID, "crescendo") ?: "crescendo",
            patterns = patterns,
            enableWristVibration = prefs.getBoolean(KEY_ENABLE_WRIST_VIBE, true),
            enableAudioPlayback = prefs.getBoolean(KEY_ENABLE_AUDIO_PLAYBACK, false),
            audioDurationSeconds = prefs.getInt(KEY_AUDIO_DURATION_SEC, 15),
            audioVolumePercent = prefs.getInt(KEY_AUDIO_VOLUME_PCT, 50),
            customAudioPath = prefs.getString(KEY_CUSTOM_AUDIO_PATH, "") ?: "",
            customAudioName = prefs.getString(KEY_CUSTOM_AUDIO_NAME, "默认潜意识耳语") ?: "默认潜意识耳语",
            sleepOnsetProtectionHours = prefs.getFloat(KEY_ONSET_PROTECT_HOURS, 2.5f),
            stage1HrSampleRateSeconds = prefs.getInt(KEY_STAGE1_HR_SEC, 30),
            stage2HrSampleRateSeconds = prefs.getInt(KEY_STAGE2_HR_SEC, 1),
            cooldownMinutes = prefs.getInt(KEY_COOLDOWN_MIN, 20),
            enableAudioVerification = prefs.getBoolean(KEY_ENABLE_AUDIO_VERIFY, true),
            confidenceThreshold = prefs.getFloat(KEY_CONFIDENCE_THRESHOLD, 0.55f).let { if (it >= 0.849f && it <= 0.851f) 0.55f else it },
            engineMode = engineMode,
            enableAd8232Ecg = prefs.getBoolean(KEY_ENABLE_AD8232_ECG, true),
            ad8232MacAddress = prefs.getString(KEY_AD8232_MAC, "") ?: "",
            ad8232DeviceName = prefs.getString(KEY_AD8232_NAME, "FlashAlarm-ECG") ?: "FlashAlarm-ECG",
            enableEogDevice = prefs.getBoolean(KEY_ENABLE_EOG_DEVICE, false),
            eogMacAddress = prefs.getString(KEY_EOG_MAC, "") ?: "",
            eogDeviceName = prefs.getString(KEY_EOG_NAME, "FlashAlarm-EOG") ?: "FlashAlarm-EOG",
            enableAlgorithmDiagnostics = prefs.getBoolean(KEY_ENABLE_DIAGNOSTICS, true),
            diagnosticRetentionDays = prefs.getInt(KEY_DIAGNOSTIC_RETENTION_DAYS, 30)
        )
    }

    private fun serializePatterns(patterns: List<CustomizableVibrationPattern>): String {
        return try {
            val array = JSONArray()
            for (p in patterns) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("type", p.type.name)
                    put("startIntensityPercent", p.startIntensityPercent)
                    put("endIntensityPercent", p.endIntensityPercent)
                    put("pulseMs", p.pulseMs)
                    put("pauseMs", p.pauseMs)
                    put("durationSeconds", p.durationSeconds)
                    put("repeatCount", p.repeatCount)
                }
                array.put(obj)
            }
            array.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed serializing patterns", e)
            ""
        }
    }

    private fun deserializePatterns(jsonStr: String): List<CustomizableVibrationPattern> {
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<CustomizableVibrationPattern>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val typeName = obj.optString("type", PatternType.CRESCENDO.name)
                val type = try {
                    PatternType.valueOf(typeName)
                } catch (e: Exception) {
                    PatternType.CRESCENDO
                }

                list.add(
                    CustomizableVibrationPattern(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        type = type,
                        startIntensityPercent = obj.optInt("startIntensityPercent", 20),
                        endIntensityPercent = obj.optInt("endIntensityPercent", 80),
                        pulseMs = obj.optInt("pulseMs", 200),
                        pauseMs = obj.optInt("pauseMs", 600),
                        durationSeconds = obj.optInt("durationSeconds", 15),
                        repeatCount = obj.optInt("repeatCount", 3)
                    )
                )
            }
            if (list.isNotEmpty()) list else DreamCueConfig.defaultPatterns()
        } catch (e: Exception) {
            Log.e(TAG, "Failed deserializing patterns, returning defaults", e)
            DreamCueConfig.defaultPatterns()
        }
    }
}
