package com.flashalarm.miband.ui.sleep

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flashalarm.miband.R
import com.flashalarm.miband.data.db.AlgorithmDiagnosticEntity
import com.flashalarm.miband.data.db.DreamCueEntity
import com.flashalarm.miband.data.db.SleepSessionEntity
import com.flashalarm.miband.ui.components.HypnogramChart
import com.flashalarm.miband.ui.components.SleepScoreBadge
import com.flashalarm.miband.ui.theme.AlertPurple
import com.flashalarm.miband.ui.theme.DarkBorder
import com.flashalarm.miband.ui.theme.DarkSurface
import com.flashalarm.miband.ui.theme.DarkSurfaceElevated
import com.flashalarm.miband.ui.theme.DarkTextPrimary
import com.flashalarm.miband.ui.theme.DarkTextSecondary
import com.flashalarm.miband.ui.theme.DarkTextTertiary
import com.flashalarm.miband.ui.theme.GoldDream
import com.flashalarm.miband.ui.theme.HeartRateRed
import com.flashalarm.miband.ui.theme.PureBlack
import com.flashalarm.miband.ui.theme.StageAwakeColor
import com.flashalarm.miband.ui.theme.StageDeepColor
import com.flashalarm.miband.ui.theme.StageLightColor
import com.flashalarm.miband.ui.theme.StageRemColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SleepAnalysisScreen(
    viewModel: SleepViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allSessions by viewModel.allSessions.collectAsState()
    val selectedSessionId by viewModel.selectedSessionId.collectAsState()
    val selectedSession by viewModel.selectedSession.collectAsState()
    val currentEpochs by viewModel.currentEpochs.collectAsState()
    val currentCues by viewModel.currentCues.collectAsState()
    val currentDiagnostics by viewModel.currentDiagnostics.collectAsState()

    val dateFormat = SimpleDateFormat("MM月dd日", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val onCopyAiReport: (String) -> Unit = { reportText ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("FlashAlarm_AI_Diagnostic_Report", reportText)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, "已复制 AI 诊断报告，可直接粘贴给 AI 进行分析！", Toast.LENGTH_LONG).show()
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
                .padding(bottom = 60.dp)
        ) {
            // Header: Screen Title & Seed Mock Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "睡眠报告",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                    Text(
                        text = "多模态做梦期与催眠图谱",
                        fontSize = 13.sp,
                        color = DarkTextSecondary
                    )
                }

                // Quick Seed Mock Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkSurfaceElevated)
                        .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                        .clickable { viewModel.generateMockSession() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "+ 模拟记录",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = GoldDream
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // History Multi-Session Switcher (Indexed by sessionId)
            if (allSessions.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allSessions, key = { it.sessionId }) { session ->
                        val isSelected = session.sessionId == selectedSessionId
                        val dateStr = dateFormat.format(Date(session.startTime))
                        val isNap = session.netSleepMinutes < 90

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) GoldDream.copy(alpha = 0.15f) else DarkSurfaceElevated)
                                .border(1.dp, if (isSelected) GoldDream else DarkBorder, RoundedCornerShape(12.dp))
                                .clickable { viewModel.selectSession(session.sessionId) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (isNap) "午休 #$dateStr" else "夜间 #$dateStr",
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) GoldDream else DarkTextSecondary
                                )
                                if (session.cueCount > 0) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "✨${session.cueCount}",
                                        fontSize = 11.sp,
                                        color = GoldDream
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }

            // Selected Session Detail
            selectedSession?.let { session ->
                // 1. Sleep Recap Card (大字睡眠战报卡片)
                SleepRecapCard(
                    session = session,
                    onDeleteClick = { showDeleteConfirmDialog = true }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Stages Proportion Progress Bar
                StageProportionRow(session = session)

                Spacer(modifier = Modifier.height(20.dp))

                // 3. Hypnogram Step Chart (催眠图谱阶梯图带实时手指拖拽准心)
                HypnogramChart(
                    epochs = currentEpochs,
                    cues = currentCues
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 4. Lucid Dream Cue Details Card (触梦提醒战报)
                LucidDreamCueSummaryCard(
                    cues = currentCues,
                    timeFormat = timeFormat
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 5. Algorithm Deep Diagnostic Log (Blackbox Traceability)
                AlgorithmDiagnosticSection(
                    diagnostics = currentDiagnostics,
                    session = selectedSession,
                    onCopyAiReport = onCopyAiReport
                )

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedButton(
                    onClick = { showDeleteConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, HeartRateRed.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = HeartRateRed.copy(alpha = 0.08f),
                        contentColor = HeartRateRed
                    )
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_trash),
                        contentDescription = null,
                        tint = HeartRateRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("彻底删除此睡眠记录", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = HeartRateRed)
                }
            } ?: run {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无睡眠记录，点击右上角生成模拟测试记录", color = DarkTextTertiary, fontSize = 14.sp)
                }
            }
        }
    }

    if (showDeleteConfirmDialog && selectedSession != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text(
                    text = "删除睡眠记录",
                    fontWeight = FontWeight.Bold,
                    color = DarkTextPrimary
                )
            },
            text = {
                Text(
                    text = "确定要彻底删除该睡眠记录吗？包含的所有时序图谱、分期数据和触梦事件都将被清除，且不可恢复。",
                    color = DarkTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedSession?.let { s ->
                            viewModel.deleteSession(s.sessionId)
                        }
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = HeartRateRed)
                ) {
                    Text("确认删除", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteConfirmDialog = false }
                ) {
                    Text("取消", color = DarkTextSecondary)
                }
            },
            containerColor = DarkSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

