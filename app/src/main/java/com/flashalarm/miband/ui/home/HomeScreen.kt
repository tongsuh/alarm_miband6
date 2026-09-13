package com.flashalarm.miband.ui.home

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flashalarm.miband.FlashAlarmApp
import com.flashalarm.miband.R
import com.flashalarm.miband.domain.model.BleConnectionState
import com.flashalarm.miband.domain.model.VibrationCadenceType
import com.flashalarm.miband.service.SleepGuardService
import com.flashalarm.miband.ui.bedside.SleepModeActivity
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
import com.flashalarm.miband.ui.theme.PureBlack

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as FlashAlarmApp
    val bleManager = app.bleManager
    val prefs = app.userPreferencesRepository

    val connectionState by bleManager.connectionState.collectAsState()
    val deviceMetrics by bleManager.deviceMetrics.collectAsState()
    val deviceInfo by bleManager.deviceInfo.collectAsState()
    val cueConfig by prefs.cueConfig.collectAsState()
    val isServiceRunning by SleepGuardService.isServiceRunning.collectAsState()

    var showConfigDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .padding(bottom = 90.dp) // Room for floating capsule button
        ) {
            // App Title Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "FlashAlarm",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                    Text(
                        text = "小米手环 6 触梦伴侣",
                        fontSize = 13.sp,
                        color = DarkTextSecondary
                    )
                }

                // Settings icon button
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceElevated)
                        .clickable { showConfigDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_settings),
                        contentDescription = "Device Settings",
                        tint = DarkTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 1. Device Connection Card
            DeviceStatusCard(
                connectionState = connectionState,
                deviceInfo = deviceInfo,
                metrics = deviceMetrics,
                onConnectClick = {
                    val mac = prefs.getDeviceMac()
                    val key = prefs.getAuthKeyHex()
                    if (mac.isBlank() || key.isBlank()) {
                        showConfigDialog = true
                    } else {
                        bleManager.startScanAndConnect(mac)
                    }
                },
                onConfigureClick = { showConfigDialog = true }
            )

            Spacer(modifier = Modifier.height(18.dp))

            // 2. Lucid Dream Cueing Configuration Card
            DreamCueConfigCard(
                cueConfig = cueConfig,
                onConfigChange = { updated ->
                    prefs.updateCueConfig(updated)
                    app.remEngine.updateConfig(updated)
                },
                onTestVibration = {
                    if (connectionState == BleConnectionState.CONNECTED) {
                        bleManager.triggerCadenceVibration(cueConfig.cadenceType)
                        Toast.makeText(context, "已下发测试微震脉冲到手环", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "请先连接小米手环 6", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(18.dp))

            // 3. Multi-modal Algorithm Explainer Card
            AlgorithmOverviewCard()
        }

        // Bottom Persistent Floating Capsule Button: [ 🌙 开始手环睡眠守护 ]
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, PureBlack.copy(alpha = 0.95f), PureBlack)
                    )
                )
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Button(
                onClick = {
                    if (isServiceRunning) {
                        // Open Bedside Clock Screen directly
                        context.startActivity(Intent(context, SleepModeActivity::class.java))
                    } else {
                        // Start service
                        val serviceIntent = Intent(context, SleepGuardService::class.java).apply {
                            action = SleepGuardService.ACTION_START_GUARD
                        }
                        context.startForegroundService(serviceIntent)
                        context.startActivity(Intent(context, SleepModeActivity::class.java))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(29.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) Color(0xFFEF4444) else GoldDream
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_moon),
                        contentDescription = "Moon",
                        tint = if (isServiceRunning) Color.White else Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isServiceRunning) "已在守护中 · 点击进入床头屏保" else "🌙 开始手环睡眠守护",
                        color = if (isServiceRunning) Color.White else Color.Black,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Bluetooth MAC & AuthKey Config Dialog
    if (showConfigDialog) {
        DeviceConfigDialog(
            currentMac = prefs.getDeviceMac(),
            currentKey = prefs.getAuthKeyHex(),
            onDismiss = { showConfigDialog = false },
            onSave = { mac, key ->
                prefs.saveDeviceMac(mac)
                prefs.saveAuthKeyHex(key)
                bleManager.setTargetDevice("Mi Smart Band 6", mac, key)
                showConfigDialog = false
            }
        )
    }
}

