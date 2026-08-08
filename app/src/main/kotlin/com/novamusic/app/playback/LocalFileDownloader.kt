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

                // The resolved format's mimeType (e.g. "audio/webm", "audio/mp4") is the
                // authoritative container for the stream we selected; the CDN's Content-Type
                // header can be missing or mislabeled (video/webm, application/octet-stream)
                // for audio-only streams, so prefer it when deciding the container/extension.
                val containerMime = expectedMime
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() }
                    ?: contentMime
                val extension = extensionForMime(containerMime)
                // MediaStore MIME_TYPE: keep a real audio/* value even when the CDN labels an
                // audio-only stream as video/* or octet-stream.
                val storeMime = when {
                    containerMime.startsWith("audio/") -> containerMime
                    expectedMime?.startsWith("audio/") == true -> expectedMime
                    else -> mimeForExtension(extension)
                }

                val displayName =
                    "${sanitizeFileName(safeTitle)} - ${sanitizeFileName(artist)}.$extension"
                val collection =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, storeMime)
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
                    var bytesDownloaded = 0L
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        body.source().use { input ->
                            val buffer = Buffer()
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

                    // 1) Completeness check: a stream that ends early (or is cut off mid-transfer
                    // by the CDN/proxy) yields a truncated file that plays inside ExoPlayer (which
                    // sniffs and tolerates partial containers) but fails in strict external players
                    // like VLC. Compare what we wrote against the promised Content-Length and fail
                    // loudly instead of keeping a broken file. Unknown lengths (-1) can't be checked.
                    if (contentLength > 0 && bytesDownloaded != contentLength) {
                        throw IOException(
                            "Download incomplete: got $bytesDownloaded of $contentLength bytes for $songId",
                        )
                    }

                    // 2) Container sanity check: confirm the bytes we saved actually start with a
                    // known audio container magic and log the detected container so extension/container
                    // mismatches (e.g. a WebM file saved as .m4a) are visible in logcat before
                    // testing in an external player. Throws (and rolls back) on garbage payloads.
                    verifySavedContainer(uri, songId, extension)

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
     * Identifies the audio container from the stream's leading magic bytes. Returns a
     * human-readable container name, or null when the payload isn't a known audio
     * container (error page, garbage, empty file).
     */
    private fun detectContainer(input: InputStream): String? {
        val header = ByteArray(16)
        val n = input.read(header)
        if (n < 4) return null
        fun ascii(offset: Int, text: String): Boolean {
            val signature = text.toByteArray(Charsets.US_ASCII)
            if (offset + signature.size > n) return false
            return signature.indices.all { i -> header[offset + i] == signature[i] }
        }
        return when {
            ascii(0, "ID3") -> "MP3 (ID3)"
            ascii(0, "fLaC") -> "FLAC"
            ascii(0, "OggS") -> "Ogg (Opus/Vorbis)"
            ascii(0, "RIFF") -> "WAV"
            ascii(0, "FORM") -> "AIFF"
            ascii(4, "ftyp") -> "MP4/M4A"
            header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte() -> "WebM/EBML"
            header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0 -> "MP3 (MPEG sync)"
            else -> null
        }
    }

    /** Cheap magic-byte check: true when the stream looks like a known audio container. */
    private fun sniffAudioHeader(input: InputStream): Boolean = detectContainer(input) != null

    /**
     * Re-opens the just-written file, confirms it starts with a known audio container
     * magic, and logs the detected container + expected extension so mismatches are
     * visible in logcat. Throws (rolling back the download) when the payload has no
     * recognizable audio container at all.
     */
    private fun verifySavedContainer(uri: Uri, songId: String, extension: String) {
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: throw IOException("Cannot re-open downloaded file for $songId to verify it")
        val container = stream.use { detectContainer(it) }
            ?: throw IOException(
                "Downloaded file for $songId has no recognizable audio container — refusing to keep it",
            )
        val expected = when (extension) {
            "webm" -> "WebM/EBML"
            "m4a" -> "MP4/M4A"
            else -> null
        }
        val mismatch = expected != null && container != expected
        Log.i(
            TAG,
            "Downloaded $songId: container=$container extension=.$extension" +
                if (mismatch) " — WARNING: container does not match extension (expected $expected)" else "",
        )
        if (mismatch) {
            Log.w(TAG, "Container/extension mismatch for $songId — external players may refuse this file")
        }
    }

    /** Maps a file extension back to a sensible audio/* MIME type for MediaStore. */
    private fun mimeForExtension(extension: String): String = when (extension) {
        "webm" -> "audio/webm"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        else -> "audio/mp4"
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

/**
 * Maps a container mime (e.g. "audio/webm", "audio/mp4") to the file extension that
 * matches the actual container bytes. YouTube's audio-only webm streams (itag
 * 249/250/251 — opus or vorbis codec inside) are served in a WebM (EBML) container,
 * NOT as a bare Ogg-Opus stream, so they must be saved as ".webm": a ".opus"
 * extension implies a raw Ogg-Opus file, which is a different container and confuses
 * strict external players like VLC. Only mp4/m4a-family streams (itag 139/140 — AAC
 * inside) get ".m4a". The codec inside never changes the container extension.
 */
internal fun extensionForMime(mime: String): String {
    val container = mime.substringBefore(';').trim().lowercase()
    return when {
        container.contains("webm") -> "webm"
        container.contains("ogg") -> "ogg"
        container.contains("flac") -> "flac"
        container.contains("wav") -> "wav"
        container.contains("aac") -> "aac"
        container.contains("mp4") || container.contains("m4a") -> "m4a"
        container.contains("mp3") || container.contains("mpeg") -> "mp3"
        else -> "m4a"
    }
}
