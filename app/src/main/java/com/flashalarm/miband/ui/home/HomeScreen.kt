package com.flashalarm.miband.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.core.content.ContextCompat
import com.flashalarm.miband.FlashAlarmApp
import com.flashalarm.miband.R
import com.flashalarm.miband.domain.model.BleConnectionState
import com.flashalarm.miband.domain.model.DreamCueConfig
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
    val audioPlayer = app.audioPlayer

    val connectionState by bleManager.connectionState.collectAsState()
    val deviceMetrics by bleManager.deviceMetrics.collectAsState()
    val deviceInfo by bleManager.deviceInfo.collectAsState()
    val authStatusDetail by bleManager.authStatusDetail.collectAsState()
    val cueConfig by prefs.cueConfig.collectAsState()
    val isServiceRunning by SleepGuardService.isServiceRunning.collectAsState()

    var showPairingDialog by remember { mutableStateOf(false) }
    var showUnifiedSettings by remember { mutableStateOf(false) }
    var showNotConnectedWarning by remember { mutableStateOf(false) }
    var isTestingAudio by remember { mutableStateOf(false) }
    val use2021Protocol by prefs.use2021Protocol.collectAsState()

    // Audio file picker launcher (copies file to app private sandbox immediately)
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val saved = audioPlayer.saveCustomAudioToSandbox(uri)
            if (saved != null) {
                val (filePath, displayName) = saved
                val updated = cueConfig.copy(
                    customAudioPath = filePath,
                    customAudioName = displayName,
                    enableAudioPlayback = true
                )
                prefs.updateCueConfig(updated)
                app.remEngine.updateConfig(updated)
                Toast.makeText(context, "已成功导入音频: $displayName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "导入音频文件失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                .padding(bottom = 96.dp)
        ) {
            // App Title Header & Action Buttons
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

                // Unified Settings button (Heart rate, Actigraphy, Vibration studio, Sound & 2021 Protocol)
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceElevated)
                        .border(1.dp, DarkBorder, CircleShape)
                        .clickable { showUnifiedSettings = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_settings),
                        contentDescription = "System & Peripheral Settings",
                        tint = DarkTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 1. Device Connection Status Card
            DeviceStatusCard(
                connectionState = connectionState,
                deviceInfo = deviceInfo,
                metrics = deviceMetrics,
                authStatusDetail = authStatusDetail,
                onConnectClick = {
                    val mac = prefs.getDeviceMac()
                    val key = prefs.getAuthKeyHex()
                    if (mac.isBlank() || key.isBlank()) {
                        showPairingDialog = true
                    } else {
                        bleManager.startScanAndConnect(mac)
                    }
                },
                onConfigureClick = { showPairingDialog = true },
                onDisconnectClick = { bleManager.disconnect() }
            )

            Spacer(modifier = Modifier.height(18.dp))

            // 2. Lucid Dream Cueing Configuration Card (Dual Channels & Sliders)
            DreamCueConfigCard(
                cueConfig = cueConfig,
                connectionState = connectionState,
                isTestingAudio = isTestingAudio,
                onConfigChange = { updated ->
                    prefs.updateCueConfig(updated)
                    app.remEngine.updateConfig(updated)
                },
                onOpenVibrationStudio = { showUnifiedSettings = true },
                onPickAudioFile = { audioPickerLauncher.launch("audio/*") },
                onToggleAudioTest = {
                    if (isTestingAudio) {
                        audioPlayer.stopAudio()
                        isTestingAudio = false
                    } else {
                        isTestingAudio = true
                        audioPlayer.playCueAudio(
                            filePath = cueConfig.customAudioPath,
                            durationSeconds = cueConfig.audioDurationSeconds,
                            volumePercent = cueConfig.audioVolumePercent,
                            onComplete = { isTestingAudio = false }
                        )
                    }
                },
                onQuickTestVibration = {
                    if (connectionState == BleConnectionState.CONNECTED) {
                        bleManager.triggerCustomVibration(cueConfig.getActivePattern())
                        Toast.makeText(context, "手环正在试震【${cueConfig.getActivePattern().name}】", Toast.LENGTH_SHORT).show()
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
                        context.startActivity(Intent(context, SleepModeActivity::class.java))
                    } else {
                        // Safe check: band MUST be connected before starting sleep guard
                        if (connectionState != BleConnectionState.CONNECTED) {
                            showNotConnectedWarning = true
                            return@Button
                        }
                        try {
                            val serviceIntent = Intent(context, SleepGuardService::class.java).apply {
                                action = SleepGuardService.ACTION_START_GUARD
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                            Toast.makeText(context, "手环睡眠守护已在后台开启", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            android.util.Log.e("HomeScreen", "Failed starting SleepGuardService", e)
                            Toast.makeText(context, "无法启动守护服务: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
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
                        text = if (isServiceRunning) "守护进行中 · 点击进入床头屏保" else "🌙 开始手环睡眠守护",
                        color = if (isServiceRunning) Color.White else Color.Black,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Dialogs
    if (showPairingDialog) {
        DevicePairingDialog(
            bleManager = bleManager,
            initialMac = prefs.getDeviceMac(),
            initialAuthKey = prefs.getAuthKeyHex(),
            onSaveAndConnect = { mac, authKey ->
                prefs.saveDeviceMac(mac)
                prefs.saveAuthKeyHex(authKey)
                bleManager.setTargetDevice("Mi Smart Band 6", mac, authKey, prefs.getUse2021Protocol())
                bleManager.startScanAndConnect(mac)
                showPairingDialog = false
            },
            onDismiss = { showPairingDialog = false }
        )
    }

    if (showUnifiedSettings) {
        UnifiedSettingsDialog(
            bleManager = bleManager,
            connectionState = connectionState,
            initialConfig = cueConfig,
            use2021Protocol = use2021Protocol,
            onSaveConfig = { updated ->
                prefs.updateCueConfig(updated)
                app.remEngine.updateConfig(updated)
            },
            onToggle2021Protocol = { enabled ->
                prefs.setUse2021Protocol(enabled)
            },
            onPickAudioFile = { audioPickerLauncher.launch("audio/*") },
            onDismiss = { showUnifiedSettings = false }
        )
    }

    if (showNotConnectedWarning) {
        AlertDialog(
            onDismissRequest = { showNotConnectedWarning = false },
            title = {
                Text("手环尚未连接", fontWeight = FontWeight.Bold, color = DarkTextPrimary)
            },
            text = {
                Text(
                    text = "手环睡眠守护需要实时采集手环的实时心率与腕部体动数据。\n\n请先在上方【设备连接状态】卡片点击【连接手环】并完成认证，待手环显示“已连接小米手环 6”后再启动守护。",
                    color = DarkTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNotConnectedWarning = false
                        val mac = prefs.getDeviceMac()
                        val key = prefs.getAuthKeyHex()
                        if (mac.isBlank() || key.isBlank()) {
                            showPairingDialog = true
                        } else {
                            bleManager.startScanAndConnect(mac)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MiBandCyan)
                ) {
                    Text("去连接手环", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showNotConnectedWarning = false }) {
                    Text("我知道了", color = DarkTextSecondary)
                }
            },
            containerColor = DarkSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun DeviceStatusCard(
    connectionState: BleConnectionState,
    deviceInfo: com.flashalarm.miband.domain.model.BleDeviceInfo,
    metrics: com.flashalarm.miband.domain.model.BleDeviceMetrics,
    authStatusDetail: String = "",
    onConnectClick: () -> Unit,
    onConfigureClick: () -> Unit,
    onDisconnectClick: () -> Unit
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
                            BleConnectionState.AUTHENTICATING -> "正在进行Huami认证..."
                            BleConnectionState.SCANNING -> "正在扫描手环..."
                            BleConnectionState.ERROR -> "连接断开/认证失败"
                            else -> "手环未连接"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkTextPrimary
                    )
                }

                Text(
                    text = if (connectionState == BleConnectionState.CONNECTED) "已就绪" else "待连接",
                    fontSize = 12.sp,
                    color = if (connectionState == BleConnectionState.CONNECTED) MiBandCyan else DarkTextSecondary
                )
            }

            if (authStatusDetail.isNotBlank() && connectionState != BleConnectionState.CONNECTED) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(PureBlack)
                        .border(
                            1.dp,
                            if (connectionState == BleConnectionState.ERROR) HeartRateRed.copy(alpha = 0.5f) else GoldDream.copy(alpha = 0.3f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "⚡ 握手诊断: $authStatusDetail",
                        fontSize = 11.sp,
                        color = if (connectionState == BleConnectionState.ERROR) HeartRateRed else GoldDream,
                        maxLines = 2
                    )
                }
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
                    value = if (metrics.actigraphyG > 0) "%.3fg".format(metrics.actigraphyG) else "静止"
                )
                MetricItem(
                    icon = R.drawable.ic_bluetooth,
                    iconTint = AlertPurple,
                    label = "手环MAC",
                    value = if (deviceInfo.macAddress.isNotBlank()) deviceInfo.macAddress.takeLast(8) else "未配置"
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (connectionState != BleConnectionState.CONNECTED) {
                    Button(
                        onClick = onConnectClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MiBandCyan)
                    ) {
                        Text("连接手环", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = onDisconnectClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("断开连接", color = DarkTextSecondary, fontSize = 13.sp)
                    }
                }

                OutlinedButton(
                    onClick = onConfigureClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("扫描/密钥设置", color = GoldDream, fontSize = 13.sp)
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
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DarkTextPrimary)
    }
}

@Composable
private fun DreamCueConfigCard(
    cueConfig: DreamCueConfig,
    connectionState: BleConnectionState,
    isTestingAudio: Boolean,
    onConfigChange: (DreamCueConfig) -> Unit,
    onOpenVibrationStudio: () -> Unit,
    onPickAudioFile: () -> Unit,
    onToggleAudioTest: () -> Unit,
    onQuickTestVibration: () -> Unit
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
                text = "黄金触梦提醒与通道定制",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = DarkTextPrimary
            )
            Text(
                text = "手环震动与手机音频双通道可单独或组合开启，最长可调至5分钟",
                fontSize = 12.sp,
                color = DarkTextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            // --- Channel 1: Wrist Motor Vibration ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_vibrate),
                                contentDescription = null,
                                tint = GoldDream,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("手环腕部脉冲微震", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DarkTextPrimary)
                        }

                        Switch(
                            checked = cueConfig.enableWristVibration,
                            onCheckedChange = { onConfigChange(cueConfig.copy(enableWristVibration = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = GoldDream, checkedTrackColor = GoldDream.copy(alpha = 0.3f))
                        )
                    }

                    if (cueConfig.enableWristVibration) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val activePattern = cueConfig.getActivePattern()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "当前模式: ${activePattern.name}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = GoldDream
                                )
                                Text(
                                    text = "强度 ${activePattern.startIntensityPercent}%~${activePattern.endIntensityPercent}% | 时长 ${activePattern.durationSeconds}s",
                                    fontSize = 11.sp,
                                    color = DarkTextTertiary
                                )
                            }

                            Text(
                                text = "定制/重命名 >",
                                fontSize = 12.sp,
                                color = MiBandCyan,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable { onOpenVibrationStudio() }
                                    .padding(4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = onQuickTestVibration,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("手环试震一下", fontSize = 12.sp, color = GoldDream)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Channel 2: Phone Audio Playback ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_music),
                                contentDescription = null,
                                tint = AlertPurple,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("手机潜意识语音/音频", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DarkTextPrimary)
                        }

                        Switch(
                            checked = cueConfig.enableAudioPlayback,
                            onCheckedChange = { onConfigChange(cueConfig.copy(enableAudioPlayback = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = AlertPurple, checkedTrackColor = AlertPurple.copy(alpha = 0.3f))
                        )
                    }

                    if (cueConfig.enableAudioPlayback) {
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = cueConfig.customAudioName,
                                fontSize = 12.sp,
                                color = DarkTextPrimary,
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                text = "更换本地音频",
                                fontSize = 12.sp,
                                color = AlertPurple,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable { onPickAudioFile() }
                                    .padding(4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Audio duration slider (5s - 300s / 5 minutes)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("音频播放时长", fontSize = 12.sp, color = DarkTextSecondary)
                            val durText = if (cueConfig.audioDurationSeconds >= 60) {
                                "${cueConfig.audioDurationSeconds / 60}分${cueConfig.audioDurationSeconds % 60}秒"
                            } else {
                                "${cueConfig.audioDurationSeconds}秒"
                            }
                            Text(durText, fontSize = 12.sp, color = AlertPurple, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = cueConfig.audioDurationSeconds.toFloat(),
                            onValueChange = { onConfigChange(cueConfig.copy(audioDurationSeconds = it.toInt())) },
                            valueRange = 5f..300f,
                            colors = SliderDefaults.colors(thumbColor = AlertPurple, activeTrackColor = AlertPurple)
                        )

                        // Audio volume slider (10% - 100%)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("音频播放音量", fontSize = 12.sp, color = DarkTextSecondary)
                            Text("${cueConfig.audioVolumePercent}%", fontSize = 12.sp, color = AlertPurple, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = cueConfig.audioVolumePercent.toFloat(),
                            onValueChange = { onConfigChange(cueConfig.copy(audioVolumePercent = it.toInt())) },
                            valueRange = 10f..100f,
                            colors = SliderDefaults.colors(thumbColor = AlertPurple, activeTrackColor = AlertPurple)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        OutlinedButton(
                            onClick = onToggleAudioTest,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (isTestingAudio) "停止试听" else "试听潜意识音频",
                                fontSize = 12.sp,
                                color = AlertPurple
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Section 3: Sleep Segmentation & Relative Timing ---
            Text("睡眠分段与相对时序", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DarkTextPrimary)
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("入睡后深睡保护期时长", fontSize = 12.sp, color = DarkTextSecondary)
                Text("%.1f 小时".format(cueConfig.sleepOnsetProtectionHours), fontSize = 12.sp, color = GoldDream, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = cueConfig.sleepOnsetProtectionHours,
                onValueChange = { onConfigChange(cueConfig.copy(sleepOnsetProtectionHours = (it * 2).toInt() / 2.0f)) },
                valueRange = 1.0f..4.5f,
                steps = 6,
                colors = SliderDefaults.colors(thumbColor = GoldDream, activeTrackColor = GoldDream)
            )
            Text(
                text = "💡 入睡识别后前 %.1f 小时保持静默，保护深度睡眠；保护期过后触梦雷达全程开启，直至手动结束。".format(cueConfig.sleepOnsetProtectionHours),
                fontSize = 11.sp,
                color = DarkTextTertiary,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            // Cooldown Interval
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("两次触梦最短冷却间隔", fontSize = 12.sp, color = DarkTextSecondary)
                Text("${cueConfig.cooldownMinutes} 分钟", fontSize = 12.sp, color = GoldDream, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = cueConfig.cooldownMinutes.toFloat(),
                onValueChange = { onConfigChange(cueConfig.copy(cooldownMinutes = it.toInt())) },
                valueRange = 10f..60f,
                steps = 9,
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
                    Text("结合后半夜呼吸变浅变乱双重印证", fontSize = 11.sp, color = DarkTextTertiary)
                }
                Switch(
                    checked = cueConfig.enableAudioVerification,
                    onCheckedChange = { onConfigChange(cueConfig.copy(enableAudioVerification = it)) },
                    colors = SwitchDefaults.colors(checkedThumbColor = GoldDream, checkedTrackColor = GoldDream.copy(alpha = 0.3f))
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Section 4: Detection Engine Selection ---
            Text("核心做梦期判决引擎", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DarkTextPrimary)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Option A: ML Model
                val isMlSelected = cueConfig.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.ML_MODEL
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isMlSelected) GoldDream.copy(alpha = 0.15f) else DarkSurface)
                        .border(1.dp, if (isMlSelected) GoldDream else DarkBorder, RoundedCornerShape(10.dp))
                        .clickable { onConfigChange(cueConfig.copy(engineMode = com.flashalarm.miband.domain.model.RemEngineMode.ML_MODEL)) }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "🤖 AI 决策树模型",
                            fontSize = 12.sp,
                            fontWeight = if (isMlSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isMlSelected) GoldDream else DarkTextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "5分钟延时 · PhysioNet",
                            fontSize = 10.sp,
                            color = if (isMlSelected) GoldDream.copy(alpha = 0.8f) else DarkTextTertiary
                        )
                    }
                }

                // Option B: Rule Based
                val isRuleSelected = cueConfig.engineMode == com.flashalarm.miband.domain.model.RemEngineMode.RULE_BASED
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isRuleSelected) MiBandCyan.copy(alpha = 0.15f) else DarkSurface)
                        .border(1.dp, if (isRuleSelected) MiBandCyan else DarkBorder, RoundedCornerShape(10.dp))
                        .clickable { onConfigChange(cueConfig.copy(engineMode = com.flashalarm.miband.domain.model.RemEngineMode.RULE_BASED)) }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "⚙️ 生理规则引擎",
                            fontSize = 12.sp,
                            fontWeight = if (isRuleSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isRuleSelected) MiBandCyan else DarkTextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "自适应心率突增与离散",
                            fontSize = 10.sp,
                            color = if (isRuleSelected) MiBandCyan.copy(alpha = 0.8f) else DarkTextTertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlgorithmOverviewCard() {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "ℹ️", fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "触梦分期机制说明",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkTextPrimary
                    )
                }
                Text(
                    text = if (expanded) "收起 ▲" else "展开 ▼",
                    fontSize = 11.sp,
                    color = DarkTextTertiary
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    AlgorithmBullet(
                        title = "Cole-Kripke 入睡状态机",
                        desc = "自动跟踪入睡连续静止与心率沉降，相对锁定深睡保护期与做梦期"
                    )
                    AlgorithmBullet(
                        title = "手环生理多模态骨架",
                        desc = "骨骼肌瘫痪（翻身动作一票否决）与自主神经风暴（心率突增与HRV离散跳变）"
                    )
                    AlgorithmBullet(
                        title = "声学印证与精准触达",
                        desc = "夜间呼吸不规则度交叉验证，置信度达标后下发腕部微震或潜意识耳语"
                    )
                    AlgorithmBullet(
                        title = "AI 决策树与规则双擎可选",
                        desc = "支持基于临床脑电金标准训练的 5 分钟延时 AI 决策树，或自适应生理规则引擎自由切换"
                    )
                }
            }
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
