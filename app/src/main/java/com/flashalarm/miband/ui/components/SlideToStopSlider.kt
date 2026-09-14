package com.flashalarm.miband.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import com.flashalarm.miband.ui.theme.DarkBorder
import com.flashalarm.miband.ui.theme.DarkSurfaceElevated
import com.flashalarm.miband.ui.theme.DarkTextPrimary
import com.flashalarm.miband.ui.theme.DarkTextTertiary
import com.flashalarm.miband.ui.theme.GoldDream
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SlideToStopSlider(
    onStopConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    sliderHeightDp: Int = 64
) {
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current

    val thumbOffsetPx = remember { Animatable(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(sliderHeightDp.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(DarkSurfaceElevated)
    ) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val thumbSizeDp = (sliderHeightDp - 8).dp
        val thumbSizePx = with(density) { thumbSizeDp.toPx() }
        val maxOffsetPx = kotlin.math.max(0f, totalWidthPx - thumbSizePx - with(density) { 8.dp.toPx() })

        // Progress track fill behind thumb
        val currentFraction = if (maxOffsetPx > 0f) (thumbOffsetPx.value / maxOffsetPx).coerceIn(0f, 1f) else 0f
        val trackFillWidthDp = with(density) { (thumbOffsetPx.value + thumbSizePx).toDp() }

        Box(
            modifier = Modifier
                .width(trackFillWidthDp)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFFEF4444).copy(alpha = 0.2f), Color(0xFFEF4444).copy(alpha = 0.6f))
                    )
                )
        )

        // Center Hint Text
        Text(
            text = "向右滑动结束守护",
            color = if (currentFraction > 0.4f) DarkTextPrimary else DarkTextTertiary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center)
        )

        // Draggable Thumb
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .offset { IntOffset(thumbOffsetPx.value.roundToInt(), 0) }
                .size(thumbSizeDp)
                .clip(CircleShape)
                .background(if (currentFraction > 0.7f) Color(0xFFEF4444) else GoldDream)
                .align(Alignment.CenterStart)
                .pointerInput(maxOffsetPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            // Apply damping resistance curve: dragAmount * 0.85
                            val damped = dragAmount * 0.85f
                            coroutineScope.launch {
                                val target = (thumbOffsetPx.value + damped).coerceIn(0f, maxOffsetPx)
                                thumbOffsetPx.snapTo(target)
                            }
                        },
                        onDragEnd = {
                            if (currentFraction >= 0.75f) {
                                // Confirmed stop with haptic
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                } else {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                                coroutineScope.launch {
                                    thumbOffsetPx.animateTo(maxOffsetPx, spring())
                                    onStopConfirmed()
                                }
                            } else {
                                // Spring back
                                coroutineScope.launch {
                                    thumbOffsetPx.animateTo(0f, spring())
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                thumbOffsetPx.animateTo(0f, spring())
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_right),
                contentDescription = "Slide Arrow",
                tint = Color.Black,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