/**
 * Large Typography Sleep Recap Card.
 * Requirements: Net sleep duration, Time in bed, Sleep efficiency,
 * Right-aligned circular score badge (strictly single line, number & "分" aligned horizontally, NO wrap).
 */
@Composable
private fun SleepRecapCard(
    session: SleepSessionEntity,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkBorder, RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header Row: Label & Delete Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "净睡眠时长",
                    fontSize = 13.sp,
                    color = DarkTextTertiary
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(HeartRateRed.copy(alpha = 0.12f))
                        .border(1.dp, HeartRateRed.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .clickable { onDeleteClick() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_trash),
                        contentDescription = "Delete Record",
                        tint = HeartRateRed,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "删除此记录",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HeartRateRed
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Big Duration & Metrics
                Column(modifier = Modifier.weight(1f)) {
                    val hours = session.netSleepMinutes / 60
                    val minutes = session.netSleepMinutes % 60
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = hours.toString(),
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkTextPrimary
                        )
                        Text(
                            text = "时",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = DarkTextSecondary,
                            modifier = Modifier.padding(bottom = 6.dp, start = 2.dp, end = 6.dp)
                        )
                        Text(
                            text = "%02d".format(minutes),
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkTextPrimary
                        )
                        Text(
                            text = "分",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = DarkTextSecondary,
                            modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Time in Bed & Efficiency
                    val bedHours = session.timeInBedMinutes / 60
                    val bedMins = session.timeInBedMinutes % 60
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column {
                            Text("在床时间", fontSize = 11.sp, color = DarkTextTertiary)
                            Text("${bedHours}h ${bedMins}m", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DarkTextSecondary)
                        }
                        Column {
                            Text("睡眠效率", fontSize = 11.sp, color = DarkTextTertiary)
                            Text("${session.efficiency}%", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GoldDream)
                        }
                    }
                }

                // Right: Fixed Circular Score Badge
                // Requirement: Strictly single line, horizontal alignment, NO wrapping
                SleepScoreBadge(
                    score = session.sleepScore,
                    size = 78.dp,
                    strokeWidth = 7.dp
                )
            }
        }
    }
}

@Composable
private fun StageProportionRow(session: SleepSessionEntity) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkBorder, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Horizontal stacked progress bar
            val total = session.timeInBedMinutes.coerceAtLeast(1)
            val deepFraction = session.deepMinutes.toFloat() / total
            val remFraction = session.remMinutes.toFloat() / total
            val lightFraction = session.lightMinutes.toFloat() / total
            val awakeFraction = session.awakeMinutes.toFloat() / total

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(DarkSurface)
            ) {
                if (deepFraction > 0) {
                    Box(modifier = Modifier.weight(deepFraction).fillMaxSize().background(StageDeepColor))
                }
                if (remFraction > 0) {
                    Box(modifier = Modifier.weight(remFraction).fillMaxSize().background(StageRemColor))
                }
                if (lightFraction > 0) {
                    Box(modifier = Modifier.weight(lightFraction).fillMaxSize().background(StageLightColor))
                }
                if (awakeFraction > 0) {
                    Box(modifier = Modifier.weight(awakeFraction).fillMaxSize().background(StageAwakeColor))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stage Duration Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StageChip(name = "深睡", durationMin = session.deepMinutes, color = StageDeepColor)
                StageChip(name = "REM", durationMin = session.remMinutes, color = StageRemColor)
                StageChip(name = "浅睡", durationMin = session.lightMinutes, color = StageLightColor)
                StageChip(name = "清醒", durationMin = session.awakeMinutes, color = StageAwakeColor)
            }
        }
    }
}

