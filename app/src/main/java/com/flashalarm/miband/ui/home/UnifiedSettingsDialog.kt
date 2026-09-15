package com.flashalarm.miband.ui.home

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
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
import com.flashalarm.miband.FlashAlarmApp
import com.flashalarm.miband.R
import com.flashalarm.miband.data.ble.MiBandBleManager
import com.flashalarm.miband.domain.model.BleConnectionState
import com.flashalarm.miband.domain.model.CustomizableVibrationPattern
import com.flashalarm.miband.domain.model.DreamCueConfig
import com.flashalarm.miband.ui.theme.AlertPurple
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
fun UnifiedSettingsDialog(
    bleManager: MiBandBleManager,
    connectionState: BleConnectionState,
    initialConfig: DreamCueConfig,
    use2021Protocol: Boolean,
    onSaveConfig: (DreamCueConfig) -> Unit,
    onToggle2021Protocol: (Boolean) -> Unit,
    onPickAudioFile: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as FlashAlarmApp
    val audioPlayer = app.audioPlayer
    val metrics by bleManager.deviceMetrics.collectAsState()
    val deviceInfo by bleManager.deviceInfo.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var config by remember { mutableStateOf(initialConfig) }
    var isTestingAudio by remember { mutableStateOf(false) }
    var isTestingVibration by remember { mutableStateOf(false) }
    var newProtocolEnabled by remember { mutableStateOf(use2021Protocol) }
    var isHrToggling by remember { mutableStateOf(false) }

    LaunchedEffect(isHrToggling) {
        if (isHrToggling) {
            delay(500L)
            isHrToggling = false
        }
    }

    val tabs = listOf("传感器测试", "震动工坊", "声音设置", "认证协议")

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

    DisposableEffect(Unit) {
        onDispose {
            bleManager.stopVibration()
            if (isTestingAudio) {
                audioPlayer.stopAudio()
            }
        }
    }

    val currentPattern = config.patterns.firstOrNull { it.id == config.activePatternId } ?: config.patterns.first()

    fun updateCurrentPattern(transform: (CustomizableVibrationPattern) -> CustomizableVibrationPattern) {
        val updatedPatterns = config.patterns.map {
            if (it.id == config.activePatternId) transform(it) else it
        }
        val updatedConfig = config.copy(patterns = updatedPatterns)
        config = updatedConfig
        onSaveConfig(updatedConfig)
    }

    AlertDialog(
        onDismissRequest = {
            onSaveConfig(config)
            onDismiss()
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "外设与系统综合设置",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                    Text(
                        text = if (connectionState == BleConnectionState.CONNECTED) "手环已连通" else "手环未连接",
                        fontSize = 11.sp,
                        color = if (connectionState == BleConnectionState.CONNECTED) MiBandCyan else DarkTextTertiary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tab Switcher Row
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(tabs) { index, title ->
                        val isSelected = selectedTab == index
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) GoldDream.copy(alpha = 0.2f) else DarkSurfaceElevated)
                                .border(1.dp, if (isSelected) GoldDream else DarkBorder, RoundedCornerShape(10.dp))
                                .clickable { selectedTab = index }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) GoldDream else DarkTextSecondary
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                when (selectedTab) {
                    // --- Tab 0: 传感器测试 (PPG 心率 & 三轴动量) ---
                    0 -> {
                        // PPG Heart Rate Card
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
                                            text = "实时心率 (PPG)",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = DarkTextPrimary
                                        )
                                    }

                                    Text(
                                        text = if (metrics.isHrStreaming) "● 连续测定中 (1Hz)" else "常规间隔监测",
                                        fontSize = 11.sp,
                                        color = if (metrics.isHrStreaming) HeartRateRed else DarkTextTertiary
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
                                        text = if (metrics.isHrStreaming) "BPM / 实时脉搏" else "BPM / 最近一次",
                                        fontSize = 12.sp,
                                        color = DarkTextSecondary
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Button(
                                    onClick = {
                                        if (connectionState == BleConnectionState.CONNECTED) {
                                            if (!isHrToggling) {
                                                isHrToggling = true
                                                bleManager.setHeartRateStreamingMode(!metrics.isHrStreaming)
                                            }
                                        } else {
                                            Toast.makeText(context, "请先连接手环", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    enabled = !isHrToggling,
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (metrics.isHrStreaming) Color(0xFF334155) else HeartRateRed
                                    )
                                ) {
                                    Text(
                                        text = if (isHrToggling) "正在发送指令..." else if (metrics.isHrStreaming) "停止连续心率测定" else "开启连续心率测定",
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Accelerometer Card
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
                                            text = "加速度计",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = DarkTextPrimary
                                        )
                                    }

                                    val movementDesc = when {
                                        !metrics.isMotionStreaming -> "待机"
                                        metrics.actigraphyG < 0.020f -> "静止"
                                        metrics.actigraphyG < 0.080f -> "轻微微动"
                                        metrics.actigraphyG < 0.200f -> "肢体动作"
                                        else -> "大幅体动/翻身"
                                    }
                                    Text(
                                        text = movementDesc,
                                        fontSize = 11.sp,
                                        color = if (!metrics.isMotionStreaming) DarkTextTertiary else if (metrics.actigraphyG > 0.080f) HeartRateRed else MiBandCyan
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    Text(
                                        text = "%.3f g".format(metrics.actigraphyG),
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MiBandCyan
                                    )
                                    val streamStatus = if (metrics.rawSensorPacketsCount > 0) {
                                        "原始流 (${metrics.rawSensorPacketsCount}包)"
                                    } else if (metrics.isMotionStreaming) {
                                        "推流建立中..."
                                    } else {
                                        "待机"
                                    }
                                    Text(
                                        text = streamStatus,
                                        fontSize = 11.sp,
                                        color = when {
                                            metrics.rawSensorPacketsCount > 0 -> MiBandCyan
                                            metrics.isMotionStreaming -> Color(0xFFF59E0B)
                                            else -> DarkTextTertiary
                                        }
                                    )
                                }

                                if (metrics.rawSensorPacketsCount > 0) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "实时三轴: X:%+.0f  Y:%+.0f  Z:%+.0f".format(
                                            metrics.lastRawSampleX,
                                            metrics.lastRawSampleY,
                                            metrics.lastRawSampleZ
                                        ),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MiBandCyan.copy(alpha = 0.85f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Button(
                                    onClick = {
                                        if (connectionState == BleConnectionState.CONNECTED) {
                                            val isStreaming = metrics.isMotionStreaming
                                            bleManager.enableSensorNotifications(resetBaseline = isStreaming)
                                            Toast.makeText(
                                                context,
                                                if (isStreaming) "正在激活手环传感器推流并重置基准..." else "正在启动体动流检测...",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(context, "请先连接手环", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (metrics.isMotionStreaming) Color(0xFF334155) else MiBandCyan
                                    )
                                ) {
                                    Text(
                                        text = if (metrics.isMotionStreaming) "一键激活 / 重新校准体动流" else "启动体动检测",
                                        fontSize = 12.sp,
                                        color = if (metrics.isMotionStreaming) Color.White else Color.Black,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // --- Tab 1: 震动工坊 (模式选择、滑块与试震) ---
                    1 -> {
                        // Vibration Master Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("手环触梦震动开关", fontSize = 13.sp, color = DarkTextPrimary, fontWeight = FontWeight.SemiBold)
                            Switch(
                                checked = config.enableWristVibration,
                                onCheckedChange = {
                                    val updated = config.copy(enableWristVibration = it)
                                    config = updated
                                    onSaveConfig(updated)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = GoldDream, checkedTrackColor = GoldDream.copy(alpha = 0.3f))
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Pattern Selector Cards
                        Text("触梦震动节律 (可自定义/重命名)", fontSize = 12.sp, color = DarkTextTertiary)
                        Spacer(modifier = Modifier.height(6.dp))

                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(config.patterns) { _, p ->
                                val isSelected = p.id == config.activePatternId
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) GoldDream.copy(alpha = 0.2f) else DarkSurfaceElevated)
                                        .border(1.dp, if (isSelected) GoldDream else DarkBorder, RoundedCornerShape(10.dp))
                                        .clickable {
                                            val updated = config.copy(activePatternId = p.id)
                                            config = updated
                                            onSaveConfig(updated)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = p.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) GoldDream else DarkTextSecondary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Current Pattern Editor
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkSurfaceElevated)
                                .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Column {
                                OutlinedTextField(
                                    value = currentPattern.name,
                                    onValueChange = { newName ->
                                        updateCurrentPattern { it.copy(name = newName) }
                                    },
                                    label = { Text("模式名称", fontSize = 11.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = GoldDream,
                                        unfocusedBorderColor = DarkBorder,
                                        focusedTextColor = DarkTextPrimary,
                                        unfocusedTextColor = DarkTextPrimary
                                    ),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("脉冲循环次数", fontSize = 12.sp, color = DarkTextSecondary)
                                    Text("${currentPattern.repeatCount} 次", fontSize = 12.sp, color = GoldDream, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = currentPattern.repeatCount.toFloat(),
                                    onValueChange = { updateCurrentPattern { p -> p.copy(repeatCount = it.toInt()) } },
                                    valueRange = 1f..10f,
                                    steps = 8,
                                    colors = SliderDefaults.colors(thumbColor = GoldDream, activeTrackColor = GoldDream)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("单次脉冲时长", fontSize = 12.sp, color = DarkTextSecondary)
                                    Text("${currentPattern.pulseMs} ms", fontSize = 12.sp, color = MiBandCyan, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = currentPattern.pulseMs.toFloat(),
                                    onValueChange = { updateCurrentPattern { p -> p.copy(pulseMs = it.toInt()) } },
                                    valueRange = 100f..1000f,
                                    colors = SliderDefaults.colors(thumbColor = MiBandCyan, activeTrackColor = MiBandCyan)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("脉冲间隔休眠", fontSize = 12.sp, color = DarkTextSecondary)
                                    Text("${currentPattern.pauseMs} ms", fontSize = 12.sp, color = AlertPurple, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = currentPattern.pauseMs.toFloat(),
                                    onValueChange = { updateCurrentPattern { p -> p.copy(pauseMs = it.toInt()) } },
                                    valueRange = 400f..2000f,
                                    colors = SliderDefaults.colors(thumbColor = AlertPurple, activeTrackColor = AlertPurple)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("触梦震动总时长", fontSize = 12.sp, color = DarkTextSecondary)
                                    Text("${currentPattern.durationSeconds} 秒", fontSize = 12.sp, color = GoldDream, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = currentPattern.durationSeconds.toFloat(),
                                    onValueChange = { updateCurrentPattern { p -> p.copy(durationSeconds = it.toInt()) } },
                                    valueRange = 5f..60f,
                                    steps = 11,
                                    colors = SliderDefaults.colors(thumbColor = GoldDream, activeTrackColor = GoldDream)
                                )

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "说明：【单次脉冲时长】微调每次轻震的微观长短；【触梦震动总时长】控制触梦唤醒提醒持续的总秒数（可与音频耳语时长独立分别配置）。采用与 Gadgetbridge 对齐的实时警报通道，时序严格串行同步并保证手环固件复位安全间歇（≥500ms），设几次就精准物理震动几次。",
                                    fontSize = 10.sp,
                                    color = DarkTextTertiary,
                                    lineHeight = 14.sp
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            if (connectionState == BleConnectionState.CONNECTED) {
                                                if (isTestingVibration) {
                                                    bleManager.stopVibration()
                                                    isTestingVibration = false
                                                } else {
                                                    isTestingVibration = true
                                                    bleManager.triggerCustomVibration(currentPattern) {
                                                        isTestingVibration = false
                                                    }
                                                }
                                            } else {
                                                Toast.makeText(context, "请先连接手环", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(38.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isTestingVibration) HeartRateRed else GoldDream
                                        )
                                    ) {
                                        Text(
                                            text = if (isTestingVibration) "停止试震" else "在手环上试震",
                                            fontSize = 12.sp,
                                            color = if (isTestingVibration) Color.White else Color.Black,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val default = DreamCueConfig.defaultPatterns().firstOrNull { it.id == currentPattern.id }
                                            if (default != null) {
                                                updateCurrentPattern { default }
                                                Toast.makeText(context, "已恢复默认参数", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.height(38.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("恢复默认", fontSize = 12.sp, color = DarkTextSecondary)
                                    }
                                }
                            }
                        }
                    }

                    // --- Tab 2: 声音设置 (潜意识耳语、自定义音频) ---
                    2 -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("开启潜意识耳语播报", fontSize = 13.sp, color = DarkTextPrimary, fontWeight = FontWeight.SemiBold)
                                Text("进入REM期播放唤醒音频或清明梦暗示", fontSize = 11.sp, color = DarkTextTertiary)
                            }
                            Switch(
                                checked = config.enableAudioPlayback,
                                onCheckedChange = {
                                    val updated = config.copy(enableAudioPlayback = it)
                                    config = updated
                                    onSaveConfig(updated)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = GoldDream, checkedTrackColor = GoldDream.copy(alpha = 0.3f))
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("当前选用音频", fontSize = 11.sp, color = DarkTextTertiary)
                                        Text(
                                            text = config.customAudioName.ifBlank { "默认潜意识耳语" },
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = GoldDream
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            onPickAudioFile()
                                        },
                                        modifier = Modifier.height(34.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("导入新音频", fontSize = 11.sp, color = MiBandCyan)
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("耳语播放音量", fontSize = 12.sp, color = DarkTextSecondary)
                                    Text("${config.audioVolumePercent}%", fontSize = 12.sp, color = GoldDream, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = config.audioVolumePercent.toFloat(),
                                    onValueChange = {
                                        val updated = config.copy(audioVolumePercent = it.toInt())
                                        config = updated
                                        onSaveConfig(updated)
                                    },
                                    valueRange = 5f..100f,
                                    steps = 19,
                                    colors = SliderDefaults.colors(thumbColor = GoldDream, activeTrackColor = GoldDream)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("单次耳语时长", fontSize = 12.sp, color = DarkTextSecondary)
                                    Text("${config.audioDurationSeconds} 秒", fontSize = 12.sp, color = GoldDream, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = config.audioDurationSeconds.toFloat(),
                                    onValueChange = {
                                        val updated = config.copy(audioDurationSeconds = it.toInt())
                                        config = updated
                                        onSaveConfig(updated)
                                    },
                                    valueRange = 5f..30f,
                                    steps = 5,
                                    colors = SliderDefaults.colors(thumbColor = GoldDream, activeTrackColor = GoldDream)
                                )

                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "说明：若自定义音频时长短于设定时长，系统会自动无缝循环播放，直至达到设定总时长后平滑淡出。",
                                    fontSize = 10.sp,
                                    color = DarkTextTertiary,
                                    lineHeight = 14.sp
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Button(
                                    onClick = {
                                        if (isTestingAudio) {
                                            audioPlayer.stopAudio()
                                            isTestingAudio = false
                                        } else {
                                            isTestingAudio = true
                                            audioPlayer.playCueAudio(
                                                filePath = config.customAudioPath,
                                                durationSeconds = config.audioDurationSeconds,
                                                volumePercent = config.audioVolumePercent,
                                                onComplete = { isTestingAudio = false }
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(38.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isTestingAudio) HeartRateRed else AlertPurple
                                    )
                                ) {
                                    Text(
                                        text = if (isTestingAudio) "停止声音试听" else "试听潜意识音频",
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // --- Tab 3: 认证与协议设置 ---
                    3 -> {
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("2021 新认证协议", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DarkTextPrimary)
                                        Text("ECDH B-163 曲线与分块握手", fontSize = 11.sp, color = DarkTextTertiary)
                                    }

                                    Switch(
                                        checked = newProtocolEnabled,
                                        onCheckedChange = { enabled ->
                                            newProtocolEnabled = enabled
                                            onToggle2021Protocol(enabled)
                                            bleManager.use2021Protocol = enabled
                                            Toast.makeText(
                                                context,
                                                if (enabled) "已启用 2021 新认证协议 (适用于固件 v1.0.6+)" else "已切换为经典握手协议",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = GoldDream, checkedTrackColor = GoldDream.copy(alpha = 0.3f))
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "💡 重要提示：如果手环屏幕提示【请升级到新版本】，或认证握手报错【状态码 7】，说明手环固件已废弃旧版认证，请务必保持此项开启！",
                                    fontSize = 11.sp,
                                    color = GoldDream,
                                    lineHeight = 16.sp
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "当前绑定的设备 MAC: ${if (deviceInfo.macAddress.isNotBlank()) deviceInfo.macAddress else "未配置"}",
                                    fontSize = 11.sp,
                                    color = DarkTextSecondary
                                )
                                Text(
                                    text = "AuthKey 长度: ${if (deviceInfo.authKeyHex.length == 32) "32 字符 (16 字节有效)" else "未配置或格式不符"}",
                                    fontSize = 11.sp,
                                    color = if (deviceInfo.authKeyHex.length == 32) MiBandCyan else HeartRateRed
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveConfig(config)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = GoldDream)
            ) {
                Text("完成", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    )
}
