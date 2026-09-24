package com.flashalarm.miband.ui.bedside

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flashalarm.miband.FlashAlarmApp
import com.flashalarm.miband.R
import com.flashalarm.miband.domain.model.DualEngineState
import com.flashalarm.miband.domain.model.SleepSessionPhase
import com.flashalarm.miband.service.SleepGuardService
import com.flashalarm.miband.ui.components.SlideToStopSlider
import com.flashalarm.miband.ui.theme.DarkBorder
import com.flashalarm.miband.ui.theme.DarkSurfaceElevated
import com.flashalarm.miband.ui.theme.DarkTextPrimary
import com.flashalarm.miband.ui.theme.DarkTextSecondary
import com.flashalarm.miband.ui.theme.DarkTextTertiary
import com.flashalarm.miband.ui.theme.FlashAlarmTheme
import com.flashalarm.miband.ui.theme.GoldDream
import com.flashalarm.miband.ui.theme.HeartRateRed
import com.flashalarm.miband.ui.theme.MiBandCyan
import com.flashalarm.miband.ui.theme.PureBlack
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import java.util.Locale

class SleepModeActivity : ComponentActivity() {

    fun setScreenBrightness(brightness: Float) {
        try {
            val lp = window.attributes
            lp.screenBrightness = brightness.coerceIn(0.01f, 1.0f)
            window.attributes = lp
        } catch (e: Exception) {
            android.util.Log.w("SleepModeActivity", "Failed setting screen brightness", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            // Bedside Nightstand: Set window brightness to absolute minimum (0.01f)
            setScreenBrightness(0.01f)
        } catch (e: Exception) {
            android.util.Log.w("SleepModeActivity", "Failed setting window lock screen flags", e)
        }

        try {
            hideSystemBars()
        } catch (e: Exception) {
            android.util.Log.w("SleepModeActivity", "Failed hiding system bars", e)
        }

        setContent {
            FlashAlarmTheme {
                SleepModeScreen(
                    onSetScreenBrightness = ::setScreenBrightness,
                    onStopSleepGuard = {
                        try {
                            val stopIntent = Intent(this, SleepGuardService::class.java).apply {
                                action = SleepGuardService.ACTION_STOP_GUARD
                            }
                            startService(stopIntent)
                        } catch (e: Exception) {
                            android.util.Log.e("SleepModeActivity", "Failed stopping service", e)
                        }
                        finish()
                    }
                )
            }
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }
}

@Composable
fun SleepModeScreen(
    onSetScreenBrightness: (Float) -> Unit = {},
    onStopSleepGuard: () -> Unit
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as FlashAlarmApp
    val metrics by app.bleManager.deviceMetrics.collectAsState()
    val audioState by app.audioAnalyzer.state.collectAsState()
    val liveStaging by SleepGuardService.liveStaging.collectAsState()
    val activeCue by SleepGuardService.activeCue.collectAsState()

    val cueConfig by app.userPreferencesRepository.cueConfig.collectAsState()
    val dualState by SleepGuardService.dualEngineState.collectAsState()
    val ecgBleManager = app.ecgBleManager
    val ecgConnectionState by ecgBleManager.connectionState.collectAsState()
    val ecgHr by ecgBleManager.currentHeartRate.collectAsState()
    val isLeadsOff by ecgBleManager.isLeadsOff.collectAsState()
    val ecgLastRr by ecgBleManager.lastRrMs.collectAsState()

    var currentTimeStr by remember { mutableStateOf("") }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var isAwakeBrightness by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTimeStr = timeFormat.format(Date())
            delay(1000L)
        }
    }

    // Auto dim back to lowest hardware brightness after gentle 6-second tap illumination
    LaunchedEffect(isAwakeBrightness) {
        if (isAwakeBrightness) {
            onSetScreenBrightness(0.12f)
            delay(6000L)
            onSetScreenBrightness(0.01f)
            isAwakeBrightness = false
        } else {
            onSetScreenBrightness(0.01f)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // If a dream cue is active, tapping anywhere dismisses the cue and confirms consciousness
                if (activeCue?.isAcknowledged == false) {
                    SleepGuardService.acknowledgeActiveCue(app)
                }
                isAwakeBrightness = true
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        // Top: Soft Subdued Status Indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(GoldDream)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "多模态触梦守护中",
                    fontSize = 12.sp,
                    color = DarkTextTertiary
                )
            }

