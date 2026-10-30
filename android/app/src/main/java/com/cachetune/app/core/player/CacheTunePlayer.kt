package com.cachetune.app.core.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.cachetune.app.core.downloader.PrecacheManager
import com.cachetune.app.core.downloader.StreamDownloader
import com.cachetune.app.core.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * High-Performance Audio Engine for Cache Tune.
 * 
 * Plays ALAC (Apple Lossless) and FLAC directly on the device using ExoPlayer.
 * Zero transcoding lag — playback begins in sub-second time.
 */
class CacheTunePlayer(
    private val context: Context,
    private val downloader: StreamDownloader,
    private val precacheManager: PrecacheManager
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
    }

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private var progressJob: Job? = null
    var onSongEnded: (() -> Unit)? = null

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                if (playing) {
                    startProgressTracking()
                } else {
                    stopProgressTracking()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _durationMs.value = exoPlayer.duration.coerceAtLeast(0L)
                } else if (playbackState == Player.STATE_ENDED) {
                    val endedCb = onSongEnded
                    if (endedCb != null) {
                        endedCb.invoke()
                    } else {
                        skipNext()
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("CacheTunePlayer", "ExoPlayer Error: ${error.errorCodeName} (code ${error.errorCode}): ${error.message}", error)
            }
        })
    }

    /**
     * Plays a song immediately. If it's already cached, plays instantly.
     * Otherwise, starts playing as soon as the first 256KB buffer arrives.
     */
    @OptIn(UnstableApi::class)
    fun playSong(song: Song) {
        _currentSong.value = song
        _durationMs.value = song.durationMs

        scope.launch {
            try {
                val cachedFile = downloader.getCachedFile(song.id)
                if (downloader.isSongCached(song.id)) {
                    playMediaUri(Uri.fromFile(cachedFile), song)
                } else {
                    val streamUrl = song.streamUrl
                    if (!streamUrl.isNullOrBlank()) {
                        playMediaUri(Uri.parse(streamUrl), song)
                    } else {
                        withContext(Dispatchers.Main) {
                            exoPlayer.stop()
                            _isPlaying.value = false
                        }
                    }
                }

                // Notify precache manager for upcoming songs
                try {
                    precacheManager.onQueueUpdated(_queue.value, _currentIndex.value)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun playMediaUri(uri: Uri, song: Song) {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(if (song.artworkUrl.isNotBlank()) Uri.parse(song.artworkUrl) else null)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.id)
            .setMediaMetadata(metadata)
            .build()

        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    /**
     * Seamlessly upgrades the currently playing track to the 24-bit Hi-Res FLAC stream,
     * maintaining the exact playback timestamp and state.
     */
    @OptIn(UnstableApi::class)
    fun upgradeStream(upgradedSong: Song) {
        val current = _currentSong.value
        if (current != null && current.id != upgradedSong.id && !current.title.equals(upgradedSong.title, ignoreCase = true)) return

        val wasPlaying = exoPlayer.isPlaying
        // If track was not actively playing (e.g. was waiting for initial download/decrypt),
        // or player was in idle/ended state, ALWAYS start cleanly from 0:00!
        val shouldResumePosition = wasPlaying && current?.streamUrl != null &&
                exoPlayer.playbackState != Player.STATE_ENDED &&
                exoPlayer.playbackState != Player.STATE_IDLE
        val currentPosition = if (shouldResumePosition) exoPlayer.currentPosition.coerceAtLeast(0L) else 0L

        _currentSong.value = upgradedSong
        if (upgradedSong.durationMs > 0) {
            _durationMs.value = upgradedSong.durationMs
        }

        // Also update current song in queue so queue has valid streamUrl
        val curQ = _queue.value.toMutableList()
        val curIdx = curQ.indexOfFirst { it.id == upgradedSong.id || it.title.equals(upgradedSong.title, ignoreCase = true) }
        if (curIdx in curQ.indices) {
            curQ[curIdx] = upgradedSong
            _queue.value = curQ
        }

        val streamUrl = upgradedSong.streamUrl
        if (!streamUrl.isNullOrBlank()) {
            val metadata = MediaMetadata.Builder()
                .setTitle(upgradedSong.title)
                .setArtist(upgradedSong.artist)
                .setAlbumTitle(upgradedSong.album)
                .setArtworkUri(if (upgradedSong.artworkUrl.isNotBlank()) Uri.parse(upgradedSong.artworkUrl) else null)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(streamUrl))
                .setMediaId(upgradedSong.id)
                .setMediaMetadata(metadata)
                .build()

            if (currentPosition > 0L) {
                exoPlayer.setMediaItem(mediaItem, currentPosition)
            } else {
                exoPlayer.setMediaItem(mediaItem, /* resetPosition= */ true)
            }
            exoPlayer.prepare()
            exoPlayer.play()
            _isPlaying.value = true
            android.util.Log.d("CacheTunePlayer", "Started/Upgraded stream to 24-bit FLAC: $streamUrl, playing at $currentPosition ms")
        }
    }

    fun setQueue(songs: List<Song>, startIndex: Int = 0) {
        _queue.value = songs
        _currentIndex.value = startIndex
        if (startIndex in songs.indices) {
            playSong(songs[startIndex])
        }
        precacheManager.onQueueUpdated(songs, startIndex)
    }

    /**
     * Inserts a song directly after the currently playing song in the queue.
     */
    fun insertPlayNext(song: Song) {
        val q = _queue.value.toMutableList()
        val curIdx = _currentIndex.value
        val insertIdx = if (curIdx in q.indices) curIdx + 1 else q.size
        q.add(insertIdx, song)
        _queue.value = q
        precacheManager.onQueueUpdated(q, _currentIndex.value)
    }

    /**
     * Reorders an item in the queue from fromIndex to toIndex.
     */
    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val q = _queue.value.toMutableList()
        if (fromIndex in q.indices && toIndex in q.indices && fromIndex != toIndex) {
            val currentSongId = _currentSong.value?.id
            val item = q.removeAt(fromIndex)
            q.add(toIndex, item)
            _queue.value = q
            val newCurIdx = q.indexOfFirst { it.id == currentSongId }
            if (newCurIdx >= 0) {
                _currentIndex.value = newCurIdx
            }
        }
    }


    /**
     * Updates the upcoming queue without restarting playback of the currently playing track.
     */
    fun updateQueueKeepCurrent(songs: List<Song>) {
        _queue.value = songs
        val current = _currentSong.value
        val idx = if (current != null) songs.indexOfFirst { it.id == current.id } else 0
        if (idx >= 0) {
            _currentIndex.value = idx
        }
        precacheManager.onQueueUpdated(songs, _currentIndex.value)
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _currentPositionMs.value = positionMs
    }

    fun skipNext() {
        val q = _queue.value
        val nextIdx = _currentIndex.value + 1
        if (nextIdx in q.indices) {
            _currentIndex.value = nextIdx
            playSong(q[nextIdx])
        }
    }

    fun skipPrev() {
        val q = _queue.value
        val prevIdx = _currentIndex.value - 1
        if (prevIdx in q.indices) {
            _currentIndex.value = prevIdx
            playSong(q[prevIdx])
        } else {
            seekTo(0L)
        }
    }

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = scope.launch {
            while (isActive) {
                _currentPositionMs.value = exoPlayer.currentPosition
                delay(200)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stopProgressTracking()
        exoPlayer.release()
    }
}
