package com.flashalarm.miband.domain.algorithm

import android.util.Log

/**
 * HrvAdaptationController
 * Manages opportunistic AD8232 True-HRV residual gain adaptation:
 *   - Instant zeroing on LOD (Leads-off Detection) or 3-second BLE watchdog timeout
 *   - Physiological gating (333ms..1500ms / 40..180 bpm) & contact spike rejection (|ΔRR| <= 250ms)
 *   - 3-second continuous clean contact debouncing
 *   - 30-second epoch valid beat quota check (N >= 18)
 *   - 3-epoch (90s) smooth inertial ramp-up (0.30 -> 0.70 -> 1.00)
 *   - Prevents transient electrode dropouts from polluting 1Hz base sleep staging
 */
class HrvAdaptationController {

    companion object {
        private const val TAG = "HrvAdaptation"
        const val WATCHDOG_TIMEOUT_MS = 3000L
        const val MIN_VALID_BEATS_PER_EPOCH = 18
        const val DEBOUNCE_STABLE_TIME_MS = 3000L
    }

    enum class State(val displayName: String) {
        OFFLINE("心电未连"),
        LEADS_OFF_FALLBACK("电极脱落兜底"),
        CONTACT_DEBOUNCING("触点去颤校准"),
        RAMP_UP("精度爬坡注入"),
        ACTIVE_FULL("双擎全效增益")
    }

    var currentState: State = State.OFFLINE
        private set

    var currentWeight: Float = 0.0f
        private set

    private var lastValidPacketTimeMs: Long = 0L
    private var debounceStartTimeMs: Long = 0L
    private var lastAcceptedRrMs: Double = 0.0
    private var epochValidBeatsCount: Int = 0
    private var consecutiveValidEpochs: Int = 0

    @Synchronized
    fun onRawRrIntervalReceived(
        rrMs: Double,
        isHardwareLeadsOff: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        // 1. Hardware LOD or zero RR -> instant fallback
        if (isHardwareLeadsOff || rrMs <= 0.0) {
            triggerInstantFallback(nowMs, "硬件脱落 LOD 置位或 RR 异常 ($rrMs ms)")
            return false
        }

        // 2. Gate 1: Physiological range [333ms, 1500ms] (40 ~ 180 bpm)
        if (rrMs < 333.0 || rrMs > 1500.0) {
            Log.d(TAG, "生理门禁拦截异常心拍: ${rrMs.toInt()}ms")
            return false
        }

        // 3. Gate 2: Inter-beat acceleration constraint (|ΔRR| <= 250ms)
        // Discard high-amplitude mechanical contact friction spikes
        if (lastAcceptedRrMs > 0.0 && kotlin.math.abs(rrMs - lastAcceptedRrMs) > 250.0) {
            Log.d(TAG, "接触摩擦尖峰去颤: ΔRR=${kotlin.math.abs(rrMs - lastAcceptedRrMs).toInt()}ms")
            lastAcceptedRrMs = rrMs
            return false
        }

        lastAcceptedRrMs = rrMs
        lastValidPacketTimeMs = nowMs

        // 4. State transitions on clean beat
        when (currentState) {
            State.OFFLINE, State.LEADS_OFF_FALLBACK -> {
                Log.i(TAG, "心电信号初现，进入触点去颤阶段...")
                currentState = State.CONTACT_DEBOUNCING
                debounceStartTimeMs = nowMs
                epochValidBeatsCount = 1
            }
            State.CONTACT_DEBOUNCING -> {
                epochValidBeatsCount++
                if (nowMs - debounceStartTimeMs >= DEBOUNCE_STABLE_TIME_MS) {
                    // Debounce stable, waiting for full 30s epoch boundary count
                }
            }
            State.RAMP_UP, State.ACTIVE_FULL -> {
                epochValidBeatsCount++
            }
        }
        return true
    }

