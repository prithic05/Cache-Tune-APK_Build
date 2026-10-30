package com.cachetune.app.core.search

import com.cachetune.app.core.model.Song
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Stage 1: Apple Music Search Engine (100% On-Device)
 * 
 * Directly queries Apple's public iTunes API with zero server intermediary.
 * Implements high-precision title and artist fuzzy matching ported from
 * Cache Tune's scraper.py.
 */
class AppleMusicSearchEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /**
     * Cleans and normalizes song/movie titles for reliable matching.
     */
    fun normalizeText(text: String): String {
        if (text.isBlank()) return ""
        var cleaned = text
        // Strip Apple Music soundtrack tags
        cleaned = cleaned.replace(Regex("(?i)\\(\\s*from\\s+[^)]+\\)"), "")
        cleaned = cleaned.replace(Regex("(?i)\\[\\s*from\\s+[^\\]]+\\]"), "")
        cleaned = cleaned.replace(Regex("(?i)\\(\\s*original\\s+motion\\s+picture\\s+soundtrack\\s*\\)"), "")
        cleaned = cleaned.replace(Regex("(?i)-\\s*single"), "")
        cleaned = cleaned.replace(Regex("(?i)-\\s*ep"), "")
        
        // Remove punctuation and extra whitespace
        cleaned = cleaned.replace(Regex("[^a-zA-Z0-9\\s]"), "")
        cleaned = cleaned.replace(Regex("\\s+"), " ")
        return cleaned.trim().lowercase()
    }

    /**
     * Calculates the Levenshtein similarity ratio between two strings [0.0 - 1.0].
     */
    fun similarityRatio(s1: String, s2: String): Double {
        val n1 = normalizeText(s1)
        val n2 = normalizeText(s2)
        if (n1 == n2) return 1.0
        if (n1.isEmpty() || n2.isEmpty()) return 0.0

        val maxLen = max(n1.length, n2.length)
        val distance = levenshteinDistance(n1, n2)
        return 1.0 - (distance.toDouble() / maxLen.toDouble())
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLen = lhs.length
        val rhsLen = rhs.length
        var cost = IntArray(lhsLen + 1) { it }
        var newCost = IntArray(lhsLen + 1) { 0 }

        for (i in 1..rhsLen) {
            newCost[0] = i
            for (j in 1..lhsLen) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLen]
    }

    /**
     * Checks if the YouTube artist matches the Apple Music artist.
     */
    fun matchArtist(ytArtist: String, amArtist: String): Boolean {
        val normYt = normalizeText(ytArtist)
        val normAm = normalizeText(amArtist)
        if (normYt.isBlank() || normAm.isBlank()) return false

        // Check exact substring match
        if (normYt in normAm || normAm in normYt) return true

        // Split multiple artists (comma, &)
        val parts = normAm.split(",", "&").map { it.trim() }
        for (part in parts) {
            if (part.isBlank()) continue
            if (normYt in part || part in normYt) return true
            if (similarityRatio(normYt, part) > 0.85) return true
        }

        return similarityRatio(normYt, normAm) > 0.80
    }

    /**
     * Searches Apple Music via the iTunes Search API directly on the device.
     */
    suspend fun searchSongs(
        query: String,
        storefront: String = "in",
        limit: Int = 25
    ): List<Song> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=$limit&country=$storefront"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
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
     * Resolves the best matching Apple Music track for a given title and artist.
     */
    suspend fun resolveAppleUrl(
        songTitle: String,
        artist: String = "",
        storefront: String = "in"
    ): Song? = withContext(Dispatchers.IO) {
        val cleanArtist = if (artist.contains(",")) artist.split(",")[0].trim() else artist
        val query = if (cleanArtist.isNotBlank()) "$songTitle $cleanArtist" else songTitle
        val candidates = searchSongs(query, storefront, limit = 10)

        // Find candidate with best score
        var bestMatch: Song? = null
        var highestScore = 0.0

        for (candidate in candidates) {
            val titleScore = similarityRatio(songTitle, candidate.title)
            val artistScore = if (artist.isNotBlank()) {
                if (matchArtist(artist, candidate.artist)) 1.0 else similarityRatio(artist, candidate.artist)
            } else 1.0

            val combinedScore = (titleScore * 0.6) + (artistScore * 0.4)
            if (combinedScore > highestScore && titleScore > 0.70) {
                highestScore = combinedScore
                bestMatch = candidate
            }
        }

        bestMatch ?: candidates.firstOrNull()
    }

    private fun parseSearchResults(jsonString: String): List<Song> {
        val songs = mutableListOf<Song>()
        try {
            val root = JsonParser.parseString(jsonString).asJsonObject
            val results = root.getAsJsonArray("results") ?: return emptyList()

            for (element in results) {
                val item = element.asJsonObject
                val trackId = item.get("trackId")?.asString ?: item.get("collectionId")?.asString ?: continue
                val trackName = item.get("trackName")?.asString ?: ""
                val artistName = item.get("artistName")?.asString ?: "Unknown Artist"
                val collectionName = item.get("collectionName")?.asString ?: ""
                val trackViewUrl = item.get("trackViewUrl")?.asString ?: ""
                val previewUrl = item.get("previewUrl")?.asString
                val durationMs = item.get("trackTimeMillis")?.asLong ?: 0L

                // Upgrade artwork to ultra high-res 1200x1200bb for crystal clear display
                val rawArtwork = item.get("artworkUrl100")?.asString ?: ""
                val highResArtwork = rawArtwork.replace("100x100bb.jpg", "1200x1200bb.jpg")
                    .replace("100x100bb.png", "1200x1200bb.png")

                songs.add(
                    Song(
                        id = trackId,
                        title = trackName,
                        artist = artistName,
                        album = collectionName,
                        artworkUrl = highResArtwork,
                        durationMs = durationMs,
                        appleMusicUrl = trackViewUrl,
                        streamUrl = null, // Play full 24-bit FLAC only, no 30s previews
                        isLossless = true,
                        isHiRes = true,
                        codec = "FLAC 24-bit/48.0kHz",
                        bitDepth = 24,
                        sampleRate = 48000
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return songs
    }
}
