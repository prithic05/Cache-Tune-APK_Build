package com.cachetune.app.ui.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cachetune.app.core.model.Song
import com.cachetune.app.ui.components.ActionPillRow
import com.cachetune.app.ui.components.DynamicMeshGradient
import com.cachetune.app.ui.theme.*

/**
 * 1:1 YouTube Music Full-Screen Lossless Player.
 * Features:
 * - Download button for 1-tap local saving to Music/CacheTune
 * - Workable Cast/Audio device chooser
 * - Large square album art & action pills
 * - Draggable & reorderable "Your queue" bottom sheet
 */
@Composable
fun PlayerScreen(
    currentSong: Song?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    queue: List<Song> = emptyList(),
    currentIndex: Int = 0,
    onPlayPauseClick: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
    onLyricsClick: () -> Unit,
    onExportFlacClick: () -> Unit,
    statusPhase: String = "Ready",
    onQueueItemClick: (Int) -> Unit = {},
    onReorderQueue: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> }
) {
    if (currentSong == null) return

    val context = LocalContext.current
    val totalDuration = if (durationMs > 0) durationMs else currentSong.durationMs
    var sliderPosition by remember(currentPositionMs) {
        mutableFloatStateOf(
            if (totalDuration > 0) (currentPositionMs.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) else 0f
        )
    }

    var isQueueOpen by remember { mutableStateOf(false) }
    var isShuffleOn by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(0) } // 0 = off, 1 = all, 2 = one

    DynamicMeshGradient(artworkUrl = currentSong.artworkUrl) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Top Bar: Collapse chevron, Download button, Cast icon, 3-dots
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Collapse",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 1-Tap Download Button
                        IconButton(onClick = onExportFlacClick) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "Download Song",
                                tint = CacheTuneOrange,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Workable Cast Button: Opens system audio output / cast panel
                        IconButton(onClick = {
                            try {
                                val intent = Intent("android.settings.PANEL_VOLUME")
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    val intent = Intent(android.provider.Settings.ACTION_CAST_SETTINGS)
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    try {
                                        val intent = Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Cast,
                                contentDescription = "Cast / Output Device",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        IconButton(onClick = { /* More actions */ }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // 2. Large Square Artwork with 16dp rounded corners
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .aspectRatio(1f)
                        .shadow(16.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1A1A1A)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = currentSong.artworkUrl,
                        contentDescription = currentSong.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 3. Song Info: Title with chevron right (>) + Artist Subtitle
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = currentSong.title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = "Song details",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = currentSong.artist,
                        color = Color(0xFFAAAAAA),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 4. Action Pills Row: Lyrics, Dynamic Bitrate/kHz badge, and Real-Time Processing Status
                ActionPillRow(
                    onLyricsClick = onLyricsClick,
                    onDownloadFlacClick = onExportFlacClick,
                    qualityLabel = currentSong.audioQualityBadge,
                    statusPhase = statusPhase,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // 5. Seekbar Timeline
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = sliderPosition,
                        onValueChange = { sliderPosition = it },
                        onValueChangeFinished = {
                            val newTimeMs = (sliderPosition * totalDuration).toLong()
                            onSeek(newTimeMs)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = CacheTuneOrange,
                            activeTrackColor = CacheTuneOrange,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTime(currentPositionMs),
                            color = Color(0xFFAAAAAA),
                            fontSize = 12.sp
                        )
                        Text(
                            text = formatTime(totalDuration),
                            color = Color(0xFFAAAAAA),
                            fontSize = 12.sp
                        )
                    }
                }

                // 6. Playback Controls Row: Shuffle, Prev, Big White Play/Pause Circle, Next, Repeat
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { isShuffleOn = !isShuffleOn }) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffleOn) CacheTuneOrange else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(onClick = onSkipPrev) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Big Solid-White Circular Play/Pause Button with Black Icon
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable(onClick = onPlayPauseClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(onClick = onSkipNext) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(onClick = { repeatMode = (repeatMode + 1) % 3 }) {
                        Icon(
                            imageVector = if (repeatMode == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            contentDescription = "Repeat",
                            tint = if (repeatMode > 0) CacheTuneOrange else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // 7. Bottom Handle: "Your queue"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isQueueOpen = true }
                        .padding(top = 8.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(CacheTuneOrange.copy(alpha = 0.6f))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Your queue (${queue.size})",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Draggable Bottom Sheet for "Your queue" with live reordering
            if (isQueueOpen) {
                QueueBottomSheet(
                    queue = queue,
                    currentIndex = currentIndex,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onSongClick = { index ->
                        onQueueItemClick(index)
                    },
                    onReorder = onReorderQueue,
                    onDismiss = { isQueueOpen = false }
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
