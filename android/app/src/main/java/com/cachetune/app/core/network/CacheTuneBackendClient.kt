package com.cachetune.app.core.network

import com.cachetune.app.core.model.HomeSection
import com.cachetune.app.core.model.Song
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Communicates with the Cache Tune Python FastAPI backend.
 * Provides access to the full ALAC/FLAC download, wrapper decryption,
 * and bit-depth / sample-rate resolution pipeline.
 */
class CacheTuneBackendClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    data class SessionResult(
        val sessionId: String,
        val queue: List<Song>
    )

    data class PlayResult(
        val streamUrl: String?,
        val cacheStatus: String,
        val bitDepth: Int?,
        val sampleRate: Int?,
        val bitrate: Int? = null,
        val phase: String? = null,
        val entryId: String?,
        val errorMsg: String?
    )

    /**
     * Checks if the Cache Tune backend server is reachable.
     */
    suspend fun isServerReachable(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.isBlank()) return@withContext false
        try {
            val req = Request.Builder()
                .url("$cleanUrl/api/health")
                .header("X-Client", "CacheTune-Android")
                .get()
                .build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) return@withContext true
            }
        } catch (_: Exception) {}

        try {
            val req = Request.Builder()
                .url("$cleanUrl/")
                .header("X-Client", "CacheTune-Android")
                .get()
                .build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) return@withContext true
            }
        } catch (_: Exception) {}

        try {
            val req = Request.Builder()
                .url("$cleanUrl/health")
                .header("X-Client", "CacheTune-Android")
                .get()
                .build()
            client.newCall(req).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Detailed latency & status check for Settings view.
     */
    suspend fun testConnectionDetailed(baseUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.isBlank()) return@withContext Pair(false, "URL cannot be empty")
        val startTime = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url("$cleanUrl/api/health")
                .header("X-Client", "CacheTune-Android")
                .get()
                .build()
            client.newCall(req).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    return@withContext Pair(true, "⚡ ONLINE — ${latency}ms latency (Docker/Laptop Ready)")
                }
            }
        } catch (_: Exception) {}

        try {
            val req = Request.Builder()
                .url("$cleanUrl/")
                .header("X-Client", "CacheTune-Android")
                .get()
                .build()
            client.newCall(req).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    return@withContext Pair(true, "⚡ ONLINE — ${latency}ms latency (Root endpoint ready)")
                }
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "❌ OFFLINE: ${e.message ?: "Connection refused"}")
        }

        Pair(false, "❌ Server returned non-200 response")
    }

    /**
     * Starts a session on the backend for the given seed song and fetches the Up Next queue.
     */
    suspend fun startSession(
        baseUrl: String,
        seedSong: Song,
        limit: Int = 50,
        appleUrl: String? = null
    ): SessionResult? = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trimEnd('/')
        val payload = JsonObject().apply {
            addProperty("video_id", seedSong.id)
            addProperty("limit", limit)
            if (!appleUrl.isNullOrBlank()) {
                addProperty("apple_url", appleUrl)
            }
            val songObj = JsonObject().apply {
                addProperty("title", seedSong.title)
                addProperty("artist", seedSong.artist)
                addProperty("thumbnail_url", seedSong.artworkUrl)
                if (!appleUrl.isNullOrBlank()) {
                    addProperty("apple_url", appleUrl)
                }
            }
            add("song", songObj)
        }

        val request = Request.Builder()
            .url("$cleanUrl/api/session/start")
            .header("X-Client", "CacheTune-Android")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val root = JsonParser.parseString(body).asJsonObject
                val sessionId = root.get("session_id")?.asString ?: return@withContext null
                val queueArray = root.getAsJsonArray("queue") ?: return@withContext null

                val queueList = mutableListOf<Song>()
                for (elem in queueArray) {
                    val item = elem.asJsonObject
                    val vid = item.get("video_id")?.asString ?: ""
                    val title = item.get("title")?.asString ?: ""
                    val artist = item.get("artist")?.asString ?: ""
                    val thumb = item.get("thumbnail_url")?.asString ?: ""
                    val status = item.get("status")?.asString ?: "pending"

                    queueList.add(
                        Song(
                            id = if (vid.isNotBlank()) vid else seedSong.id,
                            title = title,
                            artist = artist,
                            artworkUrl = thumb,
                            durationMs = 210000L,
                            status = status,
                            isLossless = true,
                            codec = "FLAC 24-bit/48.0kHz",
                            bitDepth = 24,
                            sampleRate = 48000
                        )
                    )
                }
                SessionResult(sessionId = sessionId, queue = queueList)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Calls /api/play to start downloading and converting the selected track to FLAC.
     */
    suspend fun playTrack(
        baseUrl: String,
        sessionId: String,
        position: Int,
        appleUrl: String? = null
    ): PlayResult? = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trimEnd('/')
        val payload = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("position", position)
            if (!appleUrl.isNullOrBlank()) {
                addProperty("apple_url", appleUrl)
            }
        }

        val request = Request.Builder()
            .url("$cleanUrl/api/play")
            .header("X-Client", "CacheTune-Android")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val root = JsonParser.parseString(body).asJsonObject

                val streamUrl = root.get("stream_url")?.takeIf { !it.isJsonNull }?.asString?.let {
                    if (it.startsWith("/")) "$cleanUrl$it" else it
                }
                val cacheStatus = root.get("cache_status")?.takeIf { !it.isJsonNull }?.asString ?: "resolving"
                val bitDepth = root.get("bit_depth")?.takeIf { !it.isJsonNull }?.asInt
                val sampleRate = root.get("sample_rate")?.takeIf { !it.isJsonNull }?.asInt
                val bitrate = root.get("bitrate")?.takeIf { !it.isJsonNull }?.asInt
                val phase = root.get("phase")?.takeIf { !it.isJsonNull }?.asString
                val entryId = root.get("entry_id")?.takeIf { !it.isJsonNull }?.asString
                val errorMsg = root.get("error_msg")?.takeIf { !it.isJsonNull }?.asString

                PlayResult(
                    streamUrl = streamUrl,
                    cacheStatus = cacheStatus,
                    bitDepth = bitDepth,
                    sampleRate = sampleRate,
                    bitrate = bitrate,
                    phase = phase,
                    entryId = entryId,
                    errorMsg = errorMsg
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Polls the entry status until the FLAC file is cached and ready to play.
     */
    suspend fun pollUntilReady(
        baseUrl: String,
        entryId: String,
        maxWaitSeconds: Int = 180,
        onProgressUpdate: ((phase: String, progress: Float) -> Unit)? = null
    ): PlayResult? = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trimEnd('/')
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < maxWaitSeconds * 1000L) {
            try {
                val req = Request.Builder()
                    .url("$cleanUrl/api/entry/$entryId")
                    .header("X-Client", "CacheTune-Android")
                    .get()
                    .build()

                client.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val root = JsonParser.parseString(body).asJsonObject
                        val status = root.get("status")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val streamUrl = root.get("stream_url")?.takeIf { !it.isJsonNull }?.asString?.let {
                            if (it.startsWith("/")) "$cleanUrl$it" else it
                        }
                        val bitDepth = root.get("bit_depth")?.takeIf { !it.isJsonNull }?.asInt
                        val sampleRate = root.get("sample_rate")?.takeIf { !it.isJsonNull }?.asInt
                        val bitrate = root.get("bitrate")?.takeIf { !it.isJsonNull }?.asInt
                        val phase = root.get("phase")?.takeIf { !it.isJsonNull }?.asString ?: "downloading"
                        val progress = root.get("progress")?.takeIf { !it.isJsonNull }?.asFloat ?: 0f

                        onProgressUpdate?.invoke(phase, progress)

                        if (streamUrl != null && (status == "cached" || status == "playing")) {
                            return@withContext PlayResult(
                                streamUrl = streamUrl,
                                cacheStatus = "cached",
                                bitDepth = bitDepth,
                                sampleRate = sampleRate,
                                bitrate = bitrate,
                                phase = "completed",
                                entryId = entryId,
                                errorMsg = null
                            )
                        } else if (status == "error") {
                            val err = root.get("error_msg")?.takeIf { !it.isJsonNull }?.asString ?: "Download failed"
                            return@withContext PlayResult(
                                streamUrl = null,
                                cacheStatus = "error",
                                bitDepth = null,
                                sampleRate = null,
                                bitrate = null,
                                phase = "error",
                                entryId = entryId,
                                errorMsg = err
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(1000)
        }
        null
    }

    /**
     * Syncs Apple Music credentials and media-user-token to Docker backend.
     */
    suspend fun saveAppleCredentials(
        baseUrl: String,
        username: String?,
        pass: String?,
        mediaUserToken: String?,
        storefront: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trimEnd('/')
        val payload = JsonObject().apply {
            if (!username.isNullOrBlank()) addProperty("username", username)
            if (!pass.isNullOrBlank()) addProperty("password", pass)
            if (!mediaUserToken.isNullOrBlank()) addProperty("media_user_token", mediaUserToken)
            if (!storefront.isNullOrBlank()) addProperty("storefront", storefront)
        }
        val request = Request.Builder()
            .url("$cleanUrl/api/config/applemusic")
            .header("X-Client", "CacheTune-Android")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    /**
     * Fetches full 15-shelf YouTube Music Home feed from Docker backend using provided credentials or guest mode.
     */
    suspend fun getHomeFeed(baseUrl: String, headersJson: String? = null): List<HomeSection>? = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.isBlank()) return@withContext null
        try {
            val payload = JsonObject().apply {
                if (!headersJson.isNullOrBlank()) addProperty("headers", headersJson)
                addProperty("limit", 15)
            }
            val request = Request.Builder()
                .url("$cleanUrl/api/home/feed")
                .header("X-Client", "CacheTune-Android")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                parseBackendHomeSections(body)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseBackendHomeSections(jsonString: String): List<HomeSection> {
        val result = mutableListOf<HomeSection>()
        try {
            val root = JsonParser.parseString(jsonString).asJsonArray
            for (shelfElem in root) {
                val shelfObj = shelfElem.asJsonObject
                val title = shelfObj.get("title")?.asString ?: "Recommended"
                val contentsArr = shelfObj.getAsJsonArray("contents") ?: continue
                val songs = mutableListOf<Song>()
                for (itemElem in contentsArr) {
                    val itemObj = itemElem.asJsonObject
                    val itemTitle = itemObj.get("title")?.asString ?: continue
                    val videoId = itemObj.get("videoId")?.takeIf { !it.isJsonNull }?.asString
                    val playlistId = itemObj.get("playlistId")?.takeIf { !it.isJsonNull }?.asString
                    val browseId = itemObj.get("browseId")?.takeIf { !it.isJsonNull }?.asString
                    val thumbnail = itemObj.get("thumbnail")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val artist = itemObj.get("artist")?.takeIf { !it.isJsonNull }?.asString ?: "YouTube Music"

                    val isPlaylist = !playlistId.isNullOrBlank() || (!browseId.isNullOrBlank() && browseId.startsWith("VL")) || videoId.isNullOrBlank()
                    val id = if (isPlaylist) (playlistId ?: browseId ?: videoId ?: "") else (videoId ?: playlistId ?: browseId ?: "")
                    if (id.isNotBlank()) {
                        songs.add(
                            Song(
                                id = id,
                                title = itemTitle,
                                artist = artist,
                                artworkUrl = thumbnail,
                                durationMs = 210000L,
                                isLossless = true,
                                codec = "2296 kbps • 96.0 kHz",
                                bitDepth = 24,
                                sampleRate = 48000,
                                isPlaylistCard = isPlaylist,
                                subtitleOverlay = itemTitle
                            )
                        )
                    }
                }
                if (songs.isNotEmpty()) {
                    val isGrid = title.contains("Quick pick", ignoreCase = true) || title.contains("Trending", ignoreCase = true)
                    result.add(HomeSection(title = title, songs = songs, isGrid = isGrid))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}
