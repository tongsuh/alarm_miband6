package com.flashalarm.miband.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.flashalarm.miband.FlashAlarmApp
import com.flashalarm.miband.R
import com.flashalarm.miband.domain.model.BleConnectionState
import com.flashalarm.miband.domain.model.DreamCueConfig
import com.flashalarm.miband.ui.theme.DarkBorder
import com.flashalarm.miband.ui.theme.DarkSurface
import com.flashalarm.miband.ui.theme.DarkSurfaceElevated
import com.flashalarm.miband.ui.theme.DarkTextPrimary
import com.flashalarm.miband.ui.theme.DarkTextSecondary
import com.flashalarm.miband.ui.theme.DarkTextTertiary
import com.flashalarm.miband.ui.theme.GoldDream
import com.flashalarm.miband.ui.theme.HeartRateRed
import com.flashalarm.miband.ui.theme.MiBandCyan

@Composable
fun SensorDiagnosticsDialog(
    initialConfig: DreamCueConfig,
    onSaveConfig: (DreamCueConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as FlashAlarmApp
    val bleManager = app.bleManager
    val audioAnalyzer = app.audioAnalyzer

    val connectionState by bleManager.connectionState.collectAsState()
    val metrics by bleManager.deviceMetrics.collectAsState()
    val audioState by audioAnalyzer.state.collectAsState()

    var isMicTesting by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(initialConfig) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            audioAnalyzer.startAnalysis()
            isMicTesting = true
        } else {
            Toast.makeText(context, "未授予麦克风权限，无法测试声学", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isMicTesting) {
                audioAnalyzer.stopAnalysis()
            }
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    AlertDialog(
        onDismissRequest = {
            if (isMicTesting) audioAnalyzer.stopAnalysis()
            onDismiss()
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Text(
                    text = "传感器诊断实验室",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextPrimary
                )
                Text(
                    text = "实时检验手环PPG心率、三轴体动与手机呼吸声学",
                    fontSize = 12.sp,
                    color = DarkTextTertiary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. PPG Heart Rate Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceElevated)
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_heart),
                                    contentDescription = null,
                                    tint = HeartRateRed,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .scale(if (metrics.heartRateBpm > 0) pulseScale else 1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "手环实时心率 (PPG)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = DarkTextPrimary
                                )
                            }

                            Text(
                                text = if (connectionState == BleConnectionState.CONNECTED) "已就绪" else "手环未连接",
                                fontSize = 11.sp,
                                color = if (connectionState == BleConnectionState.CONNECTED) GoldDream else DarkTextTertiary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = if (metrics.heartRateBpm > 0) "${metrics.heartRateBpm}" else "--",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = HeartRateRed
                            )
                            Text(
                                text = "BPM / 实时脉搏",
                                fontSize = 12.sp,
                                color = DarkTextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // HR Sampling Rate Settings
                        Text(
                            text = "心率采样频率分段设置",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DarkTextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        // Stage 1 (Deep sleep)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "阶段一(深睡保护期)", fontSize = 11.sp, color = DarkTextTertiary)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(10, 30, 60).forEach { sec ->
                                    val isSelected = config.stage1HrSampleRateSeconds == sec
                                    Text(
                                        text = "${sec}s",
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) GoldDream else DarkTextTertiary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isSelected) GoldDream.copy(alpha = 0.2f) else DarkSurface)
                                            .clickable { config = config.copy(stage1HrSampleRateSeconds = sec) }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Stage 2 (REM peak window)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "阶段二(REM高发期)", fontSize = 11.sp, color = DarkTextTertiary)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(1, 2, 5).forEach { sec ->
                                    val isSelected = config.stage2HrSampleRateSeconds == sec
                                    val label = if (sec == 1) "1s(流式)" else "${sec}s"
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MiBandCyan else DarkTextTertiary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isSelected) MiBandCyan.copy(alpha = 0.2f) else DarkSurface)
                                            .clickable { config = config.copy(stage2HrSampleRateSeconds = sec) }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Accelerometer Actigraphy Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceElevated)
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_moon),
                                    contentDescription = null,
                                    tint = MiBandCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "手环三轴加速度计 (VM)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = DarkTextPrimary
                                )
                            }

                            val movementDesc = when {
                                metrics.actigraphyG < 0.035f -> "肌肉静止(深度/REM)"
                                metrics.actigraphyG < 0.14f -> "微动"
                                else -> "翻身(一票否决)"
                            }
                            Text(
                                text = movementDesc,
                                fontSize = 11.sp,
                                color = if (metrics.actigraphyG > 0.14f) HeartRateRed else MiBandCyan
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "%.4f g".format(metrics.actigraphyG),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = DarkTextPrimary
                            )
                            Text(
                                text = "向量合模动态增量",
                                fontSize = 11.sp,
                                color = DarkTextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = (metrics.actigraphyG / 0.20f).coerceIn(0f, 1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (metrics.actigraphyG > 0.14f) HeartRateRed else MiBandCyan,
                            trackColor = DarkSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. Microphone Acoustic Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceElevated)
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "手机麦克风夜间呼吸声学",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DarkTextPrimary
                            )

                            Text(
                                text = if (isMicTesting) "测试中" else "未开启",
                                fontSize = 11.sp,
                                color = if (isMicTesting) GoldDream else DarkTextTertiary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (isMicTesting) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "%.1f dB".format(audioState.ambientRmsDb),
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (audioState.isAudioReliable) GoldDream else HeartRateRed
                                )

                                Text(
                                    text = if (audioState.isAudioReliable) "声学环境良好" else "环境噪音过高",
                                    fontSize = 11.sp,
                                    color = if (audioState.isAudioReliable) GoldDream else HeartRateRed
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            LinearProgressIndicator(
                                progress = ((audioState.ambientRmsDb - 20f) / 50f).coerceIn(0f, 1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (audioState.isAudioReliable) GoldDream else HeartRateRed,
                                trackColor = DarkSurface
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Button(
                            onClick = {
                                if (isMicTesting) {
                                    audioAnalyzer.stopAnalysis()
                                    isMicTesting = false
                                } else {
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasPermission) {
                                        audioAnalyzer.startAnalysis()
                                        isMicTesting = true
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isMicTesting) HeartRateRed else GoldDream
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isMicTesting) "停止麦克风测试" else "开启麦克风声学校准测试",
                                color = Color.Black,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isMicTesting) audioAnalyzer.stopAnalysis()
                    onSaveConfig(config)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = GoldDream),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("保存设置", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (isMicTesting) audioAnalyzer.stopAnalysis()
                    onDismiss()
                }
            ) {
                Text("关闭", color = DarkTextSecondary)
            }
        }
    )
}
