package com.flashalarm.miband.domain.model

enum class DualEngineState {
    ECG_PRIMARY,       // AD8232 为主，真心电双模态 ML
    SHADOW_PREWARMING, // 8232 蓝牙重连后在后台静默攒数据预热 (需连续 21 个 Epoch/10.5分钟无异常)
    LATCH_BAND         // 电极真脱落，单向锁存到手环模式，整夜不再切回 8232
}
