package com.flashalarm.miband.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flashalarm.miband.data.db.DreamCueEntity
import com.flashalarm.miband.data.db.SleepEpochEntity
import com.flashalarm.miband.domain.model.SleepStage
import com.flashalarm.miband.ui.theme.DarkBorder
import com.flashalarm.miband.ui.theme.DarkSurfaceElevated
import com.flashalarm.miband.ui.theme.DarkTextPrimary
import com.flashalarm.miband.ui.theme.DarkTextSecondary
import com.flashalarm.miband.ui.theme.DarkTextTertiary
import com.flashalarm.miband.ui.theme.GoldDream
import com.flashalarm.miband.ui.theme.HeartRateRed
import com.flashalarm.miband.ui.theme.StageAwakeColor
import com.flashalarm.miband.ui.theme.StageDeepColor
import com.flashalarm.miband.ui.theme.StageLightColor
import com.flashalarm.miband.ui.theme.StageRemColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HypnogramChart(
    epochs: List<SleepEpochEntity>,
    cues: List<DreamCueEntity>,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 220
) {
    if (epochs.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(chartHeightDp.dp)
                .background(DarkSurfaceElevated, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("暂无睡眠分期数据", color = DarkTextTertiary, fontSize = 14.sp)
        }
        return
    }

    var scrubIndex by remember { mutableStateOf<Int?>(null) }
    var scrubX by remember { mutableStateOf(0f) }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // Find continuous segment boundaries for current scrub index
    val scrubInfo = remember(scrubIndex, epochs) {
        val idx = scrubIndex
        if (idx == null || idx !in epochs.indices) null
        else {
            val currentEpoch = epochs[idx]
            val currentStage = SleepStage.fromCode(currentEpoch.stage)

            // Find start of continuous segment
            var startIdx = idx
            while (startIdx > 0 && epochs[startIdx - 1].stage == currentEpoch.stage) {
                startIdx--
            }

            // Find end of continuous segment
            var endIdx = idx
            while (endIdx < epochs.size - 1 && epochs[endIdx + 1].stage == currentEpoch.stage) {
                endIdx++
            }

            val startTimeStr = timeFormat.format(Date(epochs[startIdx].timestamp))
            val endTimeStr = timeFormat.format(Date(epochs[endIdx].timestamp))

            ScrubDetails(
                stageName = currentStage.displayName,
                timeRangeStr = "$startTimeStr ~ $endTimeStr",
                heartRateBpm = currentEpoch.heartRate,
                stageColor = when (currentStage) {
                    SleepStage.AWAKE -> StageAwakeColor
                    SleepStage.REM -> StageRemColor
                    SleepStage.LIGHT -> StageLightColor
                    SleepStage.DEEP -> StageDeepColor
                }
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkSurfaceElevated, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        // Minimal Stage Info Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (scrubInfo != null) {
                // Minimal Stage Display: e.g. "深睡 · 00:30 ~ 01:50" (strictly no redundant duration text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(scrubInfo.stageColor, RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${scrubInfo.stageName} · ${scrubInfo.timeRangeStr}",
                        color = DarkTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                // Overlaid Heart Rate at that moment: e.g. "68 bpm"
                Text(
                    text = "${scrubInfo.heartRateBpm} bpm",
                    color = HeartRateRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = "催眠图谱与心率流",
                    color = DarkTextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "手指左右滑动探索",
                    color = DarkTextTertiary,
                    fontSize = 12.sp
                )
            }
        }

        // Canvas for Hypnogram Step Curve + Heart Rate Curve + Star Cues
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeightDp.dp)
                .pointerInput(epochs) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val n = epochs.size
                            val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                            val idx = (fraction * (n - 1)).toInt().coerceIn(0, n - 1)
                            scrubIndex = idx
                            scrubX = offset.x
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val n = epochs.size
                            val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            val idx = (fraction * (n - 1)).toInt().coerceIn(0, n - 1)
                            scrubIndex = idx
                            scrubX = change.position.x
                        },
                        onDragEnd = {
                            scrubIndex = null
                        },
                        onDragCancel = {
                            scrubIndex = null
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val width = size.width
                val height = size.height
                val n = epochs.size
                if (n < 2) return@Canvas

                // 4 Hypnogram Levels:
                // Level 0 (Top): Awake (y = height * 0.12)
                // Level 1: REM (y = height * 0.38)
                // Level 2: Light (y = height * 0.64)
                // Level 3 (Bottom): Deep (y = height * 0.88)
                val stageY = mapOf(
                    SleepStage.AWAKE to height * 0.12f,
                    SleepStage.REM to height * 0.38f,
                    SleepStage.LIGHT to height * 0.64f,
                    SleepStage.DEEP to height * 0.88f
                )

                // Draw background horizontal guide lines for the 4 stages
                val stages = listOf(
                    SleepStage.AWAKE,
                    SleepStage.REM,
                    SleepStage.LIGHT,
                    SleepStage.DEEP
                )
                stages.forEach { stage ->
                    val y = stageY.getValue(stage)
                    drawLine(
                        color = DarkBorder.copy(alpha = 0.5f),
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                }

                // 1. Build Hypnogram Step Path
                val stepPath = Path()
                val stepFillPath = Path()

                var prevX = 0f
                var prevY = stageY.getValue(SleepStage.fromCode(epochs[0].stage))
                stepPath.moveTo(prevX, prevY)
                stepFillPath.moveTo(prevX, height)
                stepFillPath.lineTo(prevX, prevY)

                for (i in 1 until n) {
                    val x = (i.toFloat() / (n - 1)) * width
                    val currentY = stageY.getValue(SleepStage.fromCode(epochs[i].stage))

                    // Step line: horizontal to x, then vertical to currentY
                    stepPath.lineTo(x, prevY)
                    stepPath.lineTo(x, currentY)

                    stepFillPath.lineTo(x, prevY)
                    stepFillPath.lineTo(x, currentY)

                    prevX = x
                    prevY = currentY
                }
                stepFillPath.lineTo(width, height)
                stepFillPath.close()

                // Draw subtle stage gradient fill
                drawPath(
                    path = stepFillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            StageAwakeColor.copy(alpha = 0.15f),
                            StageRemColor.copy(alpha = 0.15f),
                            StageLightColor.copy(alpha = 0.10f),
                            StageDeepColor.copy(alpha = 0.05f)
                        ),
                        startY = 0f,
                        endY = height
                    )
                )

                // Draw step path stroke
                drawPath(
                    path = stepPath,
                    color = Color(0xFF60A5FA),
                    style = Stroke(width = 3f, cap = StrokeCap.Round)
                )

                // 2. Overlaid Heart Rate Line (Pink/Red curve)
                val minHr = 45f
                val maxHr = 110f
                val hrPath = Path()
                var firstHr = true

                for (i in 0 until n) {
                    val hr = epochs[i].heartRate
                    if (hr > 0) {
                        val x = (i.toFloat() / (n - 1)) * width
                        val norm = ((hr - minHr) / (maxHr - minHr)).coerceIn(0f, 1f)
                        val y = height - (norm * height * 0.85f) - (height * 0.07f)

                        if (firstHr) {
                            hrPath.moveTo(x, y)
                            firstHr = false
                        } else {
                            hrPath.lineTo(x, y)
                        }
                    }
                }

                drawPath(
                    path = hrPath,
                    color = HeartRateRed.copy(alpha = 0.75f),
                    style = Stroke(width = 1.8f, cap = StrokeCap.Round)
                )

                // 3. Draw Lucid Dream Cue Gold Star ✨ Markers
                val sessionStart = epochs.first().timestamp
                val sessionEnd = epochs.last().timestamp
                val sessionDuration = (sessionEnd - sessionStart).coerceAtLeast(1L)

                cues.forEach { cue ->
                    val fraction = ((cue.timestamp - sessionStart).toFloat() / sessionDuration).coerceIn(0f, 1f)
                    val cx = fraction * width
                    val remY = stageY.getValue(SleepStage.REM)

                    // Draw golden star / diamond marker
                    drawDreamStar(cx, remY)
                }

                // 4. Scrubbing Crosshair & Glowing Tracking Dot
                scrubIndex?.let { idx ->
                    val cx = (idx.toFloat() / (n - 1)) * width
                    val currentEpoch = epochs[idx]
                    val currentStage = SleepStage.fromCode(currentEpoch.stage)
                    val cy = stageY.getValue(currentStage)

                    // Vertical glowing line
                    drawLine(
                        color = Color.White.copy(alpha = 0.65f),
                        start = Offset(cx, 0f),
                        end = Offset(cx, height),
                        strokeWidth = 2f
                    )

                    // Outer halo
                    drawCircle(
                        color = GoldDream.copy(alpha = 0.35f),
                        radius = 12f,
                        center = Offset(cx, cy)
                    )

                    // Center dot
                    drawCircle(
                        color = Color.White,
                        radius = 5f,
                        center = Offset(cx, cy)
                    )
                }
            }
        }

        // Timeline axis labels (Start, Mid, End)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val startTime = timeFormat.format(Date(epochs.first().timestamp))
            val midTime = timeFormat.format(Date((epochs.first().timestamp + epochs.last().timestamp) / 2))
            val endTime = timeFormat.format(Date(epochs.last().timestamp))

            Text(startTime, color = DarkTextTertiary, fontSize = 11.sp)
            Text(midTime, color = DarkTextTertiary, fontSize = 11.sp)
            Text(endTime, color = DarkTextTertiary, fontSize = 11.sp)
        }
    }
}

private fun DrawScope.drawDreamStar(cx: Float, cy: Float) {
    // Halo glow
    drawCircle(
        color = GoldDream.copy(alpha = 0.4f),
        radius = 10f,
        center = Offset(cx, cy)
    )

    // 4-pointed diamond star
    val starPath = Path().apply {
        moveTo(cx, cy - 8f)
        lineTo(cx + 4f, cy)
        lineTo(cx, cy + 8f)
        lineTo(cx - 4f, cy)
        close()
    }
    drawPath(starPath, GoldDream, style = Fill)
}

private data class ScrubDetails(
    val stageName: String,
    val timeRangeStr: String,
    val heartRateBpm: Int,
    val stageColor: Color
)
