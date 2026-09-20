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
            .apply()

        _cueConfig.value = config
    }

    private fun loadCueConfig(): DreamCueConfig {
        val patternsJson = prefs.getString(KEY_PATTERNS_JSON, null)
        val patterns = if (!patternsJson.isNullOrBlank()) {
            deserializePatterns(patternsJson)
        } else {
            DreamCueConfig.defaultPatterns()
        }

        val engineMode = try {
            val modeStr = prefs.getString(KEY_ENGINE_MODE, com.flashalarm.miband.domain.model.RemEngineMode.ML_MODEL.name)
            com.flashalarm.miband.domain.model.RemEngineMode.valueOf(modeStr ?: com.flashalarm.miband.domain.model.RemEngineMode.ML_MODEL.name)
        } catch (e: Exception) {
            com.flashalarm.miband.domain.model.RemEngineMode.ML_MODEL
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
            confidenceThreshold = prefs.getFloat(KEY_CONFIDENCE_THRESHOLD, 0.72f).let { if (it >= 0.849f && it <= 0.851f) 0.72f else it },
            engineMode = engineMode
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
