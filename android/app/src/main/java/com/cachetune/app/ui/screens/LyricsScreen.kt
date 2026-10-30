package com.cachetune.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cachetune.app.core.model.LyricLine
import com.cachetune.app.core.model.Song
import com.cachetune.app.ui.components.DynamicMeshGradient
import com.cachetune.app.ui.theme.NeonCyan
import com.cachetune.app.ui.theme.TextPrimary
import com.cachetune.app.ui.theme.TextTertiary

/**
 * Aesthetic Synced Karaoke Lyrics Screen.
 * Smoothly auto-scrolls with glowing text emphasis on the active lyric.
 */
@Composable
fun LyricsScreen(
    currentSong: Song?,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit
) {
    if (currentSong == null) return

    val sampleLyrics = remember(currentSong.id) {
        listOf(
            LyricLine(0L, "♪ (Lossless Intro) ♪"),
            LyricLine(12000L, "I'm tryna put you in the worst mood, ah"),
            LyricLine(16000L, "P1 cleaner than your church shoes, ah"),
            LyricLine(20000L, "Milli point two just to hurt you, ah"),
            LyricLine(24000L, "All red Lamb' just to tease you, ah"),
            LyricLine(28000L, "None of these toys on lease too, ah"),
            LyricLine(32000L, "Made your whole year in a week too, yah"),
            LyricLine(36000L, "Main bitch out your league too, ah"),
            LyricLine(40000L, "Side bitch out of your league too, ah"),
            LyricLine(44000L, "Look what you've done"),
            LyricLine(48000L, "I'm a motherfuckin' starboy"),
            LyricLine(52000L, "Look what you've done"),
            LyricLine(56000L, "I'm a motherfuckin' starboy")
        )
    }

    val activeIndex = remember(currentPositionMs) {
        sampleLyrics.indexOfLast { it.timeMs <= currentPositionMs }.coerceAtLeast(0)
    }

    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex in sampleLyrics.indices) {
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = -300 // Keep active line vertically centered
            )
        }
    }

    DynamicMeshGradient(artworkUrl = currentSong.artworkUrl) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            // Close header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "LIVE LYRICS",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp, color = NeonCyan)
                    )
                    Text(
                        text = currentSong.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                    )
                }

                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = TextPrimary
                    )
                }
            }

            // Lyrics List
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 120.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                itemsIndexed(sampleLyrics) { index, line ->
                    val isActive = index == activeIndex

                    val textColor by animateColorAsState(
                        targetValue = if (isActive) TextPrimary else TextTertiary.copy(alpha = 0.5f),
                        animationSpec = tween(durationMillis = 350),
                        label = "lyric_color"
                    )

                    val textScale by animateFloatAsState(
                        targetValue = if (isActive) 1.08f else 1.0f,
                        animationSpec = tween(durationMillis = 350),
                        label = "lyric_scale"
                    )

                    Text(
                        text = line.text,
                        color = textColor,
                        fontSize = if (isActive) 26.sp else 22.sp,
                        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                        lineHeight = 34.sp,
                        modifier = Modifier
                            .scale(textScale)
                            .clickable { onSeek(line.timeMs) }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}