@Composable
private fun StageChip(name: String, durationMin: Int, color: Color) {
    val h = durationMin / 60
    val m = durationMin % 60
    Column(horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(modifier = Modifier.width(4.dp))
            Text(name, fontSize = 11.sp, color = DarkTextTertiary)
        }
        Text(
            text = if (h > 0) "${h}h ${m}m" else "${m}m",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = DarkTextPrimary
        )
    }
}

@Composable
private fun LucidDreamCueSummaryCard(
    cues: List<DreamCueEntity>,
    timeFormat: SimpleDateFormat
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
                    Icon(
                        painter = painterResource(id = R.drawable.ic_star),
                        contentDescription = null,
                        tint = GoldDream,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "腕部黄金触梦提醒",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                }

                val ackCount = cues.count { it.acknowledged }
                Text(
                    text = if (ackCount > 0) "共击发 ${cues.size} 次 · 意识唤醒 $ackCount 次" else "共击发 ${cues.size} 次",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = GoldDream
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (cues.isEmpty()) {
                Text(
                    text = "本次睡眠未达到触发条件或处于保护冷却窗中",
                    fontSize = 12.sp,
                    color = DarkTextTertiary
                )
            } else {
                cues.forEachIndexed { index, cue ->
                    val cueTime = timeFormat.format(Date(cue.timestamp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurface)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "第 ${index + 1} 次 · $cueTime",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DarkTextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = cue.cadenceName,
                                        fontSize = 11.sp,
                                        color = GoldDream
                                    )
                                    if (cue.acknowledged) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(GoldDream.copy(alpha = 0.2f))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "✨ 意识唤醒成功",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = GoldDream
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = cue.triggerReason,
                                    fontSize = 11.sp,
                                    color = DarkTextTertiary
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${cue.heartRate} bpm",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = HeartRateRed
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${(cue.confidence * 100).toInt()}%",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GoldDream
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlgorithmDiagnosticSection(
    diagnostics: List<AlgorithmDiagnosticEntity>,
    session: SleepSessionEntity?,
    onCopyAiReport: (String) -> Unit
) {
    if (diagnostics.isEmpty()) return

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Macro Metrics
    val eogActiveEpochs = diagnostics.filter { it.eogBursts > 0 }
    val avgBoost = if (eogActiveEpochs.isNotEmpty()) {
        eogActiveEpochs.map { it.effectiveLogitBoost }.average().toFloat()
    } else 0f

    val bridgeConversions = diagnostics.count {
        it.baseRemProb < it.effectiveThreshold && it.fusedRemProb >= it.effectiveThreshold
    }

    val totalCues = diagnostics.count { it.isCueTriggered }

    // Filter state: 0: Cues, 1: EOG/REM Active, 2: All
    var selectedFilter by remember { mutableStateOf(0) }

    val filteredList = remember(diagnostics, selectedFilter) {
        when (selectedFilter) {
            0 -> diagnostics.filter { it.isCueTriggered }
            1 -> diagnostics.filter {
                it.isCueTriggered || it.stage == 1 || it.eogBursts > 0 || it.baseRemProb >= 0.25f || it.fusedRemProb >= 0.40f
            }
            else -> diagnostics
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🔬 算法深度诊断 (黑匣子)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                    Text(
                        text = "1Hz手环AI基座 + EOG残差推力全链路溯源",
                        fontSize = 11.sp,
                        color = DarkTextTertiary
                    )
                }

                // Copy for AI Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AlertPurple.copy(alpha = 0.2f))
                        .border(1.dp, AlertPurple.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable {
                            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            val tf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            val report = generateAiDiagnosticReport(session, diagnostics, dateFormat, tf)
                            onCopyAiReport(report)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "📋 复制给AI",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AlertPurple
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 1. Macro Dashboard Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MacroStatCard(
                    title = "EOG 平均推力",
                    value = if (avgBoost > 0) "+${"%.2f".format(avgBoost)}L" else "--",
                    color = AlertPurple,
                    modifier = Modifier.weight(1f)
                )
                MacroStatCard(
                    title = "EOG 促成 REM 跃迁",
                    value = "$bridgeConversions 次",
                    color = GoldDream,
                    modifier = Modifier.weight(1f)
                )
                MacroStatCard(
                    title = "触梦击发",
                    value = "$totalCues 次",
                    color = Color(0xFF22C55E),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val cueCount = diagnostics.count { it.isCueTriggered }
                val activeCount = diagnostics.count {
                    it.isCueTriggered || it.stage == 1 || it.eogBursts > 0 || it.baseRemProb >= 0.25f || it.fusedRemProb >= 0.40f
                }
                DiagnosticFilterChip(
                    text = "✨ 触梦 ($cueCount)",
                    isSelected = selectedFilter == 0,
                    onClick = { selectedFilter = 0 },
                    modifier = Modifier.weight(1f)
                )
                DiagnosticFilterChip(
                    text = "👁️ EOG/做梦 ($activeCount)",
                    isSelected = selectedFilter == 1,
                    onClick = { selectedFilter = 1 },
                    modifier = Modifier.weight(1f)
                )
                DiagnosticFilterChip(
                    text = "📊 全部 (${diagnostics.size})",
                    isSelected = selectedFilter == 2,
                    onClick = { selectedFilter = 2 },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Timeline Items
            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "该筛选条件下无记录",
                        fontSize = 12.sp,
                        color = DarkTextTertiary
                    )
                }
            } else {
                val displayItems = if (selectedFilter == 2) filteredList.take(120) else filteredList
                displayItems.forEachIndexed { idx, item ->
                    DiagnosticTimelineCard(item = item, timeFormat = timeFormat, epochIndex = idx + 1)
                    if (idx < displayItems.size - 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                if (selectedFilter == 2 && filteredList.size > 120) {
                    Text(
                        text = "注：为保持流畅，全部模式仅展示前 120 条记录。点击右上角【📋 复制给AI】可导出完整 ${diagnostics.size} 个周期全量数据！",
                        fontSize = 11.sp,
                        color = DarkTextTertiary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MacroStatCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurface)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                fontSize = 9.sp,
                color = DarkTextSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DiagnosticFilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) AlertPurple else DarkSurface)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) Color.White else DarkTextSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun DiagnosticTimelineCard(
    item: AlgorithmDiagnosticEntity,
    timeFormat: SimpleDateFormat,
    epochIndex: Int
) {
    val timeStr = timeFormat.format(Date(item.timestamp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface)
            .padding(10.dp)
    ) {
        Column {
            // Header Row: Time, Epoch #, Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timeStr,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "#$epochIndex",
                        fontSize = 10.sp,
                        color = DarkTextTertiary
                    )
                }

                val (badgeText, badgeColor) = when {
                    item.isCueTriggered -> "✨ 触梦击发成功" to GoldDream
                    item.isCueEligible -> "⏱️ 冷却间隔拦截" to AlertPurple
                    item.triggerReason.contains("体动") || item.triggerReason.contains("避让") -> "🛑 动作一票否决" to HeartRateRed
                    item.stage == 1 -> "👁️ REM 活跃" to StageRemColor
                    item.stage == 3 -> "🛡️ 慢波深睡保底" to StageDeepColor
                    item.stage == 0 -> "🌙 清醒期" to StageAwakeColor
                    else -> "⚪ 浅睡中" to StageLightColor
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Confidence Waterfall Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF1E222D))
            ) {
                val baseWeight = item.baseRemProb.coerceIn(0.001f, 1f)
                val maxBoost = (1f - baseWeight).coerceAtLeast(0f)
                val boostWeight = item.confidenceBoost.coerceIn(0f, maxBoost)
                val remainingWeight = (1f - (baseWeight + boostWeight)).coerceAtLeast(0.001f)

                if (baseWeight > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(baseWeight)
                            .background(Color(0xFF22C55E))
                    )
                }
                if (boostWeight > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(boostWeight)
                            .background(AlertPurple)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(remainingWeight)
                        .background(Color(0xFF2A2D3A))
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Waterfall Legend Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "1Hz基座: ${(item.baseRemProb * 100).toInt()}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF22C55E)
                    )
                    if (item.confidenceBoost > 0.005f) {
                        Text(
                            text = " + EOG: +${(item.confidenceBoost * 100).toInt()}%",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = AlertPurple
                        )
                    }
                    Text(
                        text = " = ${(item.fusedRemProb * 100).toInt()}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                }

                val isRelaxedTh = item.effectiveThreshold < 0.54f
                Text(
                    text = "门槛: ${(item.effectiveThreshold * 100).toInt()}%" + if (isRelaxedTh) " (强爆发下探)" else "",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isRelaxedTh) GoldDream else DarkTextSecondary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Sensor Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val surgePercent = (item.hrSurgePercent * 100).toInt()
                val hrText = if (item.heartRate > 0) "${item.heartRate} bpm (+${surgePercent}%)" else "HR 离线"
                Text(
                    text = "🫀 $hrText · 肌张力 ${"%.2f".format(item.atoniaScore)}",
                    fontSize = 10.sp,
                    color = DarkTextSecondary
                )

                val eogDesc = if (item.eogBursts > 0) {
                    "👁️ EOG: ${item.eogBursts}次 (+${"%.2f".format(item.effectiveLogitBoost)}L)"
                } else {
                    "👁️ EOG: 静息"
                }
                Text(
                    text = eogDesc,
                    fontSize = 10.sp,
                    color = if (item.eogBursts > 0) AlertPurple else DarkTextTertiary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "📝 ${item.triggerReason}",
                fontSize = 10.sp,
                color = DarkTextTertiary,
                lineHeight = 13.sp
            )
        }
    }
}

private fun generateAiDiagnosticReport(
    session: SleepSessionEntity?,
    diagnostics: List<AlgorithmDiagnosticEntity>,
    dateFormat: SimpleDateFormat,
    timeFormat: SimpleDateFormat
): String {
    val dateStr = session?.let { dateFormat.format(Date(it.startTime)) } ?: "最新睡眠"
    val cues = diagnostics.filter { it.isCueTriggered }
    val eogActive = diagnostics.filter { it.eogBursts > 0 }
    val bridgeCount = diagnostics.count { it.baseRemProb < it.effectiveThreshold && it.fusedRemProb >= it.effectiveThreshold }
    val vetoCount = diagnostics.count { it.triggerReason.contains("体动") || it.triggerReason.contains("避让") }

    val keyDiagnostics = diagnostics.filter {
        it.isCueTriggered || (it.stage == 1 && it.eogBursts > 0) || it.fusedRemProb >= 0.45f || it.triggerReason.contains("体动")
    }.take(60)

    return buildString {
        appendLine("# FlashAlarm 夜间算法决策诊断报告 (AI 审查专用)")
        appendLine()
        appendLine("## 1. 运行配置与监测基线")
        appendLine("- 报告日期: $dateStr")
        appendLine("- 监测时长: ${diagnostics.size / 2} 分钟 (共 ${diagnostics.size} 个 30s 评估周期)")
        if (session != null) {
            appendLine("- 睡眠评分: ${session.sleepScore} 分 | 效率: ${session.efficiency}% | REM: ${session.remMinutes}分 | 深睡: ${session.deepMinutes}分")
        }
        appendLine()
        appendLine("## 2. 宏观多模态协同统计")
        appendLine("- 触梦击发总计: ${cues.size} 次")
        appendLine("- EOG 活跃总周期: ${eogActive.size} 个 (${if (diagnostics.isNotEmpty()) (eogActive.size * 100 / diagnostics.size) else 0}%)")
        appendLine("- EOG 促成 REM 临界跃迁: $bridgeCount 次 (成功突破门槛)")
        appendLine("- 手腕体动避让拦截: $vetoCount 次")
        appendLine()
        appendLine("## 3. 关键诊断时序抽取表 (触梦击发点 + 高价值决策期)")
        appendLine("| 时间 | 周期 | P_base | EOG脉冲 | α门控 | 有效推力 | P_fused | 门槛 | HR(Surge) | 肌张力 | 决策结果 / 抑制原因 |")
        appendLine("|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---|")

        val timeFormatSec = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        keyDiagnostics.forEachIndexed { idx, d ->
            val tStr = timeFormatSec.format(Date(d.timestamp))
            val surgeStr = "+${(d.hrSurgePercent * 100).toInt()}%"
            val cueFlag = if (d.isCueTriggered) "✨ " else ""
            val thFlag = if (d.effectiveThreshold < 0.54f) "*" else ""
            appendLine("| $tStr | #${idx + 1} | ${"%.2f".format(d.baseRemProb)} | ${d.eogBursts} | ${"%.2f".format(d.alphaGating)} | +${"%.2f".format(d.effectiveLogitBoost)}L | ${"%.2f".format(d.fusedRemProb)} | ${"%.2f".format(d.effectiveThreshold)}$thFlag | ${d.heartRate} ($surgeStr) | ${"%.2f".format(d.atoniaScore)} | $cueFlag${d.triggerReason} |")
        }

        appendLine()
        appendLine("*(注: 门槛带 * 表示持续眼动爆发自适应相对下探生效)*")
        appendLine()
        appendLine("## 4. 给 AI 助手的问题引导")
        appendLine("请根据以上夜间生理与算法多模态数据进行专业审查：")
        appendLine("1. 评估当前的置信门槛设定与 EOG 推力系数是否匹配？是否存在过敏或失敏？")
        appendLine("2. 分析表中的动量抑制或未触发周期，是否存在 REM 期微弱眼动被漏判？")
        appendLine("3. 结合用户的心率浪涌与肌张力表现，给出个性化参数调优建议（如置信门槛、冷却时间、爆发阈值）。")
    }
}