@Composable
private fun DeviceStatusCard(
    connectionState: BleConnectionState,
    deviceInfo: com.flashalarm.miband.domain.model.BleDeviceInfo,
    metrics: com.flashalarm.miband.domain.model.BleDeviceMetrics,
    onConnectClick: () -> Unit,
    onConfigureClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkBorder, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (connectionState) {
                                    BleConnectionState.CONNECTED -> MiBandCyan
                                    BleConnectionState.CONNECTING, BleConnectionState.AUTHENTICATING -> GoldDream
                                    BleConnectionState.SCANNING -> AlertPurple
                                    else -> Color.Gray
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (connectionState) {
                            BleConnectionState.CONNECTED -> "已连接小米手环 6"
                            BleConnectionState.CONNECTING -> "正在建立连接..."
                            BleConnectionState.AUTHENTICATING -> "正在进行华米AES握手..."
                            BleConnectionState.SCANNING -> "正在扫描附近手环..."
                            BleConnectionState.ERROR -> "连接断开/认证失败"
                            else -> "手环未连接"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkTextPrimary
                    )
                }

                Text(
                    text = if (connectionState == BleConnectionState.CONNECTED) "已就绪" else "配置密钥",
                    fontSize = 12.sp,
                    color = MiBandCyan,
                    modifier = Modifier.clickable { onConfigureClick() }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Metrics readout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricItem(
                    icon = R.drawable.ic_heart,
                    iconTint = HeartRateRed,
                    label = "实时心率",
                    value = if (metrics.heartRateBpm > 0) "${metrics.heartRateBpm} bpm" else "--"
                )
                MetricItem(
                    icon = R.drawable.ic_moon,
                    iconTint = MiBandCyan,
                    label = "手腕动量",
                    value = if (metrics.actigraphyG > 0) "%.2fg".format(metrics.actigraphyG) else "静止"
                )
                MetricItem(
                    icon = R.drawable.ic_bluetooth,
                    iconTint = AlertPurple,
                    label = "MAC地址",
                    value = if (deviceInfo.macAddress.isNotBlank()) deviceInfo.macAddress.takeLast(8) else "未设定"
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (connectionState != BleConnectionState.CONNECTED) {
                Button(
                    onClick = onConnectClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkBorder)
                ) {
                    Text("连接手环", color = DarkTextPrimary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun MetricItem(
    icon: Int,
    iconTint: Color,
    label: String,
    value: String
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(label, fontSize = 11.sp, color = DarkTextTertiary)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DarkTextPrimary)
    }
}

@Composable
private fun DreamCueConfigCard(
    cueConfig: com.flashalarm.miband.domain.model.DreamCueConfig,
    onConfigChange: (com.flashalarm.miband.domain.model.DreamCueConfig) -> Unit,
    onTestVibration: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkBorder, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "黄金触梦提醒设置",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = DarkTextPrimary
            )
            Text(
                text = "命中做梦期时向手腕下发特定节奏微震，在梦中唤醒自知意识",
                fontSize = 12.sp,
                color = DarkTextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            // Cadence selector
            Text("微震节奏模式", fontSize = 13.sp, color = DarkTextTertiary)
            Spacer(modifier = Modifier.height(6.dp))

            VibrationCadenceType.entries.forEach { cadence ->
                val isSelected = cueConfig.cadenceType == cadence
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) GoldDream.copy(alpha = 0.15f) else DarkSurface)
                        .border(1.dp, if (isSelected) GoldDream else DarkBorder, RoundedCornerShape(10.dp))
                        .clickable { onConfigChange(cueConfig.copy(cadenceType = cadence)) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = cadence.displayName,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) GoldDream else DarkTextPrimary
                            )
                            Text(
                                text = cadence.patternDescription,
                                fontSize = 11.sp,
                                color = DarkTextTertiary
                            )
                        }
                        if (isSelected) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_star),
                                contentDescription = null,
                                tint = GoldDream,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Test vibration button
            Button(
                onClick = onTestVibration,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkBorder)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_vibrate),
                    contentDescription = null,
                    tint = GoldDream,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("试听/测试手腕微震触感", color = GoldDream, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Cooldown Interval
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("两次触梦最短冷却", fontSize = 13.sp, color = DarkTextPrimary)
                Text("${cueConfig.cooldownMinutes} 分钟", fontSize = 13.sp, color = GoldDream, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = cueConfig.cooldownMinutes.toFloat(),
                onValueChange = { onConfigChange(cueConfig.copy(cooldownMinutes = it.toInt())) },
                valueRange = 15f..45f,
                steps = 5,
                colors = SliderDefaults.colors(thumbColor = GoldDream, activeTrackColor = GoldDream)
            )

            // Sleep Onset Delay Window
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("入睡首周期深睡保护", fontSize = 13.sp, color = DarkTextPrimary)
                Text("${cueConfig.minSleepOnsetMinutes} 分钟", fontSize = 13.sp, color = GoldDream, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = cueConfig.minSleepOnsetMinutes.toFloat(),
                onValueChange = { onConfigChange(cueConfig.copy(minSleepOnsetMinutes = it.toInt())) },
                valueRange = 60f..100f,
                steps = 7,
                colors = SliderDefaults.colors(thumbColor = GoldDream, activeTrackColor = GoldDream)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Acoustic Breathing Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("手机麦克风呼吸交叉验证 (25%)", fontSize = 13.sp, color = DarkTextPrimary)
                    Text("若关闭或嘈杂，将自动平滑降级为纯手环双通道", fontSize = 11.sp, color = DarkTextTertiary)
                }
                Switch(
                    checked = cueConfig.enableAudioVerification,
                    onCheckedChange = { onConfigChange(cueConfig.copy(enableAudioVerification = it)) },
                    colors = SwitchDefaults.colors(checkedThumbColor = GoldDream, checkedTrackColor = GoldDream.copy(alpha = 0.3f))
                )
            }
        }
    }
}

