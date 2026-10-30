package com.cachetune.app.ui.screens

import kotlinx.coroutines.launch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cachetune.app.R
import com.cachetune.app.core.model.HomeSection
import com.cachetune.app.core.model.Song
import com.cachetune.app.ui.components.SpeedDialGrid
import com.cachetune.app.ui.theme.*

data class PlaylistDetails(
    val title: String,
    val subtitle: String,
    val artworkUrl: String,
    val songs: List<Song>,
    val isLoading: Boolean = false
)

/**
 * 1:1 YouTube Music Home Screen.
 * Renders:
 * - App Logo header with "CacheTune" text (No top avatar, no categories row)
 * - 3x3 Speed Dial grid populated from user's personalized feed
 * - "Quick picks" with "Play Next" action on song rows
 * - Full horizontal carousel shelves and curated mixes with interactive Playlist browsing
 */
@Composable
fun HomeScreen(
    homeSections: List<HomeSection>,
    speedDialSongs: List<Song>,
    userName: String = "",
    currentSong: Song?,
    isPlaying: Boolean = false,
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit = {},
    onLoadPlaylist: (suspend (Song) -> List<Song>)? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var activePlaylist by remember { mutableStateOf<PlaylistDetails?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF030303))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 1. Top Bar: Official CacheTune App Logo & Title (Avatar removed)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_logo),
                            contentDescription = "CacheTune Logo",
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Text(
                            text = "CacheTune",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        )
                    }
                }
            }

            // 2. Speed Dial Title + 3x3 Grid (User name row and top category boxes removed)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Speed dial",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    // 3x3 Speed Dial Grid
                    val speedDialList = if (speedDialSongs.isNotEmpty()) {
                        speedDialSongs
                    } else {
                        homeSections.firstOrNull()?.songs?.take(18) ?: emptyList()
                    }

                    SpeedDialGrid(
                        songs = speedDialList,
                        onSongClick = { song ->
                            // Play single clicked speed dial song and generate authentic autoplay radio queue
                            onSongClick(song, emptyList())
                        }
                    )
                }
            }

            // 3. Render All Home Shelves Matching CacheTune Web & YT Music
            items(homeSections) { section ->
                val isTrackList = section.isGrid || 
                    section.title.contains("Quick pick", ignoreCase = true) || 
                    section.title.contains("Trending songs", ignoreCase = true)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Shelf Header: Title + CacheTune Orange "View all" button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (section.title.contains("★")) {
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = CacheTuneOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                text = section.title.replace("★", "").trim(),
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "View all",
                            color = CacheTuneOrange,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                val realSongs = section.songs.filter { !it.isPlaylistCard }
                                if (realSongs.isNotEmpty()) {
                                    activePlaylist = PlaylistDetails(
                                        title = section.title.replace("★", "").trim(),
                                        subtitle = "CacheTune Curated Mix",
                                        artworkUrl = realSongs.firstOrNull()?.artworkUrl ?: "",
                                        songs = realSongs,
                                        isLoading = false
                                    )
                                } else {
                                    val firstPl = section.songs.firstOrNull()
                                    activePlaylist = PlaylistDetails(
                                        title = section.title.replace("★", "").trim(),
                                        subtitle = "CacheTune Curated Mix",
                                        artworkUrl = firstPl?.artworkUrl ?: "",
                                        songs = emptyList(),
                                        isLoading = true
                                    )
                                    coroutineScope.launch {
                                        val tracks = if (firstPl != null) onLoadPlaylist?.invoke(firstPl) ?: emptyList() else emptyList()
                                        activePlaylist = PlaylistDetails(
                                            title = section.title.replace("★", "").trim(),
                                            subtitle = "CacheTune Curated Mix",
                                            artworkUrl = firstPl?.artworkUrl ?: "",
                                            songs = tracks,
                                            isLoading = false
                                        )
                                    }
                                }
                            }
                        )
                    }

                    if (isTrackList) {
                        // Vertical 4-row Column Chunks with Play Next Button on each row
                        val chunks = section.songs.chunked(4)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(chunks) { columnSongs ->
                                    Column(
                                        modifier = Modifier.width(320.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        for (song in columnSongs) {
                                            val isCurrent = currentSong?.id == song.id
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isCurrent) Color(0xFF262626) else Color.Transparent)
                                                    .clickable { onSongClick(song, section.songs) }
                                                    .padding(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                AsyncImage(
                                                    model = song.artworkUrl,
                                                    contentDescription = song.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(50.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = song.title,
                                                        color = if (isCurrent) CacheTuneOrangeBright else Color.White,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = song.artist,
                                                        color = Color(0xFFAAAAAA),
                                                        fontSize = 12.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                // Play Next button on song row
                                                IconButton(
                                                    onClick = { onPlayNext(song) },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.QueueMusic,
                                                        contentDescription = "Play Next",
                                                        tint = CacheTuneOrange,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Floating Right Next Arrow Button
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 10.dp)
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF242424).copy(alpha = 0.9f))
                                    .clickable {
                                        val realSongs = section.songs.filter { !it.isPlaylistCard }
                                        if (realSongs.isNotEmpty()) {
                                            activePlaylist = PlaylistDetails(
                                                title = section.title.replace("★", "").trim(),
                                                subtitle = "CacheTune Curated Mix",
                                                artworkUrl = realSongs.firstOrNull()?.artworkUrl ?: "",
                                                songs = realSongs,
                                                isLoading = false
                                            )
                                        } else {
                                            val firstPl = section.songs.firstOrNull()
                                            activePlaylist = PlaylistDetails(
                                                title = section.title.replace("★", "").trim(),
                                                subtitle = "CacheTune Curated Mix",
                                                artworkUrl = firstPl?.artworkUrl ?: "",
                                                songs = emptyList(),
                                                isLoading = true
                                            )
                                            coroutineScope.launch {
                                                val tracks = if (firstPl != null) onLoadPlaylist?.invoke(firstPl) ?: emptyList() else emptyList()
                                                activePlaylist = PlaylistDetails(
                                                    title = section.title.replace("★", "").trim(),
                                                    subtitle = "CacheTune Curated Mix",
                                                    artworkUrl = firstPl?.artworkUrl ?: "",
                                                    songs = tracks,
                                                    isLoading = false
                                                )
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = "Next",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else {
                        // Horizontal Carousel Cards (Dancing on your own, New releases, Old School Romance, etc.)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(section.songs) { song ->
                                    Column(
                                        modifier = Modifier
                                            .width(152.dp)
                                            .clickable {
                                                if (song.isPlaylistCard) {
                                                    activePlaylist = PlaylistDetails(
                                                        title = song.title,
                                                        subtitle = song.artist,
                                                        artworkUrl = song.artworkUrl,
                                                        songs = emptyList(),
                                                        isLoading = true
                                                    )
                                                    coroutineScope.launch {
                                                        val tracks = onLoadPlaylist?.invoke(song) ?: emptyList()
                                                        activePlaylist = PlaylistDetails(
                                                            title = song.title,
                                                            subtitle = song.artist,
                                                            artworkUrl = song.artworkUrl,
                                                            songs = tracks,
                                                            isLoading = false
                                                        )
                                                    }
                                                } else {
                                                    onSongClick(song, section.songs)
                                                }
                                            }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(152.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF1E1E1E))
                                        ) {
                                            AsyncImage(
                                                model = song.artworkUrl,
                                                contentDescription = song.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )

                                            // Subtle top-left play icon
                                            Icon(
                                                imageVector = Icons.Rounded.PlayCircle,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.6f),
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(6.dp)
                                                    .size(18.dp)
                                            )

                                            // Bottom stylish title chip overlay
                                            if (!song.subtitleOverlay.isNullOrBlank()) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .align(Alignment.BottomCenter)
                                                        .background(
                                                            Brush.verticalGradient(
                                                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                                            )
                                                        )
                                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                                ) {
                                                    Text(
                                                        text = song.subtitleOverlay,
                                                        color = CacheTuneOrangeBright,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = song.title,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.artist,
                                            color = Color(0xFFAAAAAA),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // Floating Right Next Arrow Button
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 10.dp)
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF242424).copy(alpha = 0.9f))
                                    .clickable {
                                        val realSongs = section.songs.filter { !it.isPlaylistCard }
                                        if (realSongs.isNotEmpty()) {
                                            activePlaylist = PlaylistDetails(
                                                title = section.title.replace("★", "").trim(),
                                                subtitle = "CacheTune Curated Mix",
                                                artworkUrl = realSongs.firstOrNull()?.artworkUrl ?: "",
                                                songs = realSongs,
                                                isLoading = false
                                            )
                                        } else {
                                            val firstPl = section.songs.firstOrNull()
                                            activePlaylist = PlaylistDetails(
                                                title = section.title.replace("★", "").trim(),
                                                subtitle = "CacheTune Curated Mix",
                                                artworkUrl = firstPl?.artworkUrl ?: "",
                                                songs = emptyList(),
                                                isLoading = true
                                            )
                                            coroutineScope.launch {
                                                val tracks = if (firstPl != null) onLoadPlaylist?.invoke(firstPl) ?: emptyList() else emptyList()
                                                activePlaylist = PlaylistDetails(
                                                    title = section.title.replace("★", "").trim(),
                                                    subtitle = "CacheTune Curated Mix",
                                                    artworkUrl = firstPl?.artworkUrl ?: "",
                                                    songs = tracks,
                                                    isLoading = false
                                                )
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = "Next",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Interactive Playlist Details Sheet Overlay
        androidx.compose.animation.AnimatedVisibility(
            visible = activePlaylist != null,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut()
        ) {
            activePlaylist?.let { pl ->
                PlaylistDetailsSheet(
                    title = pl.title,
                    subtitle = pl.subtitle,
                    artworkUrl = pl.artworkUrl,
                    songs = pl.songs,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    isLoading = pl.isLoading,
                    onSongClick = onSongClick,
                    onPlayNext = onPlayNext,
                    onPlayAll = {
                        pl.songs.firstOrNull()?.let { onSongClick(it, pl.songs) }
                    },
                    onShuffle = {
                        val shuffled = pl.songs.shuffled()
                        shuffled.firstOrNull()?.let { onSongClick(it, shuffled) }
                    },
                    onDismiss = { activePlaylist = null }
                )
            }
        }
    }
}
