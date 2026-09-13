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
import okio.BufferedSource
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
    val isPaused: Boolean = false,
    val stopCount: Int = 0,
) {
    enum class State {
        QUEUED,
        DOWNLOADING,
        COMPLETED,
        FAILED,
    }

    val isActive: Boolean get() =
        this.state == State.QUEUED || this.state == State.DOWNLOADING
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
            var currentStreamUrl = try {
                downloadUtil.resolveStreamUrl(songId, preferAac = true)
            } catch (e: Exception) {
                val reason = when {
                    e.message?.contains("timed out", ignoreCase = true) == true ->
                        "Network timeout — check your connection"
                    e.message?.contains("403") == true ||
                        e.message?.contains("Forbidden", ignoreCase = true) == true ->
                        "Song unavailable from source (403 Forbidden)"
                    e.message?.contains("404") == true ||
                        e.message?.contains("Not Found", ignoreCase = true) == true ->
                        "Song not found on source"
                    e.message?.contains("not available", ignoreCase = true) == true ||
                        e.message?.contains("unavailable", ignoreCase = true) == true ->
                        "Song is not available in your region"
                    else ->
                        "Could not resolve stream URL"
                }
                Log.e(TAG, "Stream URL resolution failed for $songId: ${e.message}")
                throw IOException("$reason (song: $safeTitle by $artist)", e)
            }
            val downloading = LocalDownloadState(
                songId = songId,
                title = safeTitle,
                artist = artist,
                state = LocalDownloadState.State.DOWNLOADING,
            )
            _progress.update { map -> map + (songId to downloading) }
            onProgress?.invoke(downloading)

            // Make initial probe request to inspect headers & total content length
            var initialRequest = Request.Builder()
                .url(currentStreamUrl)
                .header("Range", "bytes=0-")
                .build()

            var response = downloadUtil.mediaOkHttpClient.newCall(initialRequest).execute()
            if (response.code == 403) {
                // If URL expired or 403 returned, invalidate cache and re-resolve stream URL once
                response.close()
                com.novamusic.app.utils.YTPlayerUtils.invalidateCachedStreamUrls(songId)
                currentStreamUrl = downloadUtil.resolveStreamUrl(songId, preferAac = true)
                initialRequest = Request.Builder()
                    .url(currentStreamUrl)
                    .header("Range", "bytes=0-")
                    .build()
                response = downloadUtil.mediaOkHttpClient.newCall(initialRequest).execute()
            }

            response.use { initialResp ->
                if (!initialResp.isSuccessful && initialResp.code != 206) {
                    throw IOException(
                        "HTTP ${initialResp.code} while downloading $songId " +
                            "(Content-Type: ${initialResp.header("Content-Type") ?: "unknown"})",
                    )
                }

                val body = initialResp.body ?: throw IOException("Empty response body for $songId")
                
                // Determine Content-Length from Content-Range or Content-Length header
                val contentRangeHeader = initialResp.header("Content-Range")
                val totalLengthFromRange = contentRangeHeader?.substringAfter('/')?.trim()?.toLongOrNull()
                val contentLength = totalLengthFromRange ?: body.contentLength()

                val rawContentType = initialResp.header("Content-Type") ?: ""
                val contentMime = rawContentType.substringBefore(';').trim().lowercase()

                val expectedMime = runCatching {
                    database.awaitIdle()
                    database.format(songId).first()?.mimeType
                }.getOrNull()

                if (!isPlausibleAudioContentType(contentMime, expectedMime)) {
                    val detail =
                        "HTTP ${initialResp.code} | Content-Type: \"$rawContentType\" | " +
                            "expected audio, got non-audio (known format: ${expectedMime ?: "unknown"})"
                    Log.e(TAG, "Refusing to save non-audio response for $songId: $detail")
                    throw IOException("Non-audio response for $songId: $detail")
                }

                val containerMime = expectedMime
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() }
                    ?: contentMime
                val extension = extensionForMime(containerMime)
                val storeMime = storeMimeForContainer(containerMime, expectedMime, extension)

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
                    var resumeAttempts = 0
                    val MAX_RESUME_ATTEMPTS = 5
                    var lastEmitAt = 0L
                    var lastEmittedProgress = -1f

                    // The MediaStore output stream is opened ONCE and stays open for the whole
                    // download. Re-opening it per chunk with mode "wa" does not append on
                    // MediaStore (each chunk overwrote from byte 0), so only the final chunk
                    // survived on disk and the container verification below rejected the file.
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        val buffer = Buffer()

                        // Streams one response body into `output`, emitting throttled progress.
                        // Declared as a suspend function value so it can still call the suspend
                        // onProgress callback from inside this non-suspend `use` scope.
                        val drain: suspend (BufferedSource) -> Long = { input ->
                            var written = 0L
                            while (true) {
                                val read = input.read(buffer, BUFFER_SIZE)
                                if (read == -1L) break
                                // Buffer.copyTo() only PEEKS — it does not consume — so writing
                                // with copyTo() alone re-wrote the same first 64 KB on every
                                // iteration. readByteArray() consumes exactly what was read.
                                output.write(buffer.readByteArray(read))
                                bytesDownloaded += read
                                written += read

                                val p = if (contentLength > 0) {
                                    (bytesDownloaded.toFloat() / contentLength).coerceIn(0f, 1f)
                                } else 0f

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
                            written
                        }

                        // The probe request above already asked for the whole stream
                        // (Range: bytes=0-), so its body IS chunk 0 — write it directly.
                        // Issuing a second bytes=0- request against the same tokenized
                        // googlevideo URL made the CDN serve the file twice, and those
                        // repeat responses can come back as a non-audio stub, which is
                        // what ended up at the head of the saved file.
                        drain(body.source())

                        // Resume loop: only for known-length streams that ended early.
                        // An unknown length (chunked body) was fully delivered above.
                        while (contentLength > 0 && bytesDownloaded < contentLength) {
                            val req = Request.Builder()
                                .url(currentStreamUrl)
                                .header("Range", "bytes=$bytesDownloaded-")
                                .build()

                            val callResp = try {
                                downloadUtil.mediaOkHttpClient.newCall(req).execute()
                            } catch (e: Exception) {
                                if (resumeAttempts < MAX_RESUME_ATTEMPTS) {
                                    resumeAttempts++
                                    Log.w(TAG, "Stream interrupted for $songId at $bytesDownloaded bytes, retrying ($resumeAttempts/$MAX_RESUME_ATTEMPTS)...")
                                    Thread.sleep(500L)
                                    continue
                                } else throw e
                            }

                            var chunkRead = 0L
                            callResp.use { chunkResp ->
                                if (chunkResp.code == 403 && resumeAttempts < MAX_RESUME_ATTEMPTS) {
                                    resumeAttempts++
                                    com.novamusic.app.utils.YTPlayerUtils.invalidateCachedStreamUrls(songId)
                                    currentStreamUrl = downloadUtil.resolveStreamUrl(songId, preferAac = true)
                                    return@use
                                }
                                if (!chunkResp.isSuccessful && chunkResp.code != 206) {
                                    throw IOException("HTTP ${chunkResp.code} while resuming download for $songId at $bytesDownloaded bytes")
                                }

                                // Same non-audio guard as the probe: a resumed response can
                                // also be a stub page. Refuse before appending it mid-file.
                                val chunkCt = (chunkResp.header("Content-Type") ?: "")
                                    .substringBefore(';').trim().lowercase()
                                if (!isPlausibleAudioContentType(chunkCt, expectedMime)) {
                                    throw IOException(
                                        "Resume response for $songId is non-audio: HTTP ${chunkResp.code} | " +
                                            "Content-Type: \"$chunkCt\" — refusing to append it",
                                    )
                                }

                                val chunkBody = chunkResp.body ?: return@use
                                chunkRead = drain(chunkBody.source())
                            }

                            if (chunkRead == 0L && bytesDownloaded < contentLength) {
                                if (resumeAttempts < MAX_RESUME_ATTEMPTS) {
                                    resumeAttempts++
                                    Thread.sleep(500L)
                                    continue
                                } else {
                                    throw IOException("Download incomplete: got $bytesDownloaded of $contentLength bytes for $songId")
                                }
                            }
                        }
                    }

                    if (contentLength > 0 && bytesDownloaded < contentLength) {
                        throw IOException("Download incomplete: got $bytesDownloaded of $contentLength bytes for $songId")
                    }
                    if (bytesDownloaded == 0L) {
                        throw IOException(
                            "Stream returned no audio data for $songId " +
                                "(HTTP ${initialResp.code}, Content-Type: \"$rawContentType\", " +
                                "expected $contentLength bytes)",
                        )
                    }

                    Log.i(TAG, "Downloaded $songId: wrote $bytesDownloaded of $contentLength bytes")

                    // Container magic check. On failure, attach what actually landed on disk
                    // so the cause is readable from logcat/notification instead of guessed at.
                    try {
                        verifySavedContainer(uri, songId, extension)
                    } catch (e: IOException) {
                        val head = headPreview(uri)
                        Log.e(
                            TAG,
                            "Container check failed for $songId: wrote $bytesDownloaded of " +
                                "$contentLength bytes, HTTP ${initialResp.code}, " +
                                "Content-Type: \"$rawContentType\", head=[$head]",
                        )
                        throw IOException(
                            "${e.message} — wrote $bytesDownloaded of $contentLength bytes, head=[$head]",
                            e,
                        )
                    }

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
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    runCatching {
                        database.query {
                            val current = getSongByIdBlocking(songId)?.song ?: return@query
                            if (current.isLocal) {
                                update(current.copy(isLocal = false, localPath = null))
                            }
                        }
                    }
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
     * Marks an in-flight download as paused (waiting for a retry / for network
     * constraints to be met again). The download is NOT cancelled: WorkManager
     * re-runs the worker later and [download] resumes from the retry attempt.
     * The progress entry keeps the last known DOWNLOADING state so the Download
     * Queue still shows the row (with a "paused" indicator) instead of the
     * download silently vanishing.
     *
     * Returns the number of times this download has been paused within the
     * current session (including this call). Callers use it as a ceiling so a
     * download that keeps getting interrupted (e.g. flaky network) eventually
     * transitions to a permanent failure instead of cycling forever.
     */
    suspend fun markPaused(
        songId: String,
        title: String,
        artist: String,
        error: String? = null,
    ): Int {
        val safeTitle = title.ifBlank { songId }
        var newStopCount = 0
        _progress.update { map ->
            val current = map[songId]
            newStopCount = (current?.stopCount ?: 0) + 1
            map + (
                songId to LocalDownloadState(
                    songId = songId,
                    title = safeTitle,
                    artist = artist,
                    state = LocalDownloadState.State.DOWNLOADING,
                    progress = current?.progress ?: 0f,
                    bytesDownloaded = current?.bytesDownloaded ?: 0L,
                    totalBytes = current?.totalBytes ?: 0L,
                    error = error ?: current?.error,
                    isPaused = true,
                    stopCount = newStopCount,
                )
                )
        }
        return newStopCount
    }

    /**
     * Marks a download as permanently failed without throwing (used when a
     * download exceeded its pause/resume ceiling). The queue row stays visible
     * with the FAILED state so the user can remove it.
     */
    suspend fun markFailed(
        songId: String,
        title: String,
        artist: String,
        error: String?,
    ) {
        val safeTitle = title.ifBlank { songId }
        _progress.update { map ->
            val current = map[songId]
            map + (
                songId to LocalDownloadState(
                    songId = songId,
                    title = safeTitle,
                    artist = artist,
                    state = LocalDownloadState.State.FAILED,
                    progress = current?.progress ?: 0f,
                    error = error,
                    isPaused = false,
                )
                )
        }
    }

    /**
     * Cancels any in-flight download and removes the local file + database flags
     * for a song downloaded by this app.
     */
    suspend fun deleteLocalFile(songId: String) {
        cancelWork(context, songId)
        val song = database.getSongById(songId)
        song?.song?.localPath?.let { path -> deleteStoredFile(path) }
        database.query {
            val current = getSongByIdBlocking(songId)?.song ?: return@query
            update(current.copy(isLocal = false, localPath = null))
        }
        _progress.update { map -> map - songId }
    }

    /**
     * Deletes the on-disk file behind a stored localPath.
     *
     * localPath is normally the raw MediaStore DATA column, which is a plain
     * filesystem path with NO scheme (e.g. /storage/emulated/0/Music/NovaMusic/x.m4a).
     * The previous implementation only handled "content" and "file" schemes and
     * silently skipped every other case, so deleting a download cleared the database
     * flags but left the audio file on disk: it disappeared from the app's download
     * list while still being present in a file manager and playable in VLC.
     *
     * Only paths inside FOLDER_NAME are ever removed, so an unexpected or malformed
     * path cannot delete unrelated user media.
     */
    private fun deleteStoredFile(path: String) {
        if (!isDeletableDownloadPath(path, FOLDER_NAME)) return
        runCatching {
            if (path.startsWith("content://", ignoreCase = true)) {
                // Removing the MediaStore row also removes the underlying file.
                context.contentResolver.delete(Uri.parse(path), null, null)
                return@runCatching
            }
            val filePath =
                if (path.startsWith("file://", ignoreCase = true)) {
                    path.removePrefix("file://")
                } else {
                    path
                }
            val file = File(filePath)
            if (file.exists() && !file.delete()) {
                // Never fail silently: a surviving file is exactly the reported bug —
                // the song left the list while the audio stayed on disk.
                Log.w(TAG, "Failed to delete downloaded file: $filePath")
            }
        }
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
        if (contentMime.isEmpty()) return true
        if (contentMime.startsWith("audio/")) return true
        if (contentMime.startsWith("video/")) return true // YouTube audio-only streams are served under video/webm or video/mp4
        if (contentMime == "application/octet-stream") return true
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
            return true
        }
        return true
    }

    /**
     * Identifies the audio container from the stream's leading magic bytes. Reads up to 512
     * bytes in a loop to handle streams that return small chunks or use non-zero box offsets.
     * Returns a human-readable container name, or null when the payload isn't a known audio container.
     */


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

    /**
     * Reads the first [count] bytes of a saved download and renders them as hex plus a
     * printable-ASCII preview. Diagnostic only: it makes a container-verification failure
     * readable straight from logcat / the failure notification instead of being guessed
     * at. Never includes URLs, signatures or credentials.
     */
    private fun headPreview(uri: Uri, count: Int = 16): String {
        val buf = ByteArray(count)
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return "unreadable"
        return stream.use { input ->
            var off = 0
            while (off < count) {
                val r = input.read(buf, off, count - off)
                if (r <= 0) break
                off += r
            }
            if (off == 0) return "empty"
            val head = buf.copyOf(off)
            val hex = head.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            val ascii = head.joinToString("") { b ->
                val c = b.toInt() and 0xFF
                if (c in 32..126) c.toChar().toString() else "."
            }
            "$hex \"$ascii\""
        }
    }

    /** Maps a file extension back to a sensible audio MIME type for MediaStore. */
    internal fun mimeForExtension(extension: String): String = when (extension) {
        "webm" -> "audio/x-matroska"
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
                    .addTag(LocalFileDownloadWorker.WORK_TAG)
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

internal fun storeMimeForContainer(
    containerMime: String,
    expectedMime: String?,
    extension: String
): String {
    val cleanContainer = containerMime.substringBefore(';').trim().lowercase()
    val cleanExpected = expectedMime?.substringBefore(';')?.trim()?.lowercase()
    return when {
        cleanContainer.contains("webm") -> "audio/x-matroska"
        cleanContainer.startsWith("audio/") -> cleanContainer
        cleanExpected?.contains("webm") == true -> "audio/x-matroska"
        cleanExpected?.startsWith("audio/") == true -> cleanExpected
        else -> when (extension) {
            "webm" -> "audio/x-matroska"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            else -> "audio/mp4"
        }
    }
}

internal fun detectContainer(input: InputStream): String? {
    val header = ByteArray(512)
    var totalRead = 0
    while (totalRead < header.size) {
        val bytesRead = input.read(header, totalRead, header.size - totalRead)
        if (bytesRead <= 0) break
        totalRead += bytesRead
    }
    if (totalRead < 4) return null

    fun hasAscii(offset: Int, text: String): Boolean {
        val sig = text.toByteArray(Charsets.US_ASCII)
        if (offset + sig.size > totalRead) return false
        return sig.indices.all { i -> header[offset + i] == sig[i] }
    }

    fun findAscii(text: String, maxSearch: Int = totalRead): Int {
        val sig = text.toByteArray(Charsets.US_ASCII)
        if (sig.size > totalRead) return -1
        val limit = (maxSearch - sig.size).coerceAtMost(totalRead - sig.size)
        for (i in 0..limit) {
            if (sig.indices.all { j -> header[i + j] == sig[j] }) return i
        }
        return -1
    }

    // Check for WebM/EBML magic: 0x1A 0x45 0xDF 0xA3 in first 64 bytes
    for (i in 0..(totalRead - 4).coerceAtMost(60)) {
        if (header[i] == 0x1A.toByte() &&
            header[i + 1] == 0x45.toByte() &&
            header[i + 2] == 0xDF.toByte() &&
            header[i + 3] == 0xA3.toByte()
        ) {
            return "WebM/EBML"
        }
    }

    // Check for Ogg container: "OggS" in first 64 bytes
    if (findAscii("OggS", 64) != -1) return "Ogg (Opus/Vorbis)"

    // Check for FLAC container: "fLaC" in first 64 bytes
    if (findAscii("fLaC", 64) != -1) return "FLAC"

    // Check for MP3 ID3 header
    if (hasAscii(0, "ID3") || findAscii("ID3", 32) != -1) return "MP3 (ID3)"

    // Check for WAV or AIFF
    if (findAscii("RIFF", 16) != -1) return "WAV"
    if (findAscii("FORM", 16) != -1) return "AIFF"

    // Check for MP4/M4A / ISOBMFF boxes anywhere in first 256 bytes
    // Common MP4 boxes: "ftyp", "styp", "moof", "sidx", "mdat", "free", "skip", "wide"
    val mp4Boxes = listOf("ftyp", "styp", "moof", "sidx", "mdat", "free", "skip", "wide")
    for (box in mp4Boxes) {
        if (findAscii(box, 256) != -1) {
            return "MP4/M4A"
        }
    }

    // Check for AAC ADTS or MPEG audio frame sync
    for (i in 0..(totalRead - 2).coerceAtMost(256)) {
        val b0 = header[i].toInt() and 0xFF
        val b1 = header[i + 1].toInt() and 0xFF
        if (b0 == 0xFF) {
            // AAC ADTS frame sync: 12 bits of 1s (0xFF 0xF0..0xFF 0xF9)
            if ((b1 and 0xF6) == 0xF0) return "AAC (ADTS)"
            // MPEG audio frame sync: 11 bits of 1s (0xFF 0xE0..0xFF 0xFF)
            if ((b1 and 0xE0) == 0xE0) return "MP3 (MPEG sync)"
        }
    }

    return null
}

/**
 * Whether [path] refers to something the app is allowed to delete.
 *
 * The stored localPath is normally the raw MediaStore DATA column, i.e. a plain
 * filesystem path with NO URI scheme. Deletion used to branch on Uri.scheme and
 * silently skip anything that was neither "content" nor "file", so scheme-less
 * paths were never deleted. This predicate is deliberately pure string logic so it
 * can be unit tested on the JVM without a device.
 *
 * Only paths inside our own download folder are accepted, so an unexpected or
 * malformed path can never delete unrelated user media or the playback cache.
 */
internal fun isDeletableDownloadPath(path: String, folderName: String = "NovaMusic"): Boolean {
    if (path.isBlank()) return false
    if (path.startsWith("content://", ignoreCase = true)) return true
    val filePath =
        if (path.startsWith("file://", ignoreCase = true)) {
            path.removePrefix("file://")
        } else {
            path
        }
    return filePath.isNotEmpty() && filePath.contains(folderName, ignoreCase = true)
}
