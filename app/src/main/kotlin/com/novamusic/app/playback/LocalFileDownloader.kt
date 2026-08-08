/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.novamusic.app.playback

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.novamusic.app.db.MusicDatabase
import com.novamusic.app.db.entities.SongEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of a single download handled by [LocalFileDownloader].
 */
data class LocalDownloadState(
    val songId: String,
    val title: String,
    val artist: String,
    val state: State,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val localPath: String? = null,
) {
    enum class State {
        QUEUED,
        DOWNLOADING,
        COMPLETED,
        FAILED,
    }
}

/**
 * Downloads a song's raw audio stream and saves it as a real file via MediaStore
 * (into Music/NovaMusic/), then marks the song's Room entity with isLocal=true and
 * localPath so the player plays the on-device file.
 *
 * The download itself runs inside a WorkManager [LocalFileDownloadWorker] so it
 * survives the app being closed or killed; this class only performs the actual
 * work and keeps an in-memory progress map for the UI.
 */
@Singleton
class LocalFileDownloader
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val downloadUtil: DownloadUtil,
    private val database: MusicDatabase,
) {
    private val _progress = MutableStateFlow<Map<String, LocalDownloadState>>(emptyMap())
    val progress: StateFlow<Map<String, LocalDownloadState>> = _progress.asStateFlow()

    init {
        // One-time startup cleanup: drop MediaStore entries + Room flags for app
        // downloads whose file is missing, empty, or clearly not audio.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { purgeCorruptedLocalFiles() }
        }
    }

    /**
     * Downloads the raw audio stream for [songId] and stores it as a real file.
     * Meant to be called from a WorkManager worker.
     *
     * [onProgress] is invoked on each throttled progress emission (at most every
     * [PROGRESS_EMIT_INTERVAL_MS] or every [PROGRESS_EMIT_DELTA] progress change) and
     * always on the terminal QUEUED/DOWNLOADING/COMPLETED/FAILED transitions, so callers
     * can drive a notification without spamming updates.
     */
    suspend fun download(
        songId: String,
        title: String,
        artist: String,
        onProgress: (suspend (LocalDownloadState) -> Unit)? = null,
    ) {
        val safeTitle = title.ifBlank { songId }
        val queued = LocalDownloadState(songId, safeTitle, artist, LocalDownloadState.State.QUEUED)
        _progress.update { map -> map + (songId to queued) }
        onProgress?.invoke(queued)
        try {
            val streamUrl = downloadUtil.resolveStreamUrl(songId)
            val downloading = LocalDownloadState(
                songId = songId,
                title = safeTitle,
                artist = artist,
                state = LocalDownloadState.State.DOWNLOADING,
            )
            _progress.update { map -> map + (songId to downloading) }
            onProgress?.invoke(downloading)

            val request = Request.Builder().url(streamUrl).build()
            downloadUtil.mediaOkHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        "HTTP ${response.code} while downloading $songId " +
                            "(Content-Type: ${response.header("Content-Type") ?: "unknown"})",
                    )
                }
                val body = response.body ?: throw IOException("Empty response body for $songId")
                val contentLength = body.contentLength()

                // Validate the response is actually audio before writing anything to disk.
                // YouTube's CDN can answer 200 with an HTML/JSON error page instead of the
                // stream (expired URL, rejected client, bot check); saving that as a .m4a
                // is what produced the "None of the available extractors could read the
                // stream" corrupt files.
                val rawContentType = response.header("Content-Type") ?: ""
                val contentMime = rawContentType.substringBefore(';').trim().lowercase()
                // resolveStreamUrl persists the chosen format on the query executor
                // fire-and-forget, so drain it before reading the expected mime back.
                val expectedMime = runCatching {
                    database.awaitIdle()
                    database.format(songId).first()?.mimeType
                }.getOrNull()
                if (!isPlausibleAudioContentType(contentMime, expectedMime)) {
                    val detail =
                        "HTTP ${response.code} | Content-Type: \"$rawContentType\" | " +
                            "expected audio, got non-audio (known format: ${expectedMime ?: "unknown"})"
                    Log.e(TAG, "Refusing to save non-audio response for $songId: $detail")
                    throw IOException("Non-audio response for $songId: $detail")
                }
                if (contentLength == 0L) {
                    val detail = "HTTP ${response.code} | Content-Type: \"$rawContentType\" | empty body"
                    Log.e(TAG, "Empty download response for $songId: $detail")
                    throw IOException("Empty download response for $songId: $detail")
                }

                val mimeType = contentMime.ifEmpty { expectedMime ?: "audio/mpeg" }

                val displayName =
                    "${sanitizeFileName(safeTitle)} - ${sanitizeFileName(artist)}.${extensionForMime(mimeType)}"
                val collection =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.TITLE, safeTitle)
                    put(MediaStore.Audio.Media.ARTIST, artist)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/NovaMusic")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                }
                val uri = context.contentResolver.insert(collection, values)
                    ?: throw IOException("Failed to create MediaStore entry for $songId")

                try {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        body.source().use { input ->
                            val buffer = Buffer()
                            var bytesDownloaded = 0L
                            // Progress throttling: updating _progress copies the whole map and
                            // triggers recomposition across every screen observing it. Firing on
                            // every 64KB buffer read (dozens of times per second) was the cause of
                            // app-wide slowness during downloads. Emit at most every 250ms OR every
                            // 2% progress change, whichever comes first. Terminal states below are
                            // not throttled and always emit.
                            var lastEmitAt = 0L
                            var lastEmittedProgress = -1f
                            while (true) {
                                val read = input.read(buffer, BUFFER_SIZE)
                                if (read == -1L) break
                                buffer.copyTo(output, read)
                                bytesDownloaded += read
                                val p =
                                    if (contentLength > 0) {
                                        (bytesDownloaded.toFloat() / contentLength).coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                val now = SystemClock.elapsedRealtime()
                                val progressDelta = p - lastEmittedProgress
                                if (now - lastEmitAt >= PROGRESS_EMIT_INTERVAL_MS || progressDelta >= PROGRESS_EMIT_DELTA) {
                                    lastEmitAt = now
                                    lastEmittedProgress = p
                                    val state = LocalDownloadState(
                                        songId = songId,
                                        title = safeTitle,
                                        artist = artist,
                                        state = LocalDownloadState.State.DOWNLOADING,
                                        progress = p,
                                        bytesDownloaded = bytesDownloaded,
                                        totalBytes = contentLength,
                                    )
                                    _progress.update { map -> map + (songId to state) }
                                    onProgress?.invoke(state)
                                }
                            }
                        }
                    } ?: throw IOException("Failed to open output stream for $songId")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                    }

                    val localPath = queryDataColumn(uri) ?: uri.toString()

                    database.query {
                        val existing = getSongByIdBlocking(songId)?.song
                        val updated =
                            (existing ?: SongEntity(id = songId, title = safeTitle)).copy(
                                isLocal = true,
                                localPath = localPath,
                                dateDownload = LocalDateTime.now(),
                            )
                        upsert(updated)
                    }

                    val completed = LocalDownloadState(
                        songId = songId,
                        title = safeTitle,
                        artist = artist,
                        state = LocalDownloadState.State.COMPLETED,
                        progress = 1f,
                        localPath = localPath,
                    )
                    _progress.update { map -> map + (songId to completed) }
                    onProgress?.invoke(completed)
                } catch (e: Exception) {
                    // Roll back the pending MediaStore entry on failure/cancellation.
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failed = LocalDownloadState(
                songId = songId,
                title = safeTitle,
                artist = artist,
                state = LocalDownloadState.State.FAILED,
                error = e.message,
            )
            _progress.update { map -> map + (songId to failed) }
            onProgress?.invoke(failed)
            throw e
        }
    }

    /**
     * Cancels any in-flight download and removes the local file + database flags
     * for a song downloaded by this app.
     */
    suspend fun deleteLocalFile(songId: String) {
        cancelWork(context, songId)
        val song = database.getSongById(songId)
        song?.song?.localPath?.let { path ->
            runCatching {
                val uri = android.net.Uri.parse(path)
                val deleteFile = when (uri.scheme) {
                    "content" -> true
                    "file" -> uri.path?.contains(FOLDER_NAME, ignoreCase = true) == true
                    else -> false
                }
                if (deleteFile) {
                    context.contentResolver.delete(uri, null, null)
                }
            }
        }
        database.query {
            val current = getSongByIdBlocking(songId)?.song ?: return@query
            update(current.copy(isLocal = false, localPath = null))
        }
        _progress.update { map -> map - songId }
    }

    /**
     * Cancels the WorkManager worker for [songId] and drops its progress entry.
     */
    fun cancelWork(context: Context, songId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(songId))
        _progress.update { map -> map - songId }
    }

    private fun queryDataColumn(uri: Uri): String? = runCatching {
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    /**
     * Scans songs marked as app-downloaded (isLocal=true, path under Music/NovaMusic)
     * and removes the MediaStore entry + resets the Room flags whenever the underlying
     * file is missing, empty, or clearly not audio (i.e. a corrupted download).
     * Returns the number of entries purged.
     */
    suspend fun purgeCorruptedLocalFiles(): Int {
        val active = _progress.value.filterValues {
            it.state == LocalDownloadState.State.QUEUED || it.state == LocalDownloadState.State.DOWNLOADING
        }.keys
        val localSongs = database.withTransaction { getLocalSongsBlocking() }
        val purged = localSongs.filter { song -> song.id !in active && isCorruptLocalFile(song.localPath.orEmpty()) }
        if (purged.isEmpty()) return 0

        purged.forEach { song -> song.localPath?.let { deleteMediaStoreEntry(it) } }
        database.withTransaction {
            for (song in purged) {
                // Skip individually: a row removed concurrently must not void the
                // flag reset for the remaining songs.
                val current = getSongByIdBlocking(song.id)?.song ?: continue
                update(current.copy(isLocal = false, localPath = null))
            }
        }
        Log.i(TAG, "Purged ${purged.size} corrupted local download(s): ${purged.joinToString { it.id }}")
        return purged.size
    }

    private fun isCorruptLocalFile(localPath: String): Boolean {
        if (localPath.isBlank()) return true // isLocal=true but no path — broken state
        val uri = runCatching { Uri.parse(localPath) }.getOrNull() ?: return false
        val path = localFilePath(localPath)
        if (path.isNotEmpty() && !path.contains(FOLDER_NAME, ignoreCase = true)) {
            // Not an app download (scanned library music lives elsewhere) — leave it alone.
            return false
        }
        val file = if (path.isNotEmpty()) File(path) else null
        if (file != null && !file.exists()) return true

        val length =
            if (file != null) {
                file.length()
            } else {
                // If the size can't be determined (MediaStore not ready at startup,
                // row not indexed yet), leave the entry alone — never purge on a
                // failed read, only on a *definite* corrupt state.
                runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull() ?: return false
            }
        if (length < MIN_VALID_FILE_SIZE) return true

        val stream =
            runCatching {
                file?.inputStream() ?: context.contentResolver.openInputStream(uri)
            }.getOrNull() ?: return false // can't read it — don't risk a false purge
        return stream.use { input -> !sniffAudioHeader(input) }
    }

    /**
     * Resolves a stored [localPath] (content:// uri, file:// uri, or plain path) to
     * a real filesystem path when possible; empty string when it can't be resolved.
     */
    private fun localFilePath(localPath: String): String {
        val uri = runCatching { Uri.parse(localPath) }.getOrNull() ?: return localPath
        return when (uri.scheme) {
            "content" -> queryDataColumn(uri).orEmpty()
            "file" -> uri.path.orEmpty()
            else -> localPath
        }
    }

    private fun deleteMediaStoreEntry(localPath: String) {
        runCatching {
            val uri = Uri.parse(localPath)
            when (uri.scheme) {
                "content" -> context.contentResolver.delete(uri, null, null)
                else -> {
                    val file = if (uri.scheme == "file") File(uri.path.orEmpty()) else File(localPath)
                    if (file.exists()) file.delete()
                }
            }
        }
    }

    private fun isPlausibleAudioContentType(contentMime: String, expectedMime: String?): Boolean {
        if (contentMime.isEmpty()) return true // no header — nothing to judge, fall back to expected
        if (contentMime.startsWith("audio/")) return true
        // Definite error-page / non-audio payloads.
        if (
            contentMime.startsWith("text/") ||
            contentMime.startsWith("image/") ||
            contentMime.startsWith("application/json") ||
            contentMime.startsWith("application/xml") ||
            contentMime.startsWith("application/x-www-form-urlencoded")
        ) {
            return false
        }
        val expected = expectedMime?.lowercase()
        if (expected != null && expected.startsWith("audio/")) {
            // CDNs sometimes label audio-only webm/mp4 streams as video/* or octet-stream.
            if (contentMime.startsWith("video/")) return true
            if (contentMime == "application/octet-stream") return true
            return contentMime == expected
        }
        return false
    }

    /**
     * Cheap magic-byte check: true when the stream looks like a known audio container
     * (mp3/ID3, flac, ogg/opus, wav, aiff, m4a/ftyp, webm/EBML, MPEG/ADTS frame sync).
     */
    private fun sniffAudioHeader(input: InputStream): Boolean {
        val header = ByteArray(16)
        val n = input.read(header)
        if (n < 4) return false
        fun ascii(offset: Int, text: String): Boolean {
            val signature = text.toByteArray(Charsets.US_ASCII)
            if (offset + signature.size > n) return false
            return signature.indices.all { i -> header[offset + i] == signature[i] }
        }
        return ascii(0, "ID3") ||
            ascii(0, "fLaC") ||
            ascii(0, "OggS") ||
            ascii(0, "RIFF") ||
            ascii(0, "FORM") ||
            ascii(4, "ftyp") ||
            (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()) ||
            (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0)
    }

    companion object {
        private const val TAG = "LocalFileDownloader"
        private const val BUFFER_SIZE = 64L * 1024L
        private const val MIN_VALID_FILE_SIZE = 8L * 1024L
        private const val FOLDER_NAME = "NovaMusic"
        const val PROGRESS_EMIT_INTERVAL_MS = 250L
        const val PROGRESS_EMIT_DELTA = 0.02f

        fun uniqueWorkName(songId: String) = "local-download-$songId"

        /**
         * Enqueues a download inside a WorkManager worker so it survives the app
         * being closed or killed. Re-enqueuing replaces any existing work for the
         * same song.
         */
        fun enqueue(context: Context, songId: String, title: String, artist: String) {
            val request =
                OneTimeWorkRequestBuilder<LocalFileDownloadWorker>()
                    .setInputData(
                        workDataOf(
                            LocalFileDownloadWorker.KEY_SONG_ID to songId,
                            LocalFileDownloadWorker.KEY_TITLE to title,
                            LocalFileDownloadWorker.KEY_ARTIST to artist,
                        ),
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(songId),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Unknown" }

private fun extensionForMime(mime: String): String = when {
    mime.contains("opus") || mime.contains("webm") -> "opus"
    mime.contains("ogg") -> "ogg"
    mime.contains("flac") -> "flac"
    mime.contains("wav") -> "wav"
    mime.contains("aac") -> "aac"
    mime.contains("mp4") || mime.contains("mpeg") || mime.contains("mp3") -> "m4a"
    else -> "m4a"
}
