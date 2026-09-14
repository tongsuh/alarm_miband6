package com.flashalarm.miband.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flashalarm.miband.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SlideToStopSlider(
    onStopConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    sliderHeightDp: Int = 60
) {
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current

    val thumbOffsetPx = remember { Animatable(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(sliderHeightDp.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Color(0xFF131A26))
            .border(1.dp, Color(0xFF263248), RoundedCornerShape(30.dp))
    ) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val thumbSizeDp = (sliderHeightDp - 8).dp
        val thumbSizePx = with(density) { thumbSizeDp.toPx() }
        val maxOffsetPx = kotlin.math.max(1f, totalWidthPx - thumbSizePx - with(density) { 8.dp.toPx() })

        // Live fraction for display
        val currentFraction = (thumbOffsetPx.value / maxOffsetPx).coerceIn(0f, 1f)
        val isConfirmedZone = currentFraction >= 0.70f

        // Progress track fill behind thumb - fully rounded capsule matching the track
        val trackFillWidthDp = with(density) { (thumbOffsetPx.value + thumbSizePx + with(density) { 8.dp.toPx() }).coerceAtMost(totalWidthPx).toDp() }

        if (thumbOffsetPx.value > 2f) {
            Box(
                modifier = Modifier
                    .width(trackFillWidthDp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(30.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF1E293B),
                                if (isConfirmedZone) Color(0xFFE11D48).copy(alpha = 0.55f) else Color(0xFF334155).copy(alpha = 0.65f)
                            )
                        )
                    )
            )
        }

        // Center Hint Text
        Text(
            text = if (isConfirmedZone) "松手即可退出守护" else "向右滑动结束守护",
            color = if (isConfirmedZone) Color(0xFFFDA4AF) else Color(0xFF94A3B8).copy(alpha = (1f - currentFraction * 0.7f).coerceIn(0.2f, 1f)),
            fontSize = 14.sp,
            fontWeight = if (isConfirmedZone) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center)
        )

        // Draggable Thumb Button
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .offset { IntOffset(thumbOffsetPx.value.roundToInt(), 0) }
                .size(thumbSizeDp)
                .clip(CircleShape)
                .background(
                    if (isConfirmedZone) {
                        Color(0xFFE11D48)
                    } else {
                        Color(0xFFE2E8F0)
                    }
                )
                .border(
                    width = 1.5.dp,
                    color = if (isConfirmedZone) Color(0xFFFDA4AF) else Color.White.copy(alpha = 0.8f),
                    shape = CircleShape
                )
                .align(Alignment.CenterStart)
                .pointerInput(maxOffsetPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            val damped = dragAmount * 0.90f
                            coroutineScope.launch {
                                val target = (thumbOffsetPx.value + damped).coerceIn(0f, maxOffsetPx)
                                thumbOffsetPx.snapTo(target)
                            }
                        },
                        onDragEnd = {
                            // Compute real-time fraction directly from current Animatable value
                            val finalFraction = if (maxOffsetPx > 0f) (thumbOffsetPx.value / maxOffsetPx).coerceIn(0f, 1f) else 0f
                            if (finalFraction >= 0.70f) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                } else {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                                coroutineScope.launch {
                                    thumbOffsetPx.animateTo(maxOffsetPx, spring(dampingRatio = 0.8f))
                                    onStopConfirmed()
                                }
                            } else {
                                coroutineScope.launch {
                                    thumbOffsetPx.animateTo(0f, spring(dampingRatio = 0.75f))
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                thumbOffsetPx.animateTo(0f, spring(dampingRatio = 0.75f))
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_right),
                contentDescription = "Slide Arrow",
                tint = if (isConfirmedZone) Color.White else Color(0xFF0F172A),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
