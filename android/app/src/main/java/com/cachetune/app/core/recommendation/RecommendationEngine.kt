package com.cachetune.app.core.recommendation

import com.cachetune.app.core.model.HomeSection
import com.cachetune.app.core.model.Song
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Stage 2: YouTube Music Recommendation & Search Engine (100% On-Device)
 * 
 * Fetches real YouTube Music Home feeds (Quick picks, India's biggest hits, etc.),
 * live YouTube Music Search with thumbnails, and infinite Radio queues.
 * Direct translation from Cache Tune's backend/services/ytmusic_service.py.
 */
class RecommendationEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val endpointBrowse = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
    private val endpointNext = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
    private val endpointSearch = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"

    fun parseHeadersMap(input: String?): Map<String, String> {
        if (input.isNullOrBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        val trimmed = input.trim()

        if (trimmed.startsWith("{")) {
            try {
                val root = JsonParser.parseString(trimmed).asJsonObject
                for ((k, v) in root.entrySet()) {
                    if (!v.isJsonNull) {
                        map[k.trim()] = v.asString.trim()
                    }
                }
                if (map.isNotEmpty()) return map
            } catch (_: Exception) {}
        }

        for (line in trimmed.lines()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("//")) continue
            if (l.contains(":")) {
                val idx = l.indexOf(':')
                val key = l.substring(0, idx).trim()
                val value = l.substring(idx + 1).trim()
                if (!key.startsWith(":") && key.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
        return map
    }

    private fun getClientContext(authuser: String? = null): JsonObject {
        val context = JsonObject()
        val clientObj = JsonObject().apply {
            addProperty("clientName", "WEB_REMIX")
            addProperty("clientVersion", "1.20261029.01.00")
            addProperty("hl", "en")
            addProperty("gl", "IN")
            addProperty("osName", "Windows")
            addProperty("osVersion", "10.0")
        }
        context.add("client", clientObj)
        if (!authuser.isNullOrBlank()) {
            val userObj = JsonObject().apply {
                addProperty("onBehalfOfUser", authuser)
            }
            context.add("user", userObj)
        }
        return context
    }

    private fun applyHeaders(builder: Request.Builder, headersRaw: String?) {
        builder.header("Origin", "https://music.youtube.com")
        builder.header("Referer", "https://music.youtube.com/")
        builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")

        val headers = parseHeadersMap(headersRaw)
        if (headers.isEmpty()) return

        var cookieStr = ""
        var sapisid: String? = null
        var explicitAuth: String? = null

        for ((key, value) in headers) {
            val lower = key.lowercase()
            if (lower in listOf("content-encoding", "content-length", "connection", "priority", "te", "host", "accept-encoding")) {
                continue
            }
            if (lower == "cookie") {
                cookieStr = value
            } else if (lower == "authorization") {
                explicitAuth = value
            } else {
                builder.header(key, value)
            }
        }

        // Ensure SOCS=CAI in cookie to bypass EU cookie consent
        if (!cookieStr.contains("SOCS=")) {
            cookieStr = if (cookieStr.isBlank()) "SOCS=CAI" else "$cookieStr; SOCS=CAI"
        }

        // Auto-refresh SAPISIDHASH if SAPISID cookie is present (from ytmusic_service.py)
        val cookieKeys = listOf("__Secure-3PAPISID", "__Secure-1PAPISID", "SAPISID", "APISID")
        for (ck in cookieKeys) {
            val regex = Regex("""(?:^|[\s;])$ck\s*=\s*(?:\"([^\"]+)\"|([^;\s]+))""")
            val match = regex.find(cookieStr)
            if (match != null) {
                sapisid = match.groups[1]?.value ?: match.groups[2]?.value
                if (!sapisid.isNullOrBlank()) break
            }
        }

        if (!sapisid.isNullOrBlank()) {
            if (!cookieStr.contains("__Secure-3PAPISID")) {
                cookieStr += "; __Secure-3PAPISID=$sapisid"
            }
            val timestamp = System.currentTimeMillis() / 1000
            val raw = "$timestamp $sapisid https://music.youtube.com"
            val md = MessageDigest.getInstance("SHA-1")
            val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
            val hash = digest.joinToString("") { "%02x".format(it) }
            builder.header("authorization", "SAPISIDHASH ${timestamp}_$hash")
        } else if (!explicitAuth.isNullOrBlank()) {
            builder.header("authorization", explicitAuth)
        }

        if (cookieStr.isNotBlank()) {
            builder.header("cookie", cookieStr)
        }
    }

    /**
     * Fetches dynamic YouTube Music Home sections (Quick Picks, Hits, Trending, New releases),
     * automatically following continuations to load up to 15 full shelves.
     */
    suspend fun getHomeSections(authHeadersJson: String? = null): List<HomeSection> = withContext(Dispatchers.IO) {
        val headers = parseHeadersMap(authHeadersJson)
        val authuser = headers["x-goog-authuser"] ?: headers["X-Goog-AuthUser"]

        val payload = JsonObject().apply {
            add("context", getClientContext(authuser))
            addProperty("browseId", "FEmusic_home")
        }

        val requestBuilder = Request.Builder()
            .url(endpointBrowse)
            .post(payload.toString().toRequestBody(jsonMediaType))

        applyHeaders(requestBuilder, authHeadersJson)

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext getFallbackSections()
                val body = response.body?.string() ?: return@withContext getFallbackSections()
                val parsedResult = parseHomeResponse(body)
                val allSections = parsedResult.first.toMutableList()
                var ctoken = parsedResult.second

                var attempts = 0
                while (!ctoken.isNullOrBlank() && allSections.size < 15 && attempts < 8) {
                    attempts++
                    try {
                        val contUrl = "$endpointBrowse&ctoken=$ctoken&continuation=$ctoken"
                        val contReq = Request.Builder()
                            .url(contUrl)
                            .post(payload.toString().toRequestBody(jsonMediaType))
                        applyHeaders(contReq, authHeadersJson)
                        client.newCall(contReq.build()).execute().use { contResp ->
                            if (contResp.isSuccessful) {
                                val contBody = contResp.body?.string()
                                if (!contBody.isNullOrBlank()) {
                                    val contResult = parseContinuationResponse(contBody)
                                    if (contResult.first.isNotEmpty()) {
                                        allSections.addAll(contResult.first)
                                    }
                                    ctoken = contResult.second
                                } else {
                                    ctoken = null
                                }
                            } else {
                                ctoken = null
                            }
                        }
                    } catch (_: Exception) {
                        break
                    }
                }

                if (allSections.isNotEmpty()) allSections else getFallbackSections()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            getFallbackSections()
        }
    }

    /**
     * Searches YouTube Music for songs matching query with high-res thumbnails.
     */
    suspend fun searchSongs(query: String, authHeadersJson: String? = null): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val payload = JsonObject().apply {
            add("context", getClientContext())
            addProperty("query", query)
        }

        val requestBuilder = Request.Builder()
            .url(endpointSearch)
            .post(payload.toString().toRequestBody(jsonMediaType))

        applyHeaders(requestBuilder, authHeadersJson)

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                parseSearchResults(body)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Resolves high-quality direct audio stream URL natively on device
     * via YouTube Innertube unauthenticated player endpoint.
     */
    suspend fun resolveStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null
        try {
            val payload = JsonObject().apply {
                val context = JsonObject()
                val clientObj = JsonObject().apply {
                    addProperty("clientName", "ANDROID_VR")
                    addProperty("clientVersion", "1.61.48")
                    addProperty("hl", "en")
                    addProperty("gl", "IN")
                }
                context.add("client", clientObj)
                add("context", context)
                addProperty("videoId", videoId)
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val root = JsonParser.parseString(body).asJsonObject
                val streamingData = root.getAsJsonObject("streamingData") ?: return@withContext null
                val formats = streamingData.getAsJsonArray("adaptiveFormats") ?: return@withContext null

                var bestUrl: String? = null
                var bestBitrate = 0L

                for (elem in formats) {
                    val fmt = elem.asJsonObject
                    val mime = fmt.get("mimeType")?.asString ?: ""
                    val url = fmt.get("url")?.asString
                    val bitrate = fmt.get("bitrate")?.asLong ?: 0L

                    if (mime.startsWith("audio/") && !url.isNullOrBlank()) {
                        if (bitrate > bestBitrate) {
                            bestBitrate = bitrate
                            bestUrl = url
                        }
                    }
                }
                bestUrl
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Generates an infinite dynamic radio queue from a seed song (get_watch_playlist).
     */
    /**
     * Fetches real tracks for a YouTube Music playlist or album.
     */
    suspend fun getPlaylistTracks(playlistId: String, playlistTitle: String = "", authHeadersJson: String? = null): List<Song> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Song>()
        if (playlistId.isNotBlank()) {
            if (playlistId.startsWith("RD") || playlistId.startsWith("VLRD")) {
                val cleanPid = playlistId.removePrefix("VL")
                val payload = JsonObject().apply {
                    add("context", getClientContext())
                    addProperty("playlistId", cleanPid)
                    addProperty("isAudioOnly", true)
                    addProperty("enablePersistentPlaylistPanel", true)
                }
                val req = Request.Builder()
                    .url(endpointNext)
                    .post(payload.toString().toRequestBody(jsonMediaType))
                applyHeaders(req, authHeadersJson)
                try {
                    client.newCall(req.build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val b = resp.body?.string()
                            if (!b.isNullOrBlank()) {
                                tracks.addAll(parseRadioQueue(b, 50))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (tracks.isEmpty()) {
                val browseId = when {
                    playlistId.startsWith("VL") || playlistId.startsWith("MPREb_") || playlistId.startsWith("FEmusic_") -> playlistId
                    playlistId.startsWith("PL") || playlistId.startsWith("RDCLAK") || playlistId.startsWith("OLAK") -> "VL$playlistId"
                    playlistId.length > 15 -> "VL$playlistId"
                    else -> playlistId
                }
                val payload = JsonObject().apply {
                    add("context", getClientContext())
                    addProperty("browseId", browseId)
                }
                val req = Request.Builder()
                    .url(endpointBrowse)
                    .post(payload.toString().toRequestBody(jsonMediaType))
                applyHeaders(req, authHeadersJson)
                try {
                    client.newCall(req.build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (!body.isNullOrBlank()) {
                                tracks.addAll(parsePlaylistTracks(body))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // If official browse returned empty (or local fallback ID), search YouTube Music for playlist tracks
        if (tracks.isEmpty() && playlistTitle.isNotBlank()) {
            try {
                val cleanTitle = playlistTitle.replace(Regex("""[^a-zA-Z0-9\s]"""), " ").trim()
                val searched = searchSongs(cleanTitle, authHeadersJson)
                if (searched.isNotEmpty()) {
                    tracks.addAll(searched)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        tracks
    }

    private fun parsePlaylistTracks(jsonString: String): List<Song> {
        val tracks = mutableListOf<Song>()
        try {
            val root = JsonParser.parseString(jsonString).asJsonObject
            val contentsObj = root.getAsJsonObject("contents") ?: return emptyList()

            val shelves = mutableListOf<JsonObject>()
            fun collectShelves(element: com.google.gson.JsonElement?) {
                if (element == null) return
                if (element.isJsonObject) {
                    val obj = element.asJsonObject
                    if (obj.has("musicPlaylistShelfRenderer")) {
                        obj.getAsJsonObject("musicPlaylistShelfRenderer")?.let { shelves.add(it) }
                    }
                    if (obj.has("musicShelfRenderer")) {
                        obj.getAsJsonObject("musicShelfRenderer")?.let { shelves.add(it) }
                    }
                    for ((_, v) in obj.entrySet()) {
                        collectShelves(v)
                    }
                } else if (element.isJsonArray) {
                    for (item in element.asJsonArray) {
                        collectShelves(item)
                    }
                }
            }
            collectShelves(contentsObj)

            for (shelf in shelves) {
                val items = shelf.getAsJsonArray("contents") ?: continue
                for (itElem in items) {
                    val mrl = itElem.asJsonObject.getAsJsonObject("musicResponsiveListItemRenderer") ?: continue
                    val title = extractMrlTitle(mrl)
                    val artist = extractMrlArtist(mrl)
                    val videoId = extractMrlVideoId(mrl)
                    val thumb = extractMrlThumbnail(mrl, videoId)

                    var durationMs = 210000L
                    val durRuns = mrl.getAsJsonArray("flexColumns")?.let { fc ->
                        if (fc.size() > 1) fc.get(fc.size() - 1).asJsonObject.getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")?.getAsJsonObject("text")?.getAsJsonArray("runs") else null
                    }
                    if (durRuns != null && durRuns.size() > 0) {
                        val durStr = durRuns.get(0).asJsonObject.get("text")?.asString ?: ""
                        val parts = durStr.split(":").mapNotNull { it.trim().toLongOrNull() }
                        if (parts.size == 2) {
                            durationMs = ((parts[0] * 60) + parts[1]) * 1000L
                        }
                    }

                    if (!videoId.isNullOrBlank() && title.isNotBlank()) {
                        tracks.add(
                            Song(
                                id = videoId,
                                title = title,
                                artist = artist,
                                artworkUrl = thumb,
                                durationMs = durationMs,
                                isLossless = true,
                                codec = "FLAC 24-bit/48.0kHz",
                                bitDepth = 24,
                                sampleRate = 48000
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return tracks
    }

    suspend fun getRadioQueue(seedSong: Song, authHeadersJson: String? = null, limit: Int = 50): List<Song> = withContext(Dispatchers.IO) {
        val isYtId = seedSong.id.length == 11 && seedSong.id.matches(Regex("^[a-zA-Z0-9_-]{11}$"))
        val videoId = if (isYtId) {
            seedSong.id
        } else {
            val cleanArtist = seedSong.artist.split(",", "&", "•").firstOrNull()?.trim() ?: seedSong.artist
            val query = if (cleanArtist.isNotBlank() && cleanArtist != "Various Artists" && cleanArtist != "Unknown Artist") {
                "${seedSong.title} $cleanArtist"
            } else {
                seedSong.title
            }
            try {
                val results = searchSongs(query, authHeadersJson)
                results.firstOrNull { it.id.length == 11 && it.id.matches(Regex("^[a-zA-Z0-9_-]{11}$")) }?.id ?: seedSong.id
            } catch (e: Exception) {
                seedSong.id
            }
        }

        val payload = JsonObject().apply {
            add("context", getClientContext())
            addProperty("videoId", videoId)
            addProperty("playlistId", "RDAMVM$videoId")
            addProperty("isAudioOnly", true)
            addProperty("enablePersistentPlaylistPanel", true)
            addProperty("params", "wAEB")
            addProperty("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        }

        val requestBuilder = Request.Builder()
            .url(endpointNext)
            .post(payload.toString().toRequestBody(jsonMediaType))

        applyHeaders(requestBuilder, authHeadersJson)

        var queue: List<Song> = emptyList()
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        queue = parseRadioQueue(body, limit)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback: If RDAMVM returned empty, try RD+videoId
        if (queue.isEmpty() && videoId.length == 11) {
            try {
                val fallbackPayload = JsonObject().apply {
                    add("context", getClientContext())
                    addProperty("videoId", videoId)
                    addProperty("playlistId", "RD$videoId")
                    addProperty("isAudioOnly", true)
                    addProperty("enablePersistentPlaylistPanel", true)
                }
                val fallbackReq = Request.Builder()
                    .url(endpointNext)
                    .post(fallbackPayload.toString().toRequestBody(jsonMediaType))
                applyHeaders(fallbackReq, authHeadersJson)
                client.newCall(fallbackReq.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val b = resp.body?.string()
                        if (!b.isNullOrBlank()) {
                            queue = parseRadioQueue(b, limit)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        queue
    }

    private fun parseShelfElements(contents: JsonArray): List<HomeSection> {
        val sections = mutableListOf<HomeSection>()
        for (sectionElem in contents) {
            val sectionObj = sectionElem.asJsonObject
            val shelf = sectionObj.getAsJsonObject("musicCarouselShelfRenderer")
                ?: sectionObj.getAsJsonObject("musicShelfRenderer")
                ?: sectionObj.getAsJsonObject("gridRenderer")
                ?: sectionObj.getAsJsonObject("musicGridRenderer")
                ?: sectionObj.getAsJsonObject("musicImmersiveCarouselShelfRenderer")
                ?: sectionObj.getAsJsonObject("itemSectionRenderer")?.getAsJsonArray("contents")?.firstOrNull()?.asJsonObject?.let {
                    it.getAsJsonObject("musicCarouselShelfRenderer")
                        ?: it.getAsJsonObject("musicShelfRenderer")
                        ?: it.getAsJsonObject("musicGridRenderer")
                        ?: it.getAsJsonObject("gridRenderer")
                }
                ?: continue

            var header = ""
            val shelfHeader = shelf.getAsJsonObject("header")
            if (shelfHeader != null) {
                val basicHeader = shelfHeader.getAsJsonObject("musicCarouselShelfBasicHeaderRenderer")
                    ?: shelfHeader.getAsJsonObject("musicHeaderRenderer")
                val titleRuns = basicHeader?.getAsJsonObject("title")?.getAsJsonArray("runs")
                if (titleRuns != null && titleRuns.size() > 0) {
                    header = titleRuns.get(0).asJsonObject.get("text")?.asString ?: ""
                }
            }
            if (header.isBlank()) {
                header = "Recommended For You"
            }

            val items = shelf.getAsJsonArray("contents") 
                ?: shelf.getAsJsonArray("items") 
                ?: continue

            val songs = mutableListOf<Song>()
            var hasTrackRows = false

            for (itemElem in items) {
                val itemObj = itemElem.asJsonObject
                val mrl = itemObj.getAsJsonObject("musicResponsiveListItemRenderer")
                val mtr = itemObj.getAsJsonObject("musicTwoRowItemRenderer")
                    ?: itemObj.getAsJsonObject("musicNavigationButtonRenderer")

                if (mrl != null) {
                    hasTrackRows = true
                    val title = extractMrlTitle(mrl)
                    val artist = extractMrlArtist(mrl)
                    val videoId = extractMrlVideoId(mrl)
                    val thumb = extractMrlThumbnail(mrl)

                    if (!videoId.isNullOrBlank() && title.isNotBlank()) {
                        songs.add(
                            Song(
                                id = videoId,
                                title = title,
                                artist = artist,
                                artworkUrl = thumb,
                                durationMs = 210000L,
                                isLossless = true,
                                codec = "FLAC 24-bit/48.0kHz",
                                bitDepth = 24,
                                sampleRate = 48000
                            )
                        )
                    }
                } else if (mtr != null) {
                    val title = mtr.getAsJsonObject("title")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""
                    val subtitle = mtr.getAsJsonObject("subtitle")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: "Various Artists"
                    val videoId = extractVideoIdFromObject(mtr)
                    val navEndpoint = mtr.getAsJsonObject("navigationEndpoint")
                    val browseId = navEndpoint?.getAsJsonObject("browseEndpoint")?.get("browseId")?.asString
                    val playlistId = navEndpoint?.getAsJsonObject("watchPlaylistEndpoint")?.get("playlistId")?.asString
                    val isPlaylist = (!browseId.isNullOrBlank() && (browseId.startsWith("VL") || browseId.startsWith("MPREb_") || browseId.startsWith("FEmusic_"))) ||
                                     (!playlistId.isNullOrBlank() && (playlistId.startsWith("PL") || playlistId.startsWith("RDCLAK") || playlistId.startsWith("OLAK"))) ||
                                     videoId.isNullOrBlank()
                    val itemKey = if (isPlaylist) (browseId ?: playlistId ?: videoId ?: "") else (videoId ?: playlistId ?: browseId ?: "")
                    val thumb = extractThumbnail(mtr, videoId)

                    if (itemKey.isNotBlank() && title.isNotBlank()) {
                        songs.add(
                            Song(
                                id = itemKey,
                                title = title,
                                artist = subtitle,
                                artworkUrl = thumb,
                                durationMs = 210000L,
                                isLossless = true,
                                codec = "2296 kbps • 96.0 kHz",
                                bitDepth = 24,
                                sampleRate = 48000,
                                subtitleOverlay = title,
                                isPlaylistCard = isPlaylist
                            )
                        )
                    }
                }
            }

            if (songs.isNotEmpty()) {
                val isGrid = hasTrackRows || 
                    header.contains("Quick pick", ignoreCase = true) || 
                    header.contains("Trending", ignoreCase = true)
                sections.add(HomeSection(title = header, songs = songs, isGrid = isGrid))
            }
        }
        return sections
    }

    private fun parseHomeResponse(jsonString: String): Pair<List<HomeSection>, String?> {
        val sections = mutableListOf<HomeSection>()
        var ctoken: String? = null
        try {
            val root = JsonParser.parseString(jsonString).asJsonObject
            val tabs = root.getAsJsonObject("contents")
                ?.getAsJsonObject("singleColumnBrowseResultsRenderer")
                ?.getAsJsonArray("tabs") ?: return Pair(emptyList(), null)

            val sectionList = tabs.get(0)?.asJsonObject
                ?.getAsJsonObject("tabRenderer")
                ?.getAsJsonObject("content")
                ?.getAsJsonObject("sectionListRenderer") ?: return Pair(emptyList(), null)

            val contents = sectionList.getAsJsonArray("contents") ?: JsonArray()
            sections.addAll(parseShelfElements(contents))

            // Extract continuation token if available
            val continuations = sectionList.getAsJsonArray("continuations")
            if (continuations != null && continuations.size() > 0) {
                ctoken = continuations.get(0)?.asJsonObject
                    ?.getAsJsonObject("nextContinuationData")
                    ?.get("continuation")?.asString
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(sections, ctoken)
    }

    private fun parseContinuationResponse(jsonString: String): Pair<List<HomeSection>, String?> {
        val sections = mutableListOf<HomeSection>()
        var nextCtoken: String? = null
        try {
            val root = JsonParser.parseString(jsonString).asJsonObject
            val slc = root.getAsJsonObject("continuationContents")
                ?.getAsJsonObject("sectionListContinuation")
            if (slc != null) {
                val contents = slc.getAsJsonArray("contents") ?: JsonArray()
                sections.addAll(parseShelfElements(contents))

                val continuations = slc.getAsJsonArray("continuations")
                if (continuations != null && continuations.size() > 0) {
                    nextCtoken = continuations.get(0)?.asJsonObject
                        ?.getAsJsonObject("nextContinuationData")
                        ?.get("continuation")?.asString
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(sections, nextCtoken)
    }

    private fun parseHomeSections(jsonString: String): List<HomeSection> {
        val res = parseHomeResponse(jsonString).first
        return if (res.isNotEmpty()) res else getFallbackSections()
    }

    private fun parseSearchResults(jsonString: String): List<Song> {
        val results = mutableListOf<Song>()
        try {
            val root = JsonParser.parseString(jsonString).asJsonObject
            val tabs = root.getAsJsonObject("contents")
                ?.getAsJsonObject("tabbedSearchResultsRenderer")
                ?.getAsJsonArray("tabs") ?: return emptyList()

            val sections = tabs.get(0)?.asJsonObject
                ?.getAsJsonObject("tabRenderer")
                ?.getAsJsonObject("content")
                ?.getAsJsonObject("sectionListRenderer")
                ?.getAsJsonArray("contents") ?: return emptyList()

            for (sec in sections) {
                val secObj = sec.asJsonObject

                // Top card
                val cardShelf = secObj.getAsJsonObject("musicCardShelfRenderer")
                if (cardShelf != null) {
                    val title = cardShelf.getAsJsonObject("title")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""
                    val subtitle = cardShelf.getAsJsonObject("subtitle")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""
                    val videoId = extractVideoIdFromObject(cardShelf)
                    val thumb = extractThumbnail(cardShelf)

                    if (!videoId.isNullOrBlank() && title.isNotBlank()) {
                        results.add(
                            Song(
                                id = videoId,
                                title = title,
                                artist = subtitle,
                                artworkUrl = thumb,
                                durationMs = 210000L,
                                isLossless = true,
                                codec = "ALAC 24-bit/192kHz"
                            )
                        )
                    }
                }

                // Item sections
                val itemSec = secObj.getAsJsonObject("itemSectionRenderer")
                val itemContents = itemSec?.getAsJsonArray("contents")
                if (itemContents != null) {
                    for (it in itemContents) {
                        val mrl = it.asJsonObject.getAsJsonObject("musicResponsiveListItemRenderer") ?: continue
                        val title = extractMrlTitle(mrl)
                        val artist = extractMrlArtist(mrl)
                        val videoId = extractMrlVideoId(mrl)
                        val thumb = extractMrlThumbnail(mrl)

                        if (!videoId.isNullOrBlank() && title.isNotBlank()) {
                            results.add(
                                Song(
                                    id = videoId,
                                    title = title,
                                    artist = artist,
                                    artworkUrl = thumb,
                                    durationMs = 210000L,
                                    isLossless = true,
                                    codec = "ALAC 24-bit/192kHz"
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    private fun parseRadioQueue(jsonString: String, limit: Int): List<Song> {
        val queue = mutableListOf<Song>()
        try {
            val root = JsonParser.parseString(jsonString).asJsonObject
            val tabs = root.getAsJsonObject("contents")
                ?.getAsJsonObject("singleColumnMusicWatchNextResultsRenderer")
                ?.getAsJsonObject("tabbedRenderer")
                ?.getAsJsonObject("watchNextTabbedResultsRenderer")
                ?.getAsJsonArray("tabs") ?: return emptyList()

            val playlistPanel = tabs.get(0)?.asJsonObject
                ?.getAsJsonObject("tabRenderer")
                ?.getAsJsonObject("content")
                ?.getAsJsonObject("musicQueueRenderer")
                ?.getAsJsonObject("content")
                ?.getAsJsonObject("playlistPanelRenderer")
                ?.getAsJsonArray("contents") ?: return emptyList()

            for (itemElem in playlistPanel) {
                if (queue.size >= limit) break
                val item = itemElem.asJsonObject.getAsJsonObject("playlistPanelVideoRenderer") ?: continue
                val title = item.getAsJsonObject("title")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""
                val artist = item.getAsJsonObject("longBylineText")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: "Various Artists"
                val videoId = item.get("videoId")?.asString ?: continue
                val thumb = extractThumbnail(item, videoId)

                var durationMs = 220000L
                val lengthRuns = item.getAsJsonObject("lengthText")?.getAsJsonArray("runs")
                if (lengthRuns != null && lengthRuns.size() > 0) {
                    val lengthStr = lengthRuns.get(0).asJsonObject.get("text")?.asString ?: ""
                    val parts = lengthStr.split(":").mapNotNull { it.trim().toLongOrNull() }
                    if (parts.size == 2) {
                        durationMs = ((parts[0] * 60) + parts[1]) * 1000L
                    } else if (parts.size == 3) {
                        durationMs = ((parts[0] * 3600) + (parts[1] * 60) + parts[2]) * 1000L
                    }
                }

                queue.add(
                    Song(
                        id = videoId,
                        title = title,
                        artist = artist,
                        artworkUrl = thumb,
                        durationMs = durationMs,
                        isLossless = true,
                        codec = "FLAC 24-bit/48.0kHz",
                        bitDepth = 24,
                        sampleRate = 48000
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return queue
    }

    private fun extractMrlTitle(mrl: JsonObject): String {
        val flex = mrl.getAsJsonArray("flexColumns") ?: return ""
        if (flex.size() > 0) {
            return flex.get(0).asJsonObject
                .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                ?.getAsJsonObject("text")
                ?.getAsJsonArray("runs")?.get(0)?.asJsonObject
                ?.get("text")?.asString ?: ""
        }
        return ""
    }

    private fun extractMrlArtist(mrl: JsonObject): String {
        val flex = mrl.getAsJsonArray("flexColumns") ?: return "Unknown Artist"
        if (flex.size() > 1) {
            val runs = flex.get(1).asJsonObject
                .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                ?.getAsJsonObject("text")
                ?.getAsJsonArray("runs") ?: return "Unknown Artist"
            val sb = StringBuilder()
            for (r in runs) {
                val t = r.asJsonObject.get("text")?.asString ?: ""
                if (t != " • " && t != " · " && !t.contains("views") && !t.contains("plays")) {
                    sb.append(t)
                }
            }
            val res = sb.toString().trim()
            return if (res.isNotBlank()) res else "Unknown Artist"
        }
        return "Unknown Artist"
    }

    private fun extractMrlVideoId(mrl: JsonObject): String? {
        val pid = mrl.getAsJsonObject("playlistItemData")?.get("videoId")?.asString
        if (!pid.isNullOrBlank()) return pid

        val overlay = mrl.getAsJsonObject("overlay")
            ?.getAsJsonObject("musicItemThumbnailOverlayRenderer")
            ?.getAsJsonObject("content")
            ?.getAsJsonObject("musicPlayButtonRenderer")
            ?.getAsJsonObject("playNavigationEndpoint")
            ?.getAsJsonObject("watchEndpoint")
            ?.get("videoId")?.asString
        if (!overlay.isNullOrBlank()) return overlay

        return extractVideoIdFromObject(mrl)
    }

    fun cleanThumbnailUrl(url: String, videoId: String? = null): String {
        if (url.isBlank()) {
            return if (!videoId.isNullOrBlank()) "https://i.ytimg.com/vi/$videoId/sddefault.jpg" else ""
        }
        var cleaned = url
        if (cleaned.contains("googleusercontent.com")) {
            cleaned = cleaned.replace(Regex("""=w\d+-h\d+.*"""), "=w544-h544-l90-rj")
            if (!cleaned.contains("=w544-h544")) {
                cleaned += "=w544-h544-l90-rj"
            }
        } else if (cleaned.contains("ggpht.com")) {
            cleaned = cleaned.replace(Regex("""=s\d+.*"""), "=s544-c")
        } else if (cleaned.contains("i.ytimg.com")) {
            cleaned = cleaned.replace("hqdefault.jpg", "maxresdefault.jpg").replace("sddefault.jpg", "maxresdefault.jpg")
        }
        return cleaned
    }

    private fun extractMrlThumbnail(mrl: JsonObject, videoId: String? = null): String {
        val thumbs = mrl.getAsJsonObject("thumbnail")
            ?.getAsJsonObject("musicThumbnailRenderer")
            ?.getAsJsonObject("thumbnail")
            ?.getAsJsonArray("thumbnails")
        if (thumbs != null && thumbs.size() > 0) {
            val raw = thumbs.get(thumbs.size() - 1).asJsonObject.get("url")?.asString ?: ""
            if (raw.isNotBlank()) {
                return cleanThumbnailUrl(raw, videoId)
            }
        }
        return if (!videoId.isNullOrBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""
    }

    private fun extractVideoIdFromObject(obj: JsonObject): String? {
        for ((key, value) in obj.entrySet()) {
            if (key == "videoId" && value.isJsonPrimitive) {
                return value.asString
            }
            if (value.isJsonObject) {
                val found = extractVideoIdFromObject(value.asJsonObject)
                if (found != null) return found
            }
        }
        return null
    }

    private fun extractThumbnail(obj: JsonObject, videoId: String? = null): String {
        val thumbObj = obj.getAsJsonObject("thumbnail")
            ?: obj.getAsJsonObject("thumbnailRenderer")?.getAsJsonObject("musicThumbnailRenderer")?.getAsJsonObject("thumbnail")
        val thumbs = thumbObj?.getAsJsonArray("thumbnails")
        if (thumbs != null && thumbs.size() > 0) {
            val raw = thumbs.get(thumbs.size() - 1).asJsonObject.get("url")?.asString ?: ""
            if (raw.isNotBlank()) {
                return cleanThumbnailUrl(raw, videoId)
            }
        }
        return if (!videoId.isNullOrBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""
    }

    private fun getFallbackSections(): List<HomeSection> {
        val quickPicks = listOf(
            Song(id = "zAiIgYOH4Ys", title = "KALYANI (Remix)", artist = "ARJN, KDS, FIFTY4, Shreya Ghoshal", artworkUrl = "https://yt3.googleusercontent.com/naKgO_9vvIczuf7Vq1llQyRAQOOW898kBZN3pio-Bkbfcmdu3Gv14_ivEBZiHAow8VbPEq1bhO0j2DU=w544-h544-l90-rj", durationMs = 210000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, bitrate = 2296),
            Song(id = "i1o1p_DD6TU", title = "Gehra Hua (From \"Dhurandhar\")", artist = "Shashwat Sachdev, Arijit Singh, Armaan Khan", artworkUrl = "https://yt3.googleusercontent.com/B1p2-JcIuomNuey91o6bvshtrtw2gr0pH1bIviJxDqNZiT9et79lCgY4-pU-6FhVNWbi9FrnOtJ35t8=w544-h544-l90-rj", durationMs = 240000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, bitrate = 2296),
            Song(id = "BmRX2g6-iQI", title = "Radhimaa [From \"Think Indie\"]", artist = "Sai Abhyankkar, Nargis Teji, Asma Teji", artworkUrl = "https://yt3.googleusercontent.com/YpHZO1DBcGPbgywaeckHbkkiI-b4OetQDJnQtCM--usqBrKljB-9uXax23i3hHI-PiTlyyHLBdYScSeGRQ=w544-h544-l90-rj", durationMs = 195000L, isLossless = true, codec = "1777 kbps • 48.0 kHz", bitDepth = 24, sampleRate = 48000, bitrate = 1777),
            Song(id = "65f9M1Si5sM", title = "MONTAGEM DELÍRIO (Super Slowed)", artist = "ZXVREN", artworkUrl = "https://yt3.googleusercontent.com/pAsoS8Di_OazLK47PgcNt66F-ZsrOWCNFFHkzppTQvqH0BMsdV1dl-CUN_bdmso-UU_zcuLUz0fyiWE=w544-h544-l90-rj", durationMs = 185000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, bitrate = 2296),
            Song(id = "uU7y15P0zOk", title = "Aadame", artist = "Manikandan Ayyappa", artworkUrl = "https://yt3.googleusercontent.com/TYR7azjAnbTPscVDNNDd95VTfav8eLEQUyUMcFGGBnNE0u5WGQBXPMwZt2-fyAEDjo-FN3Xp8WNk7eQt-Q=w544-h544-l90-rj", durationMs = 220000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, bitrate = 2296),
            Song(id = "kyqJ_FId-_w", title = "Bairan", artist = "Banjaare", artworkUrl = "https://yt3.googleusercontent.com/LF_Gw__z3R6L4Mbz1cW7RDKXYiNcYTxBR2QrrWk0gXgUDhv9uOx01XLtvlmf4PGBISXNIlp2r0XxLU_2=w544-h544-l90-rj", durationMs = 205000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, bitrate = 2296)
        )

        val trending = listOf(
            Song(id = "jx3vD6yWyAE", title = "Mangal Rang (From \"Ramayana\")", artist = "A.R. Rahman, Arijit Singh, Shreya Ghoshal", artworkUrl = "https://yt3.googleusercontent.com/3IdLSOXCfDdtc7nV-QRCePwAPObkulmPbuYvLAbNwaZllr0I4KWGhUVud9kfRTZUAO-ziL0P3yeOuNv3=w544-h544-l90-rj", durationMs = 215000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, bitrate = 2296),
            Song(id = "wkz07LzGpcY", title = "Veezha Veera (From \"OM Chap...\")", artist = "Sai Abhyankkar, Tippu, Arun Srin...", artworkUrl = "https://yt3.googleusercontent.com/-1lzVkbi_77iv0MQ_3bHLoBgf_onVd_X6uRh_jTpHx_Z7C09ZTL6R7qi3M6ykpWVyr5V2TXNNDe9iIwN=w544-h544-l90-rj", durationMs = 240000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, bitrate = 2296),
            Song(id = "BXxgjiHeePI", title = "Tarantino", artist = "LISA", artworkUrl = "https://yt3.googleusercontent.com/X6shh2YTShbeZhJHkzuyzcsdyaGMVtTyGecA8X03GuiiD4Aggf-99vMMmdoUfoma0PgZT6xUka1UyMk=w544-h544-l90-rj", durationMs = 190000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, bitrate = 2296),
            Song(id = "CFFHc6JxENE", title = "Heera Ke Ring", artist = "Raushan Rohi, Srishti Bharati", artworkUrl = "https://yt3.googleusercontent.com/slP9-IkDHfJsq9GhmBZO0PmYW2UxFYjUj4ZvlBPVfCoKX-7oLLYtTYWbWJr8dPmzIXqCyfaHZbjZtxDt=w544-h544-l90-rj", durationMs = 195000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, bitrate = 2296),
            Song(id = "dBYFBWIDWOs", title = "No Favours", artist = "Dhanda Nyoliwala", artworkUrl = "https://yt3.googleusercontent.com/vOegCgIfNESFuXR5KkwwoGs_u3iJ2hOLuHKOeknRQmGXOVVaSEcjKw2GA0q8um0Pr2g1Wo9O3oHANH6Q8w=w544-h544-l90-rj", durationMs = 190000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, bitrate = 2296)
        )

        val dancingOnYourOwn = listOf(
            Song(id = "S1jo8K4kDJc", title = "Mollywood Party", artist = "Sushin Shyam, Vinayak Sasikumar", artworkUrl = "https://yt3.googleusercontent.com/RjRPztjAVR2sxLUV-pIK5n0TVvzQcousWmlxLZYJMxTEoJDS7YiB0u0CuJ4qYWSarxqzaOQPoyrmF8Yh4g=w544-h544-l90-rj", durationMs = 240000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, subtitleOverlay = "Mollywood Party", isPlaylistCard = true),
            Song(id = "N6_EvGT0ZfM", title = "Bollywood Fire", artist = "Amitabh Bhattacharya, Pritam", artworkUrl = "https://yt3.googleusercontent.com/xlZs7zjh277lx98oHLHWOJc9l4abh9-cPtav9vvlRrrEt0XTqmmQYYK6mm0YJrAKWrbebJlKwtw43c4=w544-h544-l90-rj", durationMs = 260000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, subtitleOverlay = "Bollywood Fire", isPlaylistCard = true),
            Song(id = "d3", title = "Tollywood Beats", artist = "Devi Sri Prasad, Thaman S", artworkUrl = "https://yt3.googleusercontent.com/pAsoS8Di_OazLK47PgcNt66F-ZsrOWCNFFHkzppTQvqH0BMsdV1dl-CUN_bdmso-UU_zcuLUz0fyiWE=w544-h544-l90-rj", durationMs = 230000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, subtitleOverlay = "Tollywood Beats", isPlaylistCard = true),
            Song(id = "d4", title = "Punjabi Hype", artist = "Karan Aujla, Diljit Dosanjh", artworkUrl = "https://yt3.googleusercontent.com/xlZs7zjh277lx98oHLHWOJc9l4abh9-cPtav9vvlRrrEt0XTqmmQYYK6mm0YJrAKWrbebJlKwtw43c4=w544-h544-l90-rj", durationMs = 210000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, subtitleOverlay = "Punjabi Hype", isPlaylistCard = true)
        )

        val newReleases = listOf(
            Song(id = "Mo29yrxMEKw", title = "MONTAGEM DE RUA", artist = "Rubikdice, Chilx, WAA", artworkUrl = "https://yt3.googleusercontent.com/tBSRdQGsIqm1trZ9ZvtTto6c7LuqXuS1OUxmf1DEzfMa_vstsejdHgAgNEkqCiZa9eY2yRKCUvHbznrF=w544-h544-l90-rj", durationMs = 175000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000),
            Song(id = "_Y3vCU_IBZQ", title = "Shri Ram Ki Gali Mein...", artist = "Tulsi Kumar, Raju Das, Jai Shankar Choudhary", artworkUrl = "https://yt3.googleusercontent.com/MthXRLpRK-qrrURD6EgGspXJUqYKbV1VUJnhlSkh-lOe60VRbWNAIeOjFNQXredVI1x6taRHVHFSLwVW=w544-h544-l90-rj", durationMs = 240000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000),
            Song(id = "pdHtjPYg5G0", title = "Jailer 2 Theme", artist = "Anirudh Ravichander", artworkUrl = "https://yt3.googleusercontent.com/9QMYbijKjQ_un7g0nDBWsWXAykR9EojQYq4HAHOZ3FcMNquUqMUwH11SVQ8Zkdmj3Qq-wrWY_9gVN9Melg=w544-h544-l90-rj", durationMs = 210000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000),
            Song(id = "nr4", title = "Badmash Banayib Raja Ke", artist = "Khesari Lal Yadav", artworkUrl = "https://yt3.googleusercontent.com/slP9-IkDHfJsq9GhmBZO0PmYW2UxFYjUj4ZvlBPVfCoKX-7oLLYtTYWbWJr8dPmzIXqCyfaHZbjZtxDt=w544-h544-l90-rj", durationMs = 195000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 48000)
        )

        val oldSchoolRomance = listOf(
            Song(id = "os1", title = "80s Bollywood Romance", artist = "Kishore Kumar, Lata Mangeshkar, Asha Bhosle", artworkUrl = "https://yt3.googleusercontent.com/naKgO_9vvIczuf7Vq1llQyRAQOOW898kBZN3pio-Bkbfcmdu3Gv14_ivEBZiHAow8VbPEq1bhO0j2DU=w544-h544-l90-rj", durationMs = 320000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, subtitleOverlay = "80s Bollywood Romance", isPlaylistCard = true),
            Song(id = "os2", title = "Ishq Sufiyana", artist = "Pritam, Arijit Singh, Shreya Ghoshal, A. R. Rahman", artworkUrl = "https://yt3.googleusercontent.com/B1p2-JcIuomNuey91o6bvshtrtw2gr0pH1bIviJxDqNZiT9et79lCgY4-pU-6FhVNWbi9FrnOtJ35t8=w544-h544-l90-rj", durationMs = 285000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, subtitleOverlay = "Ishq Sufiyana", isPlaylistCard = true),
            Song(id = "os3", title = "90s Eternal Duets", artist = "Kumar Sanu, Alka Yagnik, Udit Narayan", artworkUrl = "https://yt3.googleusercontent.com/YpHZO1DBcGPbgywaeckHbkkiI-b4OetQDJnQtCM--usqBrKljB-9uXax23i3hHI-PiTlyyHLBdYScSeGRQ=w544-h544-l90-rj", durationMs = 330000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, subtitleOverlay = "90s Eternal Duets", isPlaylistCard = true)
        )

        val trendingCommunity = listOf(
            Song(id = "tc1", title = "MOST ROMANTIC ALL TIME", artist = "Siddh Shah • 4.7M views", artworkUrl = "https://yt3.googleusercontent.com/YpHZO1DBcGPbgywaeckHbkkiI-b4OetQDJnQtCM--usqBrKljB-9uXax23i3hHI-PiTlyyHLBdYScSeGRQ=w544-h544-l90-rj", durationMs = 300000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, isPlaylistCard = true),
            Song(id = "tc2", title = "Old is gold", artist = "kewal thakur • 2.5M views", artworkUrl = "https://yt3.googleusercontent.com/3IdLSOXCfDdtc7nV-QRCePwAPObkulmPbuYvLAbNwaZllr0I4KWGhUVud9kfRTZUAO-ziL0P3yeOuNv3=w544-h544-l90-rj", durationMs = 280000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, isPlaylistCard = true),
            Song(id = "tc3", title = "Late Night Driving Melodies", artist = "Aman Verma • 1.8M views", artworkUrl = "https://yt3.googleusercontent.com/RjRPztjAVR2sxLUV-pIK5n0TVvzQcousWmlxLZYJMxTEoJDS7YiB0u0CuJ4qYWSarxqzaOQPoyrmF8Yh4g=w544-h544-l90-rj", durationMs = 290000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, isPlaylistCard = true)
        )

        val brbBeingNostalgic = listOf(
            Song(id = "bn1", title = "00s Bollywood Romance", artist = "Alka Yagnik, Pritam, Sonu Nigam, Shreya Ghoshal", artworkUrl = "https://yt3.googleusercontent.com/B1p2-JcIuomNuey91o6bvshtrtw2gr0pH1bIviJxDqNZiT9et79lCgY4-pU-6FhVNWbi9FrnOtJ35t8=w544-h544-l90-rj", durationMs = 310000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, subtitleOverlay = "00s Bollywood Romance", isPlaylistCard = true),
            Song(id = "bn2", title = "90s Bollywood Romance", artist = "Alka Yagnik, Kumar Sanu, Udit Narayan, Jatin Lalit", artworkUrl = "https://yt3.googleusercontent.com/YpHZO1DBcGPbgywaeckHbkkiI-b4OetQDJnQtCM--usqBrKljB-9uXax23i3hHI-PiTlyyHLBdYScSeGRQ=w544-h544-l90-rj", durationMs = 320000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, subtitleOverlay = "90s Bollywood Romance", isPlaylistCard = true),
            Song(id = "bn3", title = "Early 2000s Pop Hits", artist = "Lucky Ali, Euphoria, Silk Route", artworkUrl = "https://yt3.googleusercontent.com/naKgO_9vvIczuf7Vq1llQyRAQOOW898kBZN3pio-Bkbfcmdu3Gv14_ivEBZiHAow8VbPEq1bhO0j2DU=w544-h544-l90-rj", durationMs = 290000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, subtitleOverlay = "Indie Pop Nostalgia", isPlaylistCard = true)
        )

        val indiasBiggestHits = listOf(
            Song(id = "ibh1", title = "HITS OF 2026 SO FAR", artist = "Arijit Singh, Anirudh, Diljit Dosanjh", artworkUrl = "https://yt3.googleusercontent.com/naKgO_9vvIczuf7Vq1llQyRAQOOW898kBZN3pio-Bkbfcmdu3Gv14_ivEBZiHAow8VbPEq1bhO0j2DU=w544-h544-l90-rj", durationMs = 240000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, isPlaylistCard = true),
            Song(id = "ibh2", title = "TOP 50: INDIA", artist = "Global & National Chartbusters", artworkUrl = "https://yt3.googleusercontent.com/B1p2-JcIuomNuey91o6bvshtrtw2gr0pH1bIviJxDqNZiT9et79lCgY4-pU-6FhVNWbi9FrnOtJ35t8=w544-h544-l90-rj", durationMs = 260000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, isPlaylistCard = true),
            Song(id = "ibh3", title = "Viral Hits India", artist = "Trending Audio Sensations", artworkUrl = "https://yt3.googleusercontent.com/RjRPztjAVR2sxLUV-pIK5n0TVvzQcousWmlxLZYJMxTEoJDS7YiB0u0CuJ4qYWSarxqzaOQPoyrmF8Yh4g=w544-h544-l90-rj", durationMs = 220000L, isLossless = true, codec = "2296 kbps • 96.0 kHz", bitDepth = 24, sampleRate = 96000, isPlaylistCard = true)
        )

        return listOf(
            HomeSection(title = "★ Quick picks", songs = quickPicks, isGrid = true),
            HomeSection(title = "★ Trending songs for you", songs = trending, isGrid = true),
            HomeSection(title = "Dancing on your own", songs = dancingOnYourOwn, isGrid = false),
            HomeSection(title = "New releases", songs = newReleases, isGrid = false),
            HomeSection(title = "Old School Romance", songs = oldSchoolRomance, isGrid = false),
            HomeSection(title = "Trending community playlists", songs = trendingCommunity, isGrid = false),
            HomeSection(title = "Brb, Being Nostalgic!", songs = brbBeingNostalgic, isGrid = false),
            HomeSection(title = "India's biggest hits", songs = indiasBiggestHits, isGrid = false)
        )
    }
}