package com.flashalarm.miband.domain.model

enum class SleepStage(val level: Int, val displayName: String, val code: Int) {
    DEEP(0, "深睡", 3),
    LIGHT(1, "浅睡", 2),
    REM(2, "做梦期", 1),
    AWAKE(3, "清醒", 0);

    companion object {
        fun fromCode(code: Int): SleepStage {
            return entries.firstOrNull { it.code == code } ?: AWAKE
        }
    }
}