            Text(
                text = liveStaging?.stage?.displayName ?: "监测中",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = GoldDream
            )
        }

        // Center: OLED Minimal Soft Clock & Vital Indicators
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Soft dim digital clock (low burn-in risk)
            Text(
                text = currentTimeStr.ifBlank { "--:--:--" },
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                color = Color(0xFFC0A868)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Relative Sleep Phase & Active Cue Status Pill
            val currentPhase = liveStaging?.sessionPhase ?: SleepSessionPhase.DETECTING_ONSET
            val phaseColor = when {
                activeCue != null -> GoldDream
                currentPhase == SleepSessionPhase.DETECTING_ONSET -> DarkTextSecondary
                currentPhase == SleepSessionPhase.PROTECTION_PERIOD -> MiBandCyan
                else -> GoldDream
            }
            val phaseText = when {
                activeCue?.isAcknowledged == true -> "🌟 意识触梦成功！已感知梦境并停止提醒"
                activeCue != null -> "✨ 触梦提醒中 · 轻触屏幕任意位置确认已感知"
                currentPhase == SleepSessionPhase.DETECTING_ONSET -> "🌙 正在监测入睡状态 (静息沉淀中...)"
                currentPhase == SleepSessionPhase.PROTECTION_PERIOD -> "🛡️ 前半夜深睡保护期 (剩余 ${liveStaging?.protectionRemainingMinutes ?: 0} 分钟)"
                else -> "✨ REM触梦雷达已全开 (命中即下发)"
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = phaseText,
                    fontSize = 12.sp,
                    color = phaseColor,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Real-time sensor readout chips
            val isDualActive = cueConfig.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.AD8232_DUAL
            val isMlActive = cueConfig.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.ML_MODEL
            val isEcgConnected = ecgConnectionState == com.flashalarm.miband.domain.model.BleConnectionState.CONNECTED && !isLeadsOff
            val isEcgPrimary = isDualActive &&
                    dualState == DualEngineState.ECG_PRIMARY &&
                    isEcgConnected &&
                    ecgHr > 0
            val isMlEcgGainActive = isMlActive && isEcgConnected && ecgLastRr > 0.0

            val displayHr = if (isEcgPrimary) ecgHr else metrics.heartRateBpm

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Heart Rate
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_heart),
                        contentDescription = null,
                        tint = HeartRateRed.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (displayHr > 0) {
                            when {
                                isEcgPrimary -> "$displayHr bpm 🫀"
                                isMlEcgGainActive -> "$displayHr bpm 🫀增益"
                                isMlActive -> "$displayHr bpm ⌚基座"
                                dualState == DualEngineState.SHADOW_PREWARMING -> "$displayHr bpm ⏳预热"
                                dualState == DualEngineState.LATCH_BAND -> "$displayHr bpm ⌚手环"
                                else -> "$displayHr bpm"
                            }
                        } else if (isDualActive && isLeadsOff) {
                            "⚠️导联脱落"
                        } else {
                            "--"
                        },
                        fontSize = 14.sp,
                        color = if (isDualActive && isLeadsOff) Color(0xFFF59E0B) else DarkTextSecondary
                    )
                }

                // If ECG R-R is valid, display live R-R ms
                if ((isEcgPrimary || isMlEcgGainActive) && ecgLastRr > 0.0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "逐搏 %.0fms".format(ecgLastRr),
                            fontSize = 12.sp,
                            color = GoldDream.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Wrist Movement
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_moon),
                        contentDescription = null,
                        tint = MiBandCyan.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (metrics.actigraphyG > 0.05f) "翻身中" else "静止",
                        fontSize = 14.sp,
                        color = DarkTextSecondary
                    )
                }

                // Mic State
                if (audioState.isAnalyzing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (audioState.isAudioReliable) "🎙️ 呼吸平稳" else "🎙️ 嘈杂降级",
                            fontSize = 13.sp,
                            color = DarkTextTertiary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            liveStaging?.let { staging ->
                Text(
                    text = staging.triggerReason,
                    fontSize = 12.sp,
                    color = DarkTextTertiary
                )
            }
        }

        // Bottom: Slide to Stop Slider with damping
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            SlideToStopSlider(
                onStopConfirmed = onStopSleepGuard
            )
        }
    }
}
