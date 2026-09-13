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
import com.flashalarm.miband.service.SleepGuardService
import com.flashalarm.miband.ui.components.SlideToStopSlider
import com.flashalarm.miband.ui.theme.DarkBorder
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
import java.util.Locale

class SleepModeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure full screen and keep screen on for OLED bedside display
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        hideSystemBars()

        setContent {
            FlashAlarmTheme {
                SleepModeScreen(
                    onStopSleepGuard = {
                        val stopIntent = Intent(this, SleepGuardService::class.java).apply {
                            action = SleepGuardService.ACTION_STOP_GUARD
                        }
                        startService(stopIntent)
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
    onStopSleepGuard: () -> Unit
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as FlashAlarmApp
    val metrics by app.bleManager.deviceMetrics.collectAsState()
    val audioState by app.audioAnalyzer.state.collectAsState()
    val liveStaging by SleepGuardService.liveStaging.collectAsState()

    var currentTimeStr by remember { mutableStateOf("") }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // 1-second clock tick
    LaunchedEffect(Unit) {
        while (true) {
            currentTimeStr = timeFormat.format(Date())
            delay(1000L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
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
                fontSize = 46.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                color = Color(0xFFC0A868) // Soft muted warm amber
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Real-time sensor readout chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
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
                        text = if (metrics.heartRateBpm > 0) "${metrics.heartRateBpm} bpm" else "--",
                        fontSize = 14.sp,
                        color = DarkTextSecondary
                    )
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
                        text = if (metrics.actigraphyG > 0.05f) "翻身中" else "肌肉瘫痪(静止)",
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

            // Stage confidence or timing note
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
