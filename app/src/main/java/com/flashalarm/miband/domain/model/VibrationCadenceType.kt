package com.flashalarm.miband.domain.model

enum class VibrationCadenceType(val displayName: String, val patternDescription: String) {
    DOUBLE_TAP_LONG("双击-停顿-长震 (推荐)", "200ms双震 + 600ms间歇 + 800ms微长震"),
    TRIPLE_PULSE("三重渐弱微脉冲", "3次轻柔150ms微震"),
    GENTLE_WAVE("平缓起伏渐变波浪", "300ms至500ms渐进起伏"),
    HEARTBEAT("律动拟真心跳微震", "模仿深睡心跳微震");
}
