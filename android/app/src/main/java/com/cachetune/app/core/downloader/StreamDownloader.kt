package com.cachetune.app.core.downloader

import android.content.Context
import com.cachetune.app.core.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Stage 3: High-Speed Stream Downloader (100% On-Device)
 * 
 * Downloads ALAC stream segments directly to the device's local cache.
 * Implements chunked streaming so audio starts playing after the first
 * 256KB buffer, delivering instant sub-second playback.
 */
class StreamDownloader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    private val cacheDir: File = File(context.cacheDir, "audio_cache").apply {
        if (!exists()) mkdirs()
    }

    fun getCachedFile(songId: String): File {
        val flac = File(cacheDir, "$songId.flac")
        if (flac.exists() && flac.length() > 50_000) return flac
        val m4a = File(cacheDir, "$songId.m4a")
        if (m4a.exists() && m4a.length() > 50_000) return m4a
        return flac
    }

    fun isSongCached(songId: String): Boolean {
        val flac = File(cacheDir, "$songId.flac")
        if (flac.exists() && flac.length() > 50_000) return true
        val m4a = File(cacheDir, "$songId.m4a")
        return m4a.exists() && m4a.length() > 50_000
    }

    /**
     * Downloads the audio stream directly with progressive writing.
     * Invokes [onReadyToPlay] as soon as the initial audio buffer is written (< 300ms).
     */
    suspend fun downloadSongStream(
        song: Song,
        targetUrl: String,
        onReadyToPlay: (File) -> Unit,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val isFlac = targetUrl.contains(".flac", ignoreCase = true)
        val outputFile = File(cacheDir, if (isFlac) "${song.id}.flac" else "${song.id}.m4a")
        if (outputFile.exists() && outputFile.length() > 50_000) {
            onReadyToPlay(outputFile)
            onProgress(1.0f)
            return@withContext outputFile
        }

        val request = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val tempFile = File(cacheDir, "${song.id}.tmp")

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download failed with code: ${response.code}")
                }

                val body = response.body ?: throw IllegalStateException("Empty response body")
                val contentLength = body.contentLength()
                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(tempFile)

                val buffer = ByteArray(32 * 1024) // 32KB buffer
                var bytesRead: Int
                var totalBytesRead = 0L
                var readyNotified = false

                // Notify ready after 256KB for instant playback start
                val readyThreshold = 256 * 1024L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (!readyNotified && totalBytesRead >= readyThreshold) {
                        readyNotified = true
                        onReadyToPlay(tempFile)
                    }

                    if (contentLength > 0) {
                        val progress = totalBytesRead.toFloat() / contentLength.toFloat()
                        onProgress(progress)
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                if (outputFile.exists()) {
                    outputFile.delete()
                }

                if (tempFile.renameTo(outputFile)) {
                    if (!readyNotified) onReadyToPlay(outputFile)
                    onProgress(1.0f)
                    outputFile
                } else {
                    tempFile
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }
}
