package com.flashalarm.miband.ui.home

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flashalarm.miband.R
import com.flashalarm.miband.data.ble.DiscoveredBleDevice
import com.flashalarm.miband.data.ble.MiBandBleManager
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
fun DevicePairingDialog(
    bleManager: MiBandBleManager,
    initialMac: String,
    initialAuthKey: String,
    onSaveAndConnect: (mac: String, authKey: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isScanning by bleManager.isScanning.collectAsState()
    val discoveredDevices by bleManager.discoveredDevices.collectAsState()
    val connectionState by bleManager.connectionState.collectAsState()
    val authStatusDetail by bleManager.authStatusDetail.collectAsState()

    var selectedMac by remember { mutableStateOf(initialMac) }
    var selectedName by remember { mutableStateOf("Mi Smart Band 6") }
    var authKeyInput by remember { mutableStateOf(initialAuthKey) }

    // Start scanning on entry, stop on exit
    DisposableEffect(Unit) {
        bleManager.startBleScan()
        onDispose {
            bleManager.stopBleScan()
        }
    }

    AlertDialog(
        onDismissRequest = {
            bleManager.stopBleScan()
            onDismiss()
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "蓝牙手环配对连接",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                    Text(
                        text = "选择附近的小米手环 6 并输入 AuthKey",
                        fontSize = 12.sp,
                        color = DarkTextTertiary
                    )
                }

                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MiBandCyan
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                // Scan header & control
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "附近发现的设备 (${discoveredDevices.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkTextSecondary
                    )

                    Text(
                        text = if (isScanning) "停止扫描" else "重新扫描",
                        fontSize = 12.sp,
                        color = MiBandCyan,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                if (isScanning) bleManager.stopBleScan() else bleManager.startBleScan()
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Discovered Device List
                if (discoveredDevices.isEmpty() && isScanning) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "正在扫描附近的蓝牙设备...",
                            fontSize = 12.sp,
                            color = DarkTextTertiary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurfaceElevated)
                            .border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
                    ) {
                        items(discoveredDevices) { device ->
                            val isSelected = selectedMac.equals(device.address, ignoreCase = true)
                            val isMiBand = device.name.contains("Band", ignoreCase = true) || device.name.contains("Mi", ignoreCase = true)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedMac = device.address
                                        selectedName = device.name
                                    }
                                    .background(if (isSelected) MiBandCyan.copy(alpha = 0.15f) else Color.Transparent)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = device.name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isMiBand) MiBandCyan else DarkTextPrimary
                                        )
                                        if (isMiBand) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "推荐",
                                                fontSize = 10.sp,
                                                color = GoldDream,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Text(
                                        text = device.address,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = DarkTextTertiary
                                    )
                                }

                                Text(
                                    text = "${device.rssi} dBm",
                                    fontSize = 11.sp,
                                    color = DarkTextTertiary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // MAC input
                OutlinedTextField(
                    value = selectedMac,
                    onValueChange = { selectedMac = it.uppercase() },
                    label = { Text("手环 MAC 地址") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MiBandCyan,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = DarkTextPrimary,
                        unfocusedTextColor = DarkTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Auth Key input
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "16字节 AuthKey (32位十六进制)",
                            fontSize = 12.sp,
                            color = DarkTextSecondary
                        )

                        Text(
                            text = "粘贴剪贴板",
                            fontSize = 12.sp,
                            color = GoldDream,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    if (clipboard?.hasPrimaryClip() == true) {
                                        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                        val clean = clipText.replace(" ", "").replace("0x", "").trim()
                                        if (clean.length == 32) {
                                            authKeyInput = clean
                                        } else if (clean.isNotBlank()) {
                                            authKeyInput = clean
                                        }
                                    }
                                }
                                .padding(2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = authKeyInput,
                        onValueChange = { authKeyInput = it.filter { c -> !c.isWhitespace() } },
                        placeholder = { Text("例如：3027b40bc07e2c918e97... 或 0x3027b...", fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldDream,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = DarkTextPrimary,
                            unfocusedTextColor = DarkTextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    val cleanLen = authKeyInput.replace(" ", "").replace("0x", "").replace("0X", "").length
                    Text(
                        text = if (cleanLen == 32) {
                            "✅ 密钥有效：已就绪 (16字节 / 32位 Hex)"
                        } else {
                            "💡 小米手环 6 必须提供 32 位 Hex Key (当前有效: ${cleanLen}/32位)，前缀 0x 会自动兼容。"
                        },
                        fontSize = 11.sp,
                        color = if (cleanLen == 32) GoldDream else DarkTextTertiary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Current Connection state card
                if (connectionState != BleConnectionState.DISCONNECTED || (authStatusDetail.isNotBlank() && authStatusDetail != "手环未连接")) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurfaceElevated)
                            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (connectionState) {
                                            BleConnectionState.CONNECTED -> GoldDream
                                            BleConnectionState.ERROR -> HeartRateRed
                                            else -> MiBandCyan
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = connectionState.displayName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (connectionState) {
                                    BleConnectionState.CONNECTED -> GoldDream
                                    BleConnectionState.ERROR -> HeartRateRed
                                    else -> MiBandCyan
                                }
                            )
                        }

                        if (authStatusDetail.isNotBlank() && authStatusDetail != "手环未连接") {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = authStatusDetail,
                                fontSize = 11.sp,
                                color = if (connectionState == BleConnectionState.ERROR) HeartRateRed else DarkTextSecondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    bleManager.stopBleScan()
                    onSaveAndConnect(selectedMac.trim(), authKeyInput.trim())
                },
                enabled = selectedMac.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = GoldDream),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("保存并连接", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    bleManager.stopBleScan()
                    onDismiss()
                }
            ) {
                Text("取消", color = DarkTextSecondary)
            }
        }
    )
}
