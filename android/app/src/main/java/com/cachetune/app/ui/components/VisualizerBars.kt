package com.cachetune.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cachetune.app.ui.theme.NeonCyan

/**
 * Animated Audio Visualizer.
 * Displays bouncy frequency wave bars reactive to music playback.
 */
@Composable
fun VisualizerBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 4,
    barWidth: Dp = 3.dp,
    maxHeight: Dp = 18.dp,
    color: Color = NeonCyan
) {
    val infiniteTransition = rememberInfiniteTransition(label = "visualizer")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 0 until barCount) {
            val animDuration = 400 + (i * 120)
            val animatedHeight by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = animDuration, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$i"
            )

            val currentHeight = if (isPlaying) maxHeight * animatedHeight else 4.dp

            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(currentHeight)
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}
