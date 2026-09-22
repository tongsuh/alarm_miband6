package com.flashalarm.miband.ui.home

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flashalarm.miband.R
import com.flashalarm.miband.data.ble.EcgBleManager
import com.flashalarm.miband.domain.model.BleConnectionState
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
fun EcgPairingDialog(
    ecgBleManager: EcgBleManager,
    initialMac: String,
    onSaveAndConnect: (mac: String, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    val isScanning by ecgBleManager.isScanning.collectAsState()
    val discoveredDevices by ecgBleManager.discoveredDevices.collectAsState()
    val connectionState by ecgBleManager.connectionState.collectAsState()

    var selectedMac by remember { mutableStateOf(initialMac) }
    var selectedName by remember { mutableStateOf("FlashAlarm-ECG") }

    DisposableEffect(Unit) {
        ecgBleManager.startScan()
        onDispose {
            ecgBleManager.stopScan()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
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
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "连接 ESP32-C3 心电",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                }

                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = HeartRateRed
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "请确保 ESP32-C3 (AD8232) 已通电。设备广播标准心率服务 (0x180D)，点击下方列表即可一键配对。",
                    fontSize = 12.sp,
                    color = DarkTextSecondary,
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Discovered Devices Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "发现的心电设备 (${discoveredDevices.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkTextPrimary
                    )
                    TextButton(
                        onClick = {
                            if (isScanning) ecgBleManager.stopScan() else ecgBleManager.startScan()
                        }
                    ) {
                        Text(
                            text = if (isScanning) "停止扫描" else "重新扫描",
                            fontSize = 12.sp,
                            color = HeartRateRed
                        )
                    }
                }

                // Device List
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceElevated)
                        .border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
                        .padding(4.dp)
                ) {
                    if (discoveredDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isScanning) "正在搜索附近心电设备..." else "未找到设备，请点击重新扫描",
                                fontSize = 12.sp,
                                color = DarkTextTertiary
                            )
                        }
                    } else {
                        LazyColumn {
                            items(discoveredDevices) { device ->
                                val isSelected = device.address.equals(selectedMac, ignoreCase = true)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) HeartRateRed.copy(alpha = 0.15f) else Color.Transparent)
                                        .clickable {
                                            selectedMac = device.address
                                            selectedName = device.name
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = device.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isSelected) HeartRateRed else DarkTextPrimary
                                        )
                                        Text(
                                            text = device.address,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = DarkTextSecondary
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${device.rssi} dBm",
                                            fontSize = 10.sp,
                                            color = DarkTextTertiary
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(HeartRateRed)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Manual MAC Input
                OutlinedTextField(
                    value = selectedMac,
                    onValueChange = { selectedMac = it.trim() },
                    label = { Text("MAC 地址", fontSize = 11.sp) },
                    placeholder = { Text("XX:XX:XX:XX:XX:XX", fontSize = 11.sp, color = DarkTextTertiary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DarkTextPrimary
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HeartRateRed,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = HeartRateRed,
                        unfocusedLabelColor = DarkTextSecondary,
                        cursorColor = HeartRateRed
                    )
                )

                // Current Connection State info
                if (connectionState != BleConnectionState.DISCONNECTED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "当前状态: ${connectionState.displayName}",
                        fontSize = 11.sp,
                        color = if (connectionState == BleConnectionState.CONNECTED) MiBandCyan else GoldDream
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedMac.isNotBlank()) {
                        onSaveAndConnect(selectedMac, selectedName)
                        onDismiss()
                    }
                },
                enabled = selectedMac.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HeartRateRed,
                    disabledContainerColor = Color(0xFF334155)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("保存并连接", color = Color.White, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = DarkTextSecondary, fontSize = 13.sp)
            }
        }
    )
}