@Composable
private fun AlgorithmOverviewCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkBorder, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "双保险多模态分层决策逻辑",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = DarkTextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            AlgorithmBullet(
                title = "75% 手环生理骨架",
                desc = "骨骼肌瘫痪（翻身动作一票否决）+ 自主神经风暴（心率突增15%+与变异度剧烈跳变）"
            )
            AlgorithmBullet(
                title = "25% 手机声学印证",
                desc = "后半夜呼吸变浅变乱双重印证，置信度达95%+击发微震"
            )
            AlgorithmBullet(
                title = "睡眠周期硬约束",
                desc = "入睡70-90分钟后及凌晨高发期开放，彻底杜绝前半夜深睡误击发"
            )
        }
    }
}

@Composable
private fun AlgorithmBullet(title: String, desc: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("• $title", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MiBandCyan)
        Text(desc, fontSize = 12.sp, color = DarkTextSecondary, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun DeviceConfigDialog(
    currentMac: String,
    currentKey: String,
    onDismiss: () -> Unit,
    onSave: (mac: String, authKey: String) -> Unit
) {
    var macText by remember { mutableStateOf(currentMac) }
    var keyText by remember { mutableStateOf(currentKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceElevated,
        title = { Text("小米手环 6 握手配置", color = DarkTextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "小米手环 6 采用标准华米 AES-128 协议，需 16 字节 Auth Key（32位十六进制字符，如通过 Zepp/Gadgetbridge 获取）完成随机数加密握手。",
                    fontSize = 12.sp,
                    color = DarkTextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = macText,
                    onValueChange = { macText = it },
                    label = { Text("手环 MAC 地址 (例如 E4:24:D7:xx:xx:xx)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DarkTextPrimary,
                        unfocusedTextColor = DarkTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("16 字节 Auth Key (32 位 Hex)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DarkTextPrimary,
                        unfocusedTextColor = DarkTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(macText, keyText) },
                colors = ButtonDefaults.buttonColors(containerColor = GoldDream)
            ) {
                Text("保存设置", color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = DarkTextTertiary)
            }
        }
    )
}
