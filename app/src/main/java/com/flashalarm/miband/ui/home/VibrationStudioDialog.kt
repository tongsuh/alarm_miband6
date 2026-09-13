package com.flashalarm.miband.ui.home

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.flashalarm.miband.ui.theme.MiBandCyan

@Composable
fun VibrationStudioDialog(
    bleManager: MiBandBleManager,
    connectionState: BleConnectionState,
    initialConfig: DreamCueConfig,
    onSaveConfig: (DreamCueConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var patterns by remember { mutableStateOf(initialConfig.patterns) }
    var selectedPatternId by remember { mutableStateOf(initialConfig.activePatternId) }
    var isTestingVibration by remember { mutableStateOf(false) }

    val currentPattern = patterns.firstOrNull { it.id == selectedPatternId } ?: patterns.first()

    DisposableEffect(Unit) {
        onDispose {
            bleManager.stopVibration()
        }
    }

    fun updateCurrentPattern(transform: (CustomizableVibrationPattern) -> CustomizableVibrationPattern) {
        patterns = patterns.map {
            if (it.id == selectedPatternId) transform(it) else it
        }
    }

    AlertDialog(
        onDismissRequest = {
            bleManager.stopVibration()
            onDismiss()
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Text(
                    text = "触梦震动工坊",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextPrimary
                )
                Text(
                    text = "PWM微积分脉冲调制，支持滑块强度与时长高度定制",
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
                // 1. Pattern Switcher Tabs
                Text(
                    text = "选择触梦微震模式",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkTextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    patterns.forEach { p ->
                        val isSelected = p.id == selectedPatternId
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) GoldDream.copy(alpha = 0.2f) else DarkSurfaceElevated)
                                .border(1.dp, if (isSelected) GoldDream else DarkBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedPatternId = p.id
                                    bleManager.stopVibration()
                                    isTestingVibration = false
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = p.type.displayName.take(4),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) GoldDream else DarkTextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2. Pattern Rename
                OutlinedTextField(
                    value = currentPattern.name,
                    onValueChange = { newName ->
                        updateCurrentPattern { it.copy(name = newName) }
                    },
                    label = { Text("模式名称 (可自定义)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GoldDream,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = DarkTextPrimary,
                        unfocusedTextColor = DarkTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 3. Start Intensity Slider (10% - 100%)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "起始强度 (PWM占空比)", fontSize = 12.sp, color = DarkTextSecondary)
                    Text(text = "${currentPattern.startIntensityPercent}%", fontSize = 12.sp, color = GoldDream, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentPattern.startIntensityPercent.toFloat(),
                    onValueChange = { updateCurrentPattern { p -> p.copy(startIntensityPercent = it.toInt()) } },
                    valueRange = 10f..100f,
                    colors = SliderDefaults.colors(thumbColor = GoldDream, activeTrackColor = GoldDream)
                )

                // 4. End Intensity Slider (10% - 100%)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "结束强度 (渐变目标)", fontSize = 12.sp, color = DarkTextSecondary)
                    Text(text = "${currentPattern.endIntensityPercent}%", fontSize = 12.sp, color = GoldDream, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentPattern.endIntensityPercent.toFloat(),
                    onValueChange = { updateCurrentPattern { p -> p.copy(endIntensityPercent = it.toInt()) } },
                    valueRange = 10f..100f,
                    colors = SliderDefaults.colors(thumbColor = GoldDream, activeTrackColor = GoldDream)
                )

                // 5. Pulse Duration Slider (50ms - 500ms)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "脉冲微震持续", fontSize = 12.sp, color = DarkTextSecondary)
                    Text(text = "${currentPattern.pulseMs} ms", fontSize = 12.sp, color = MiBandCyan, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentPattern.pulseMs.toFloat(),
                    onValueChange = { updateCurrentPattern { p -> p.copy(pulseMs = it.toInt()) } },
                    valueRange = 50f..500f,
                    colors = SliderDefaults.colors(thumbColor = MiBandCyan, activeTrackColor = MiBandCyan)
                )

                // 6. Pause Duration Slider (50ms - 1000ms)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "脉冲停歇间隔", fontSize = 12.sp, color = DarkTextSecondary)
                    Text(text = "${currentPattern.pauseMs} ms", fontSize = 12.sp, color = MiBandCyan, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentPattern.pauseMs.toFloat(),
                    onValueChange = { updateCurrentPattern { p -> p.copy(pauseMs = it.toInt()) } },
                    valueRange = 50f..1000f,
                    colors = SliderDefaults.colors(thumbColor = MiBandCyan, activeTrackColor = MiBandCyan)
                )

                // 7. Total Vibration Duration (5s - 300s / 5 minutes)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "总震动时长", fontSize = 12.sp, color = DarkTextSecondary)
                    val minText = if (currentPattern.durationSeconds >= 60) {
                        "${currentPattern.durationSeconds / 60}分${currentPattern.durationSeconds % 60}秒"
                    } else {
                        "${currentPattern.durationSeconds}秒"
                    }
                    Text(text = minText, fontSize = 12.sp, color = AlertPurple, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentPattern.durationSeconds.toFloat(),
                    onValueChange = { updateCurrentPattern { p -> p.copy(durationSeconds = it.toInt()) } },
                    valueRange = 5f..300f,
                    colors = SliderDefaults.colors(thumbColor = AlertPurple, activeTrackColor = AlertPurple)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Action row: Live Test & Reset Default
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Reset to default button
                    OutlinedButton(
                        onClick = {
                            val defaultP = DreamCueConfig.getDefaultPattern(currentPattern.id)
                            updateCurrentPattern { defaultP }
                            Toast.makeText(context, "已恢复【${defaultP.name}】官方默认参数", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("恢复默认", fontSize = 12.sp, color = DarkTextSecondary)
                    }

                    // Test vibration on band button
                    Button(
                        onClick = {
                            if (connectionState != BleConnectionState.CONNECTED) {
                                Toast.makeText(context, "请先连接手环", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (isTestingVibration) {
                                bleManager.stopVibration()
                                isTestingVibration = false
                            } else {
                                isTestingVibration = true
                                bleManager.triggerCustomVibration(currentPattern) {
                                    isTestingVibration = false
                                }
                                Toast.makeText(context, "手环正在试震...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTestingVibration) AlertPurple else MiBandCyan
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isTestingVibration) "停止试震" else "手环试震",
                            fontSize = 12.sp,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    bleManager.stopVibration()
                    val updatedConfig = initialConfig.copy(
                        activePatternId = selectedPatternId,
                        patterns = patterns
                    )
                    onSaveConfig(updatedConfig)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = GoldDream),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("应用并保存", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    bleManager.stopVibration()
                    onDismiss()
                }
            ) {
                Text("取消", color = DarkTextSecondary)
            }
        }
    )
}
