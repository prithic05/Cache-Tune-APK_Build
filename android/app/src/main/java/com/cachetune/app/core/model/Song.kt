package com.cachetune.app.core.model

import kotlinx.serialization.Serializable

/**
 * Universal Song model representing tracks across Apple Music, YouTube Music, and local cache.
 */
@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String = "",
    val durationMs: Long = 0L,
    val appleMusicUrl: String? = null,
    val streamUrl: String? = null,
    val isLossless: Boolean = true,
    val isHiRes: Boolean = false,
    val codec: String = "2296 kbps • 96.0 kHz",
    val bitDepth: Int = 24,
    val sampleRate: Int = 48000,
    val bitrate: Int? = null,
    val lyrics: String? = null,
    val status: String = "ready",
    val entryId: String? = null,
    val phase: String? = null,
    val subtitleOverlay: String? = null,
    val isPlaylistCard: Boolean = false
) {
    val durationFormatted: String
        get() {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val audioQualityBadge: String
        get() {
            val rateKhz = if (sampleRate > 1000) "%.1f kHz".format(sampleRate / 1000.0) else "$sampleRate Hz"
            return if (bitrate != null && bitrate > 0) {
                "$bitrate kbps • $rateKhz"
            } else {
                "${bitDepth}bit • $rateKhz"
            }
        }

    val isApple: Boolean
        get() = !appleMusicUrl.isNullOrBlank() || id.startsWith("apple_")
}

data class HomeSection(
    val title: String,
    val songs: List<Song>,
    val isGrid: Boolean = false
)

data class LyricLine(
    val timeMs: Long,
    val text: String
)
