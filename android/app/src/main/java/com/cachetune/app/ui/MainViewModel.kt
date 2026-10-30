package com.cachetune.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cachetune.app.core.converter.FlacConverter
import com.cachetune.app.core.decryptor.NativeDecryptor
import com.cachetune.app.core.downloader.PrecacheManager
import com.cachetune.app.core.downloader.StreamDownloader
import com.cachetune.app.core.model.HomeSection
import com.cachetune.app.core.model.Song
import com.cachetune.app.core.network.CacheTuneBackendClient
import com.cachetune.app.core.player.CacheTunePlayer
import com.cachetune.app.core.recommendation.RecommendationEngine
import com.cachetune.app.core.search.AppleMusicSearchEngine
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DownloadDialogInfo(
    val title: String,
    val message: String,
    val isSuccess: Boolean = true,
    val isLoading: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("cachetune_config", Context.MODE_PRIVATE)

    // Core Modules
    val searchEngine = AppleMusicSearchEngine()
    val recommendationEngine = RecommendationEngine()
    val downloader = StreamDownloader(application)
    val precacheManager = PrecacheManager(downloader, searchEngine)
    val nativeDecryptor = NativeDecryptor(application)
    val player = CacheTunePlayer(application, downloader, precacheManager)
    val flacConverter = FlacConverter(application)
    val backendClient = CacheTuneBackendClient()

    // Active session ID on backend
    private var activeBackendSessionId: String? = null

    // UI States
    private val _homeSections = MutableStateFlow<List<HomeSection>>(emptyList())
    val homeSections: StateFlow<List<HomeSection>> = _homeSections.asStateFlow()

    val speedDialSongs = MutableStateFlow<List<Song>>(emptyList())
    val userName = MutableStateFlow(prefs.getString("user_name", "") ?: "")

    private val _rawSearchResults = MutableStateFlow<List<Song>>(emptyList())
    private val _searchResults = MutableStateFlow<List<Song>>(emptyList())
    val searchResults: StateFlow<List<Song>> = _searchResults.asStateFlow()

    val searchQuery = MutableStateFlow("")
    val searchFilter = MutableStateFlow("ALL") // "ALL", "APPLE", "YT"
    val isSearching = MutableStateFlow(false)

    val isPlayerExpanded = MutableStateFlow(false)
    val isLyricsVisible = MutableStateFlow(false)

    val exportStatusMessage = MutableStateFlow<String?>(null)
    val backendStatus = MutableStateFlow<String?>(null)
    val isTestingBackend = MutableStateFlow(false)
    val cloudDownloadMessage = MutableStateFlow<String?>(null)
    val processingStatus = MutableStateFlow("Ready")

    val downloadDialogState = MutableStateFlow<DownloadDialogInfo?>(null)
    private val activeDownloads = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Exposed Player States
    val queue: StateFlow<List<Song>> = player.queue
    val currentIndex: StateFlow<Int> = player.currentIndex

    // Configuration / Auth States (Stored Locally)
    val ytHeaders = MutableStateFlow(prefs.getString("yt_raw_headers", "") ?: "")
    val appleToken = MutableStateFlow(prefs.getString("apple_media_token", "") ?: "")
    val appleUsername = MutableStateFlow(prefs.getString("apple_username", "") ?: "")
    val applePassword = MutableStateFlow(prefs.getString("apple_password", "") ?: "")
    val storefront = MutableStateFlow(prefs.getString("storefront", "in") ?: "in")
    val wrapperHost = MutableStateFlow(prefs.getString("wrapper_host", "127.0.0.1:10020") ?: "127.0.0.1:10020")
    val backendUrl = MutableStateFlow(prefs.getString("backend_url", "http://192.168.1.2:8000") ?: "http://192.168.1.2:8000")

    init {
        // Load default YouTube headers from assets if not saved
        if (ytHeaders.value.isBlank()) {
            try {
                application.assets.open("browser.json").bufferedReader().use {
                    val content = it.readText()
                    ytHeaders.value = content
                    prefs.edit().putString("yt_raw_headers", content).apply()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Load default Apple Music config from assets if not saved
        if (appleToken.value.isBlank()) {
            try {
                application.assets.open("apple_music_config.json").bufferedReader().use {
                    val obj = JsonParser.parseString(it.readText()).asJsonObject
                    val token = obj.get("media_user_token")?.asString ?: ""
                    val stfront = obj.get("storefront")?.asString ?: "in"
                    val user = obj.get("username")?.asString ?: ""
                    appleToken.value = token
                    storefront.value = stfront
                    appleUsername.value = user
                    prefs.edit()
                        .putString("apple_media_token", token)
                        .putString("storefront", stfront)
                        .putString("apple_username", user)
                        .apply()
                    nativeDecryptor.saveCredentials(token, stfront)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        player.onSongEnded = {
            skipNext()
        }
        loadHomeRecommendations()
    }

    fun loadHomeRecommendations() {
        viewModelScope.launch {
            var sections: List<HomeSection>? = null
            // 1. Try fetching full 15-shelf feed from Docker backend first
            if (backendUrl.value.isNotBlank()) {
                sections = backendClient.getHomeFeed(backendUrl.value, ytHeaders.value)
            }
            // 2. If backend is offline or null, use on-device recommendationEngine
            if (sections.isNullOrEmpty()) {
                sections = recommendationEngine.getHomeSections(ytHeaders.value)
            }
            if (!sections.isNullOrEmpty()) {
                _homeSections.value = sections
                val speedDialCandidates = sections.firstOrNull()?.songs ?: emptyList()
                if (speedDialCandidates.isNotEmpty()) {
                    speedDialSongs.value = speedDialCandidates.take(18)
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
        if (query.length >= 2) {
            performSearch(query)
        }
    }

    fun setSearchFilter(filter: String) {
        searchFilter.value = filter
        applySearchFilter()
    }

    private fun applySearchFilter() {
        val raw = _rawSearchResults.value
        _searchResults.value = when (searchFilter.value) {
            "APPLE" -> raw.filter { it.isApple }
            "YT" -> raw.filter { !it.isApple }
            else -> raw
        }
    }

    fun performSearch(query: String) {
        viewModelScope.launch {
            isSearching.value = true
            try {
                val ytDeferred = async { recommendationEngine.searchSongs(query, ytHeaders.value) }
                val appleDeferred = async { searchEngine.searchSongs(query, storefront = storefront.value) }

                val ytResults = try { ytDeferred.await() } catch (_: Exception) { emptyList() }
                val appleResults = try { appleDeferred.await() } catch (_: Exception) { emptyList() }

                val combined = (appleResults + ytResults).distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }
                _rawSearchResults.value = if (combined.isNotEmpty()) combined else ytResults + appleResults
                applySearchFilter()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSearching.value = false
            }
        }
    }

    fun testBackendConnection() {
        viewModelScope.launch {
            isTestingBackend.value = true
            val (_, message) = backendClient.testConnectionDetailed(backendUrl.value)
            backendStatus.value = message
            isTestingBackend.value = false
        }
    }

    fun downloadCloudFlac(song: Song) {
        viewModelScope.launch {
            cloudDownloadMessage.value = "⚡ Triggering Docker Decryption & 24-bit FLAC conversion..."
            val isBackendActive = backendClient.isServerReachable(backendUrl.value)
            if (!isBackendActive) {
                cloudDownloadMessage.value = "❌ Docker/Cloud Engine offline at ${backendUrl.value}"
                return@launch
            }

            // Resolve Apple URL
            val primaryArtist = song.artist.split(",", "&", "•").firstOrNull()?.trim() ?: song.artist
            val resolved = searchEngine.resolveAppleUrl(song.title, primaryArtist, storefront.value)
            val resolvedAppleUrl = resolved?.appleMusicUrl ?: song.appleMusicUrl

            val session = backendClient.startSession(backendUrl.value, song, limit = 1, appleUrl = resolvedAppleUrl)
            if (session == null) {
                cloudDownloadMessage.value = "❌ Could not initialize cloud session"
                return@launch
            }

            val playRes = backendClient.playTrack(backendUrl.value, session.sessionId, 0, appleUrl = resolvedAppleUrl)
            if (playRes?.streamUrl != null) {
                cloudDownloadMessage.value = "⚡ Studio FLAC ready! Downloading to phone..."
                val downloaded = downloader.downloadSongStream(song, playRes.streamUrl, onReadyToPlay = {})
                flacConverter.exportToFlac(downloaded, song)
                cloudDownloadMessage.value = "✔ 24-bit Studio Master FLAC saved to Music/CacheTune!"
            } else if (playRes?.entryId != null) {
                cloudDownloadMessage.value = "⏳ Decrypting FairPlay & transcoding to FLAC on server..."
                val ready = backendClient.pollUntilReady(backendUrl.value, playRes.entryId, maxWaitSeconds = 180)
                if (ready?.streamUrl != null) {
                    val downloaded = downloader.downloadSongStream(song, ready.streamUrl, onReadyToPlay = {})
                    flacConverter.exportToFlac(downloaded, song)
                    cloudDownloadMessage.value = "✔ 24-bit Studio Master FLAC saved to Music/CacheTune!"
                } else {
                    cloudDownloadMessage.value = "❌ Cloud conversion failed: ${ready?.errorMsg ?: "Timeout"}"
                }
            } else {
                cloudDownloadMessage.value = "❌ Cloud conversion error: ${playRes?.errorMsg ?: "Unknown error"}"
            }
        }
    }

    fun playSongFromList(song: Song, list: List<Song>) {
        viewModelScope.launch {
            activeBackendSessionId = null
            if (list.size > 1) {
                val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                resolveAndPlay(list, idx)
            } else {
                playSingleSong(song)
            }
        }
    }

    fun playSingleSong(song: Song) {
        viewModelScope.launch {
            activeBackendSessionId = null
            val seedList = listOf(song)
            resolveAndPlay(seedList, 0)

            // Fetch authentic 50-track YouTube Music autoplay radio queue directly on device
            launch(Dispatchers.IO) {
                try {
                    val radioQueue = recommendationEngine.getRadioQueue(song, ytHeaders.value, limit = 50)
                    if (radioQueue.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            val current = player.currentSong.value ?: song
                            val upcoming = radioQueue.filter { it.id != song.id && !it.title.equals(song.title, ignoreCase = true) }
                            val fullQueue = (listOf(current) + upcoming).distinctBy { it.id }
                            player.updateQueueKeepCurrent(fullQueue)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun playQueueItem(index: Int) {
        viewModelScope.launch {
            val currentQueue = player.queue.value
            if (index in currentQueue.indices) {
                resolveAndPlay(currentQueue, index)
            }
        }
    }

    fun skipNext() {
        val nextIdx = player.currentIndex.value + 1
        val currentQueue = player.queue.value
        if (nextIdx in currentQueue.indices) {
            playQueueItem(nextIdx)
        }
    }

    fun skipPrev() {
        val prevIdx = player.currentIndex.value - 1
        val currentQueue = player.queue.value
        if (prevIdx in currentQueue.indices) {
            playQueueItem(prevIdx)
        }
    }

    suspend fun loadPlaylistTracks(playlistSong: Song): List<Song> {
        return recommendationEngine.getPlaylistTracks(
            playlistId = playlistSong.id,
            playlistTitle = playlistSong.title,
            authHeadersJson = ytHeaders.value
        )
    }

    private fun resolveAndPlay(queue: List<Song>, initialIndex: Int) {
        viewModelScope.launch {
            val targetSong = queue.getOrNull(initialIndex) ?: return@launch
            if (targetSong.isPlaylistCard) {
                // Safeguard: Never play a playlist container as a song
                return@launch
            }

            // Step 1: Check On-Device Offline FLAC Cache OR existing pre-cached streamUrl
            // Never play low-quality 30s previews — play 24-bit studio FLAC only
            var immediateStreamUrl: String? = null
            if (downloader.isSongCached(targetSong.id)) {
                val cachedFile = downloader.getCachedFile(targetSong.id)
                immediateStreamUrl = cachedFile.absolutePath
            } else if (!targetSong.streamUrl.isNullOrBlank()) {
                immediateStreamUrl = targetSong.streamUrl
            }

            val seedSong = targetSong.copy(
                streamUrl = immediateStreamUrl,
                codec = if (immediateStreamUrl != null) (targetSong.bitrate?.let { "$it kbps • ${targetSong.sampleRate / 1000.0} kHz" } ?: "2296 kbps • 96.0 kHz") else "Resolving...",
                phase = if (immediateStreamUrl != null) "Ready" else "Resolving..."
            )
            processingStatus.value = if (immediateStreamUrl != null) "Ready" else "Resolving..."

            val initialQueue = queue.toMutableList()
            if (initialIndex in initialQueue.indices) {
                initialQueue[initialIndex] = seedSong
            }
            player.setQueue(initialQueue, initialIndex)

            // Step 2: Background Docker Decryption & 24-bit Hi-Res Lossless Upgrade
            launch(Dispatchers.IO) {
                try {
                    val isBackendActive = backendClient.isServerReachable(backendUrl.value)
                    if (!isBackendActive) return@launch

                    // Sync Apple credentials from Config
                    if (appleToken.value.isNotBlank()) {
                        backendClient.saveAppleCredentials(
                            baseUrl = backendUrl.value,
                            username = appleUsername.value,
                            pass = "",
                            mediaUserToken = appleToken.value,
                            storefront = storefront.value
                        )
                    }

                    // Resolve Apple Music URL for FairPlay ALAC matching
                    val primaryArtist = targetSong.artist.split(",", "&", "•").firstOrNull()?.trim() ?: targetSong.artist
                    val resolved = searchEngine.resolveAppleUrl(targetSong.title, primaryArtist, storefront.value)
                    val resolvedAppleUrl = resolved?.appleMusicUrl ?: targetSong.appleMusicUrl

                    val existingSessionId = activeBackendSessionId
                    val sessionId: String
                    val playPosition: Int

                    if (existingSessionId != null && initialIndex > 0) {
                        // Reuse active session to avoid resetting queue or pre-cached downloads!
                        sessionId = existingSessionId
                        playPosition = initialIndex
                    } else {
                        // Start fresh backend session for new play/search
                        val session = backendClient.startSession(backendUrl.value, targetSong, limit = 50, appleUrl = resolvedAppleUrl)
                        if (session == null) return@launch
                        sessionId = session.sessionId
                        activeBackendSessionId = sessionId
                        playPosition = 0

                        // Populate the player queue with authentic 50-track autoplay queue from YouTube Music
                        if (session.queue.isNotEmpty() && player.queue.value.size <= 1) {
                            val fullQueue = session.queue.toMutableList()
                            val cur = player.currentSong.value ?: seedSong
                            fullQueue[0] = cur
                            withContext(Dispatchers.Main) {
                                player.updateQueueKeepCurrent(fullQueue)
                            }
                        }
                    }

                    // If track is already cached/playing locally or has streamUrl, just notify backend and precache next track
                    if (immediateStreamUrl != null) {
                        backendClient.playTrack(backendUrl.value, sessionId, playPosition, appleUrl = resolvedAppleUrl)
                        precacheNextTrack(initialIndex, sessionId)
                        return@launch
                    }

                    val playRes = backendClient.playTrack(backendUrl.value, sessionId, playPosition, appleUrl = resolvedAppleUrl)

                    var flacUrl: String? = null
                    var bitDepth = 24
                    var sampleRate = 48000
                    var bitrate: Int? = null

                    if (playRes?.streamUrl != null) {
                        flacUrl = playRes.streamUrl
                        bitDepth = playRes.bitDepth ?: 24
                        sampleRate = playRes.sampleRate ?: 48000
                        bitrate = playRes.bitrate
                        processingStatus.value = "Ready"
                    } else if (playRes?.entryId != null) {
                        processingStatus.value = playRes.phase ?: "Downloading..."
                        val readyRes = backendClient.pollUntilReady(
                            baseUrl = backendUrl.value,
                            entryId = playRes.entryId,
                            maxWaitSeconds = 300,
                            onProgressUpdate = { phase, _ ->
                                processingStatus.value = phase
                            }
                        )
                        flacUrl = readyRes?.streamUrl
                        bitDepth = readyRes?.bitDepth ?: 24
                        sampleRate = readyRes?.sampleRate ?: 48000
                        bitrate = readyRes?.bitrate
                    }

                    if (!flacUrl.isNullOrBlank()) {
                        processingStatus.value = "Ready"
                        val dynamicCodec = if (bitrate != null && bitrate > 0) {
                            "$bitrate kbps • ${sampleRate / 1000.0} kHz"
                        } else {
                            "${sampleRate / 1000.0} kHz"
                        }
                        val upgradedSong = targetSong.copy(
                            streamUrl = flacUrl,
                            appleMusicUrl = resolvedAppleUrl,
                            bitDepth = bitDepth,
                            sampleRate = sampleRate,
                            bitrate = bitrate,
                            codec = dynamicCodec,
                            isLossless = true,
                            isHiRes = true,
                            phase = "ready"
                        )
                        withContext(Dispatchers.Main) {
                            val cur = player.currentSong.value
                            if (cur == null || cur.id == targetSong.id || cur.title.equals(targetSong.title, ignoreCase = true)) {
                                player.upgradeStream(upgradedSong)
                            }
                        }

                        // Track 0 is now playing — start precaching Track 1 in background
                        precacheNextTrack(initialIndex, sessionId)

                        // Download the converted FLAC file directly back to mobile storage
                        launch(Dispatchers.IO) {
                            try {
                                val downloadedFile = downloader.downloadSongStream(
                                    targetSong,
                                    flacUrl,
                                    onReadyToPlay = {}
                                )
                                if (!flacConverter.isSongExported(targetSong)) {
                                    val exportResult = flacConverter.exportToFlac(downloadedFile, targetSong)
                                    if (exportResult.isSuccess) {
                                        android.util.Log.d("MainViewModel", "FLAC stream saved to device storage: ${exportResult.getOrNull()?.absolutePath}")
                                    } else {
                                        android.util.Log.e("MainViewModel", "FLAC export error: ${exportResult.exceptionOrNull()?.message}")
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("MainViewModel", "Background download to phone storage: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun precacheNextTrack(currentIndex: Int, sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val nextIndex = currentIndex + 1
            val currentQueue = player.queue.value
            val nextSong = currentQueue.getOrNull(nextIndex) ?: return@launch
            if (nextSong.isPlaylistCard) return@launch

            // If already cached locally or already has a valid streamUrl, skip precache
            if (!nextSong.streamUrl.isNullOrBlank() || downloader.isSongCached(nextSong.id)) {
                android.util.Log.d("MainViewModel", "Next track '${nextSong.title}' already pre-cached.")
                return@launch
            }

            try {
                android.util.Log.d("MainViewModel", "Pre-caching upcoming track [pos $nextIndex]: '${nextSong.title}'")

                // Resolve Apple Music URL
                val primaryArtist = nextSong.artist.split(",", "&", "•").firstOrNull()?.trim() ?: nextSong.artist
                val resolved = searchEngine.resolveAppleUrl(nextSong.title, primaryArtist, storefront.value)
                val resolvedNextAppleUrl = resolved?.appleMusicUrl ?: nextSong.appleMusicUrl

                // Trigger backend pre-cache for nextIndex
                val playRes = backendClient.playTrack(backendUrl.value, sessionId, nextIndex, appleUrl = resolvedNextAppleUrl)
                var flacUrl = playRes?.streamUrl
                var bitDepth = playRes?.bitDepth ?: 24
                var sampleRate = playRes?.sampleRate ?: 48000
                var bitrate = playRes?.bitrate

                // If not ready yet, poll until completed
                if (flacUrl.isNullOrBlank() && playRes?.entryId != null) {
                    val readyRes = backendClient.pollUntilReady(
                        baseUrl = backendUrl.value,
                        entryId = playRes.entryId,
                        maxWaitSeconds = 300
                    )
                    flacUrl = readyRes?.streamUrl
                    bitDepth = readyRes?.bitDepth ?: 24
                    sampleRate = readyRes?.sampleRate ?: 48000
                    bitrate = readyRes?.bitrate
                }

                if (!flacUrl.isNullOrBlank()) {
                    android.util.Log.d("MainViewModel", "Pre-cache READY for next track: '${nextSong.title}' -> $flacUrl")
                    val dynamicCodec = if (bitrate != null && bitrate > 0) {
                        "$bitrate kbps • ${sampleRate / 1000.0} kHz"
                    } else {
                        "${sampleRate / 1000.0} kHz"
                    }
                    val upgradedNextSong = nextSong.copy(
                        streamUrl = flacUrl,
                        appleMusicUrl = resolvedNextAppleUrl,
                        bitDepth = bitDepth,
                        sampleRate = sampleRate,
                        bitrate = bitrate,
                        codec = dynamicCodec,
                        isLossless = true,
                        isHiRes = true,
                        phase = "ready"
                    )

                    // Update in-place in player.queue
                    val latestQ = player.queue.value.toMutableList()
                    val targetIdx = latestQ.indexOfFirst { it.id == nextSong.id || it.title.equals(nextSong.title, ignoreCase = true) }
                    if (targetIdx in latestQ.indices) {
                        latestQ[targetIdx] = upgradedNextSong
                        withContext(Dispatchers.Main) {
                            player.updateQueueKeepCurrent(latestQ)
                        }
                    }

                    // Also download the FLAC into phone storage in background
                    try {
                        val downloadedFile = downloader.downloadSongStream(nextSong, flacUrl, onReadyToPlay = {})
                        if (!flacConverter.isSongExported(nextSong)) { flacConverter.exportToFlac(downloadedFile, nextSong) }
                    } catch (e: Exception) {
                        android.util.Log.e("MainViewModel", "Failed to cache next track to phone: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Pre-caching error for next track: ${e.message}")
            }
        }
    }


    fun insertPlayNext(song: Song) {
        if (player.queue.value.isEmpty()) {
            playSingleSong(song)
        } else {
            player.insertPlayNext(song)
            cloudDownloadMessage.value = "Playing next: ${song.title}"
        }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        player.reorderQueue(fromIndex, toIndex)
    }

    fun getDownloadedSongs(): List<Song> {
        return flacConverter.getDownloadedSongs()
    }

    fun downloadSongWithDialog(song: Song) {
        if (activeDownloads.contains(song.id)) {
            downloadDialogState.value = DownloadDialogInfo(
                title = "Download in Progress",
                message = "'${song.title}' is currently downloading. Please wait.",
                isSuccess = true,
                isLoading = true
            )
            return
        }

        if (flacConverter.isSongExported(song)) {
            downloadDialogState.value = DownloadDialogInfo(
                title = "Already Downloaded",
                message = "'${song.title}' is already saved in your Music/CacheTune folder.",
                isSuccess = true,
                isLoading = false
            )
            return
        }

        activeDownloads.add(song.id)

        // Check if cached locally in internal player cache
        val cachedFile = downloader.getCachedFile(song.id)
        if (cachedFile.exists() && cachedFile.length() > 50_000) {
            downloadDialogState.value = DownloadDialogInfo(
                title = "Saving Track",
                message = "Exporting '${song.title}' to Music/CacheTune...",
                isSuccess = true,
                isLoading = true
            )
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val result = flacConverter.exportToFlac(cachedFile, song)
                    withContext(Dispatchers.Main) {
                        if (result.isSuccess) {
                            downloadDialogState.value = DownloadDialogInfo(
                                title = "Download Complete",
                                message = "'${song.title}' successfully saved to Music/CacheTune!",
                                isSuccess = true,
                                isLoading = false
                            )
                        } else {
                            downloadDialogState.value = DownloadDialogInfo(
                                title = "Save Failed",
                                message = "Could not export audio: ${result.exceptionOrNull()?.message}",
                                isSuccess = false,
                                isLoading = false
                            )
                        }
                    }
                } finally {
                    activeDownloads.remove(song.id)
                }
            }
            return
        }

        // Not yet cached — stream/download and save
        downloadDialogState.value = DownloadDialogInfo(
            title = "Downloading",
            message = "Downloading '${song.title}' in lossless 24-bit FLAC...",
            isSuccess = true,
            isLoading = true
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var downloadUrl = song.streamUrl
                if (downloadUrl.isNullOrBlank()) {
                    downloadUrl = recommendationEngine.resolveStreamUrl(song.id)
                }
                if (!downloadUrl.isNullOrBlank()) {
                    val downloadedFile = downloader.downloadSongStream(song, downloadUrl, onReadyToPlay = {})
                    val result = flacConverter.exportToFlac(downloadedFile, song)
                    withContext(Dispatchers.Main) {
                        if (result.isSuccess) {
                            downloadDialogState.value = DownloadDialogInfo(
                                title = "Download Complete",
                                message = "'${song.title}' successfully downloaded to Music/CacheTune!",
                                isSuccess = true,
                                isLoading = false
                            )
                        } else {
                            downloadDialogState.value = DownloadDialogInfo(
                                title = "Download Failed",
                                message = result.exceptionOrNull()?.message ?: "Save error",
                                isSuccess = false,
                                isLoading = false
                            )
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        downloadDialogState.value = DownloadDialogInfo(
                            title = "Download Failed",
                            message = "Could not resolve audio stream for '${song.title}'",
                            isSuccess = false,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadDialogState.value = DownloadDialogInfo(
                        title = "Download Error",
                        message = e.message ?: "Download encountered an error",
                        isSuccess = false,
                        isLoading = false
                    )
                }
            } finally {
                activeDownloads.remove(song.id)
            }
        }
    }

    fun exportCurrentSongToFlac() {
        val song = player.currentSong.value ?: return
        downloadSongWithDialog(song)
    }

    fun saveYtHeaders(headers: String) {
        ytHeaders.value = headers
        prefs.edit().putString("yt_raw_headers", headers).apply()
        loadHomeRecommendations()
    }

    fun saveAppleConfig(username: String, pass: String, token: String, stfront: String, host: String) {
        appleUsername.value = username
        applePassword.value = pass
        appleToken.value = token
        storefront.value = stfront
        wrapperHost.value = host

        prefs.edit()
            .putString("apple_username", username)
            .putString("apple_password", pass)
            .putString("apple_media_token", token)
            .putString("storefront", stfront)
            .putString("wrapper_host", host)
            .apply()

        nativeDecryptor.saveCredentials(token, stfront)

        // Automatically sync to local Docker backend
        viewModelScope.launch {
            backendClient.saveAppleCredentials(
                baseUrl = backendUrl.value,
                username = username,
                pass = pass,
                mediaUserToken = token,
                storefront = stfront
            )
        }
    }

    fun saveBackendUrl(url: String) {
        backendUrl.value = url
        prefs.edit().putString("backend_url", url).apply()
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
