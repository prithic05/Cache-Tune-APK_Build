package com.cachetune.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cachetune.app.core.model.Song
import com.cachetune.app.ui.theme.*

/**
 * 1:1 YouTube Music "Your queue" Bottom Sheet with live Drag & Reorder.
 * Allows arranging songs smoothly in any order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    queue: List<Song>,
    currentIndex: Int,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (Int) -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { 52.dp.toPx() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF181818),
        scrimColor = Color.Black.copy(alpha = 0.65f),
        dragHandle = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.3f))
                )
            }
        },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
        ) {
            // Header Row: "Playing from: Your queue" + "+ Save"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Playing from",
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                    Text(
                        text = "Your queue (${queue.size} songs)",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // "+ Save" Action Pill
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF282828),
                    modifier = Modifier.clickable { /* Save Queue */ }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlaylistAdd,
                            contentDescription = "Save",
                            tint = CacheTuneOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Save",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Reorderable Queue List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                itemsIndexed(queue, key = { index, song -> "${song.id}_$index" }) { index, song ->
                    val isCurrent = index == currentIndex
                    var verticalDragOffset by remember { mutableFloatStateOf(0f) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCurrent) Color(0xFF262626) else Color.Transparent)
                            .clickable { onSongClick(index) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Thumbnail
                        AsyncImage(
                            model = song.artworkUrl,
                            contentDescription = song.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // Title & Artist • Duration
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                color = if (isCurrent) CacheTuneOrangeBright else Color.White,
                                fontSize = 14.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val durationText = formatDuration(song.durationMs)
                            Text(
                                text = "${song.artist} • $durationText",
                                color = Color(0xFFAAAAAA),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Up/Down quick buttons for precision reorder
                        if (index > 0) {
                            IconButton(
                                onClick = { onReorder(index, index - 1) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = "Move Up",
                                    tint = Color(0xFF888888),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (index < queue.size - 1) {
                            IconButton(
                                onClick = { onReorder(index, index + 1) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "Move Down",
                                    tint = Color(0xFF888888),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Live Drag Handle (═) with continuous drag detection
                        Icon(
                            imageVector = Icons.Rounded.DragHandle,
                            contentDescription = "Reorder",
                            tint = CacheTuneOrange,
                            modifier = Modifier
                                .size(32.dp)
                                .padding(horizontal = 4.dp)
                                .pointerInput(index) {
                                    detectVerticalDragGestures(
                                        onDragStart = { verticalDragOffset = 0f },
                                        onDragEnd = { verticalDragOffset = 0f },
                                        onDragCancel = { verticalDragOffset = 0f },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            verticalDragOffset += dragAmount
                                            if (verticalDragOffset > dragThresholdPx && index < queue.size - 1) {
                                                onReorder(index, index + 1)
                                                verticalDragOffset = 0f
                                            } else if (verticalDragOffset < -dragThresholdPx && index > 0) {
                                                onReorder(index, index - 1)
                                                verticalDragOffset = 0f
                                            }
                                        }
                                    )
                                }
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