    @Synchronized
    fun onWatchdogTick(nowMs: Long = System.currentTimeMillis()) {
        if (currentState != State.OFFLINE && currentState != State.LEADS_OFF_FALLBACK) {
            if (lastValidPacketTimeMs > 0L && (nowMs - lastValidPacketTimeMs) > WATCHDOG_TIMEOUT_MS) {
                triggerInstantFallback(nowMs, "数据流看门狗超时 (${nowMs - lastValidPacketTimeMs}ms > 3000ms)")
            }
        }
    }

    @Synchronized
    fun onEpochBoundary(): Float {
        val validBeats = epochValidBeatsCount
        epochValidBeatsCount = 0

        when (currentState) {
            State.OFFLINE, State.LEADS_OFF_FALLBACK -> {
                currentWeight = 0.0f
                consecutiveValidEpochs = 0
            }
            State.CONTACT_DEBOUNCING -> {
                if (validBeats >= MIN_VALID_BEATS_PER_EPOCH) {
                    currentState = State.RAMP_UP
                    consecutiveValidEpochs = 1
                    currentWeight = 0.30f
                    Log.i(TAG, "周期心拍达标 (N=$validBeats >= 18)，启动第一阶段爬坡: W_hrv = 0.30")
                } else {
                    Log.w(TAG, "周期心拍稀疏 (N=$validBeats < 18)，维持静默兜底")
                    currentState = State.LEADS_OFF_FALLBACK
                    currentWeight = 0.0f
                    consecutiveValidEpochs = 0
                }
            }
            State.RAMP_UP -> {
                if (validBeats >= MIN_VALID_BEATS_PER_EPOCH) {
                    consecutiveValidEpochs++
                    when (consecutiveValidEpochs) {
                        2 -> {
                            currentWeight = 0.70f
                            Log.i(TAG, "持续洁净第 2 周期: W_hrv = 0.70")
                        }
                        else -> {
                            currentState = State.ACTIVE_FULL
                            currentWeight = 1.00f
                            Log.i(TAG, "自愈爬坡完成，重返全效增益: W_hrv = 1.00")
                        }
                    }
                } else {
                    triggerInstantFallback(System.currentTimeMillis(), "爬坡中途心拍骤降 (N=$validBeats)")
                }
            }
            State.ACTIVE_FULL -> {
                if (validBeats >= MIN_VALID_BEATS_PER_EPOCH) {
                    currentWeight = 1.00f
                } else {
                    triggerInstantFallback(System.currentTimeMillis(), "全效运行中心拍中断 (N=$validBeats)")
                }
            }
        }
        return currentWeight
    }

    private fun triggerInstantFallback(nowMs: Long, reason: String) {
        if (currentState != State.LEADS_OFF_FALLBACK && currentState != State.OFFLINE) {
            Log.w(TAG, "触发瞬时安全容错: $reason | W_hrv 立即重置为 0.0")
            currentState = State.LEADS_OFF_FALLBACK
            currentWeight = 0.0f
            lastAcceptedRrMs = 0.0
            debounceStartTimeMs = 0L
            consecutiveValidEpochs = 0
            epochValidBeatsCount = 0
        }
    }

    @Synchronized
    fun onBleConnected() {
        if (currentState == State.OFFLINE) {
            currentState = State.LEADS_OFF_FALLBACK
            currentWeight = 0.0f
            lastAcceptedRrMs = 0.0
            debounceStartTimeMs = 0L
            consecutiveValidEpochs = 0
            epochValidBeatsCount = 0
        }
    }

    @Synchronized
    fun onBleDisconnected() {
        currentState = State.OFFLINE
        currentWeight = 0.0f
        lastAcceptedRrMs = 0.0
        debounceStartTimeMs = 0L
        consecutiveValidEpochs = 0
        epochValidBeatsCount = 0
    }

    @Synchronized
    fun reset() {
        currentState = State.OFFLINE
        currentWeight = 0.0f
        lastValidPacketTimeMs = 0L
        debounceStartTimeMs = 0L
        lastAcceptedRrMs = 0.0
        epochValidBeatsCount = 0
        consecutiveValidEpochs = 0
    }
}
