package com.cachetune.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cachetune.app.ui.MainViewModel
import com.cachetune.app.ui.components.MiniPlayer
import com.cachetune.app.ui.screens.*
import com.cachetune.app.ui.theme.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CacheTuneTheme {
                var selectedTab by remember { mutableIntStateOf(0) }

                // Request storage permissions on startup so FLAC downloads save directly to Music/CacheTune
                val storagePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(
                        android.Manifest.permission.READ_MEDIA_AUDIO,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                } else {
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { /* permissions handled */ }

                val currentSong by viewModel.player.currentSong.collectAsState()
                val isPlaying by viewModel.player.isPlaying.collectAsState()
                val currentPositionMs by viewModel.player.currentPositionMs.collectAsState()
                val durationMs by viewModel.player.durationMs.collectAsState()

                val homeSections by viewModel.homeSections.collectAsState()
                val speedDialSongs by viewModel.speedDialSongs.collectAsState()
                val userName by viewModel.userName.collectAsState()

                val searchResults by viewModel.searchResults.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                val searchFilter by viewModel.searchFilter.collectAsState()
                val isSearching by viewModel.isSearching.collectAsState()

                val ytHeaders by viewModel.ytHeaders.collectAsState()
                val appleToken by viewModel.appleToken.collectAsState()
                val appleUsername by viewModel.appleUsername.collectAsState()
                val applePassword by viewModel.applePassword.collectAsState()
                val storefront by viewModel.storefront.collectAsState()
                val wrapperHost by viewModel.wrapperHost.collectAsState()
                val backendUrl by viewModel.backendUrl.collectAsState()
                val backendStatus by viewModel.backendStatus.collectAsState()
                val isTestingBackend by viewModel.isTestingBackend.collectAsState()

                val queue by viewModel.queue.collectAsState()
                val currentIndex by viewModel.currentIndex.collectAsState()

                val isPlayerExpanded by viewModel.isPlayerExpanded.collectAsState()
                val isLyricsVisible by viewModel.isLyricsVisible.collectAsState()
                val exportStatusMessage by viewModel.exportStatusMessage.collectAsState()
                val cloudDownloadMessage by viewModel.cloudDownloadMessage.collectAsState()
                val processingStatus by viewModel.processingStatus.collectAsState()
                val downloadDialogState by viewModel.downloadDialogState.collectAsState()

                val progress = remember(currentPositionMs, durationMs) {
                    if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                }

                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(exportStatusMessage) {
                    exportStatusMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.exportStatusMessage.value = null
                    }
                }

                LaunchedEffect(cloudDownloadMessage) {
                    cloudDownloadMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.cloudDownloadMessage.value = null
                    }
                }

                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF030303))) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            Column {
                                // Floating YouTube Music MiniPlayer Pill
                                AnimatedVisibility(
                                    visible = currentSong != null && !isPlayerExpanded,
                                    enter = slideInVertically(
                                        initialOffsetY = { it },
                                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 350f)
                                    ) + fadeIn(),
                                    exit = slideOutVertically(
                                        targetOffsetY = { it },
                                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 350f)
                                    ) + fadeOut()
                                ) {
                                    MiniPlayer(
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                        progress = progress,
                                        onPlayPauseClick = { viewModel.player.togglePlayPause() },
                                        onSkipClick = { viewModel.skipNext() },
                                        onExpandClick = { viewModel.isPlayerExpanded.value = true }
                                    )
                                }

                                // 4-Tab Navigation Bar with signature CacheTune warm brand orange
                                NavigationBar(
                                    containerColor = Color(0xFF0F0F0F),
                                    tonalElevation = 0.dp
                                ) {
                                    // 1. Home
                                    NavigationBarItem(
                                        selected = selectedTab == 0,
                                        onClick = { selectedTab = 0 },
                                        icon = { Icon(Icons.Rounded.Home, contentDescription = "Home") },
                                        label = { Text("Home", fontSize = 11.sp) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CacheTuneOrange,
                                            selectedTextColor = CacheTuneOrange,
                                            indicatorColor = Color.Transparent,
                                            unselectedIconColor = Color(0xFF888888),
                                            unselectedTextColor = Color(0xFF888888)
                                        )
                                    )
                                    // 2. Search
                                    NavigationBarItem(
                                        selected = selectedTab == 1,
                                        onClick = { selectedTab = 1 },
                                        icon = { Icon(Icons.Rounded.Search, contentDescription = "Search") },
                                        label = { Text("Search", fontSize = 11.sp) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CacheTuneOrange,
                                            selectedTextColor = CacheTuneOrange,
                                            indicatorColor = Color.Transparent,
                                            unselectedIconColor = Color(0xFF888888),
                                            unselectedTextColor = Color(0xFF888888)
                                        )
                                    )
                                    // 3. Library
                                    NavigationBarItem(
                                        selected = selectedTab == 2,
                                        onClick = { selectedTab = 2 },
                                        icon = { Icon(Icons.Rounded.VideoLibrary, contentDescription = "Library") },
                                        label = { Text("Library", fontSize = 11.sp) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CacheTuneOrange,
                                            selectedTextColor = CacheTuneOrange,
                                            indicatorColor = Color.Transparent,
                                            unselectedIconColor = Color(0xFF888888),
                                            unselectedTextColor = Color(0xFF888888)
                                        )
                                    )
                                    // 4. Config
                                    NavigationBarItem(
                                        selected = selectedTab == 3,
                                        onClick = { selectedTab = 3 },
                                        icon = { Icon(Icons.Rounded.Settings, contentDescription = "Config") },
                                        label = { Text("Config", fontSize = 11.sp) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CacheTuneOrange,
                                            selectedTextColor = CacheTuneOrange,
                                            indicatorColor = Color.Transparent,
                                            unselectedIconColor = Color(0xFF888888),
                                            unselectedTextColor = Color(0xFF888888)
                                        )
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            when (selectedTab) {
                                0 -> HomeScreen(
                                    homeSections = homeSections,
                                    speedDialSongs = speedDialSongs,
                                    userName = userName,
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                    onSongClick = { song, list -> viewModel.playSongFromList(song, list) },
                                    onPlayNext = { song -> viewModel.insertPlayNext(song) },
                                    onLoadPlaylist = { playlistSong -> viewModel.loadPlaylistTracks(playlistSong) }
                                )
                                1 -> SearchScreen(
                                    searchQuery = searchQuery,
                                    onQueryChange = { viewModel.onSearchQueryChanged(it) },
                                    onSearch = { viewModel.performSearch(it) },
                                    searchResults = searchResults,
                                    isSearching = isSearching,
                                    searchFilter = searchFilter,
                                    onFilterChange = { viewModel.setSearchFilter(it) },
                                    onSongClick = { song -> viewModel.playSingleSong(song) },
                                    onPlayNext = { song -> viewModel.insertPlayNext(song) },
                                    onCloudDownload = { song -> viewModel.downloadCloudFlac(song) }
                                )
                                2 -> LibraryScreen(
                                    onSongClick = { song -> viewModel.playSingleSong(song) },
                                    onPlayNext = { song -> viewModel.insertPlayNext(song) },
                                    onGetDownloads = { viewModel.getDownloadedSongs() }
                                )
                                3 -> ConfigScreen(
                                    currentYtHeaders = ytHeaders,
                                    currentAppleToken = appleToken,
                                    currentAppleUsername = appleUsername,
                                    currentApplePassword = applePassword,
                                    currentStorefront = storefront,
                                    currentWrapperHost = wrapperHost,
                                    currentBackendUrl = backendUrl,
                                    backendStatus = backendStatus,
                                    isTestingBackend = isTestingBackend,
                                    onTestBackend = { viewModel.testBackendConnection() },
                                    onSaveYtHeaders = { viewModel.saveYtHeaders(it) },
                                    onSaveAppleConfig = { u, p, t, s, h -> viewModel.saveAppleConfig(u, p, t, s, h) },
                                    onSaveBackendUrl = { viewModel.saveBackendUrl(it) }
                                )
                            }
                        }
                    }

                    // Full-Screen Lossless Player Modal with Spring Physics Transition
                    AnimatedVisibility(
                        visible = isPlayerExpanded,
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f)
                        ) + fadeIn(),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f)
                        ) + fadeOut()
                    ) {
                        PlayerScreen(
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            currentPositionMs = currentPositionMs,
                            durationMs = durationMs,
                            queue = queue,
                            currentIndex = currentIndex,
                            onPlayPauseClick = { viewModel.player.togglePlayPause() },
                            onSkipNext = { viewModel.skipNext() },
                            onSkipPrev = { viewModel.skipPrev() },
                            onSeek = { viewModel.player.seekTo(it) },
                            onDismiss = { viewModel.isPlayerExpanded.value = false },
                            onLyricsClick = { viewModel.isLyricsVisible.value = true },
                            onExportFlacClick = {
                                permissionLauncher.launch(storagePermissions)
                                viewModel.exportCurrentSongToFlac()
                            },
                            statusPhase = processingStatus,
                            onQueueItemClick = { viewModel.playQueueItem(it) },
                            onReorderQueue = { from, to -> viewModel.reorderQueue(from, to) }
                        )
                    }

                    // Synced Karaoke Lyrics Overlay Modal
                    AnimatedVisibility(
                        visible = isLyricsVisible,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        LyricsScreen(
                            currentSong = currentSong,
                            currentPositionMs = currentPositionMs,
                            onSeek = { viewModel.player.seekTo(it) },
                            onClose = { viewModel.isLyricsVisible.value = false }
                        )
                    }

                    // Modal Dialog for Download Feedback (Prevent duplicates, show Downloading / Already Downloaded)
                    downloadDialogState?.let { dialogInfo ->
                        AlertDialog(
                            onDismissRequest = {
                                if (!dialogInfo.isLoading) {
                                    viewModel.downloadDialogState.value = null
                                }
                            },
                            containerColor = Color(0xFF14151E),
                            titleContentColor = Color.White,
                            textContentColor = Color(0xFFCCCCCC),
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (dialogInfo.isLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            color = CacheTuneOrange,
                                            strokeWidth = 2.5.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (dialogInfo.isSuccess) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                                            contentDescription = null,
                                            tint = if (dialogInfo.isSuccess) CacheTuneOrange else Color(0xFFEF4444),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Text(
                                        text = dialogInfo.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                }
                            },
                            text = {
                                Text(
                                    text = dialogInfo.message,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                )
                            },
                            confirmButton = {
                                if (!dialogInfo.isLoading) {
                                    TextButton(
                                        onClick = { viewModel.downloadDialogState.value = null },
                                        colors = ButtonDefaults.textButtonColors(contentColor = CacheTuneOrangeBright)
                                    ) {
                                        Text("OK", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
