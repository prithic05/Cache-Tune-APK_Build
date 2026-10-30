package com.cachetune.app.core.downloader

import com.cachetune.app.core.model.Song
import com.cachetune.app.core.search.AppleMusicSearchEngine
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * PrecacheManager ported from Cache Tune's download_manager.py.
 * 
 * Manages background pre-fetching of upcoming queue tracks so that
 * when the user transitions to the next track, it plays with ZERO delay (gapless).
 */
class PrecacheManager(
    private val downloader: StreamDownloader,
    private val searchEngine: AppleMusicSearchEngine,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private var currentPosition: Int = 0
    private var currentQueue: List<Song> = emptyList()

    /**
     * Updates playback state and triggers pre-caching for upcoming tracks.
     */
    fun onQueueUpdated(queue: List<Song>, position: Int) {
        currentQueue = queue
        currentPosition = position

        // Only precache the immediate next track (+1) while current track is playing
        val upcomingSongs = getUpcomingTracks(queue, position, window = 1)
        val priorityIds: Set<String> = upcomingSongs.map { it.id }.toSet()

        activeJobs.keys.toList().forEach { songId ->
            if (songId !in priorityIds) {
                activeJobs[songId]?.cancel()
                activeJobs.remove(songId)
            }
        }

        // Start precache for upcoming tracks
        upcomingSongs.forEach { upcomingSong ->
            if (!downloader.isSongCached(upcomingSong.id) && !activeJobs.containsKey(upcomingSong.id)) {
                startPrecacheTask(upcomingSong)
            }
        }
    }

    private fun getUpcomingTracks(queue: List<Song>, currentPos: Int, window: Int): List<Song> {
        val list = mutableListOf<Song>()
        for (i in 1..window) {
            val idx = currentPos + i
            if (idx in queue.indices) {
                list.add(queue[idx])
            }
        }
        return list
    }

    private fun startPrecacheTask(song: Song) {
        val job = scope.launch {
            try {
                // If stream URL is not present, resolve via Apple Music or Fallback
                val targetUrl = song.streamUrl ?: run {
                    val resolved = searchEngine.resolveAppleUrl(song.title, song.artist)
                    resolved?.streamUrl
                }

                if (!targetUrl.isNullOrBlank()) {
                    downloader.downloadSongStream(
                        song = song,
                        targetUrl = targetUrl,
                        onReadyToPlay = { /* Precached in background */ },
                        onProgress = { /* Optional progress logging */ }
                    )
                }
            } catch (e: CancellationException) {
                // Task cancelled due to skip/reorder
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                activeJobs.remove(song.id)
            }
        }
        activeJobs[song.id] = job
    }

    fun cancelAll() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }
}
