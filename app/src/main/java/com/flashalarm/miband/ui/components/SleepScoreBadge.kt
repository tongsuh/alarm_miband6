package com.flashalarm.miband.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flashalarm.miband.ui.theme.DarkBorder
import com.flashalarm.miband.ui.theme.DarkTextPrimary
import com.flashalarm.miband.ui.theme.DarkTextSecondary
import com.flashalarm.miband.ui.theme.GoldDream

/**
 * Circular sleep score badge.
 * Requirement: Strictly single-line layout, number and "分" aligned horizontally, NO wrapping.
 */
@Composable
fun SleepScoreBadge(
    score: Int,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    strokeWidth: Dp = 6.dp
) {
    val progress = (score.coerceIn(0, 100) / 100f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 800),
        label = "scoreProgress"
    )

    val ringColor = when {
        score >= 85 -> Brush.sweepGradient(listOf(GoldDream, Color(0xFFFFA000), GoldDream))
        score >= 70 -> Brush.sweepGradient(listOf(Color(0xFF38BDF8), Color(0xFF3B82F6), Color(0xFF38BDF8)))
        else -> Brush.sweepGradient(listOf(Color(0xFFF87171), Color(0xFFEF4444), Color(0xFFF87171)))
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = strokeWidth.toPx()
            // Track circle
            drawCircle(
                color = DarkBorder,
                radius = (size.toPx() - strokePx) / 2f,
                style = Stroke(width = strokePx)
            )
            // Progress arc
            drawArc(
                brush = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }

        // Horizontal single line: Number and "分"
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Text(
                text = score.toString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = DarkTextPrimary,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = "分",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = DarkTextSecondary,
                modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
