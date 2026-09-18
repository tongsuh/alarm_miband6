package com.flashalarm.miband.ui.sleep

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flashalarm.miband.R
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
    val allSessions by viewModel.allSessions.collectAsState()
    val selectedSessionId by viewModel.selectedSessionId.collectAsState()
    val selectedSession by viewModel.selectedSession.collectAsState()
    val currentEpochs by viewModel.currentEpochs.collectAsState()
    val currentCues by viewModel.currentCues.collectAsState()

    val dateFormat = SimpleDateFormat("MM月dd日", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

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
