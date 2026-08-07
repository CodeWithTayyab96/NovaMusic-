/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.novamusic.app.playback

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Request
import okio.Buffer
import java.io.IOException
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

    /**
     * Downloads the raw audio stream for [songId] and stores it as a real file.
     * Meant to be called from a WorkManager worker.
     */
    suspend fun download(songId: String, title: String, artist: String) {
        val safeTitle = title.ifBlank { songId }
        _progress.update { map ->
            map + (songId to LocalDownloadState(songId, safeTitle, artist, LocalDownloadState.State.QUEUED))
        }
        try {
            val streamUrl = downloadUtil.resolveStreamUrl(songId)
            _progress.update { map ->
                map[songId]?.let { map + (songId to it.copy(state = LocalDownloadState.State.DOWNLOADING)) } ?: map
            }

            val request = Request.Builder().url(streamUrl).build()
            downloadUtil.mediaOkHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} while downloading $songId")
                }
                val body = response.body ?: throw IOException("Empty response body for $songId")
                val contentLength = body.contentLength()
                val mimeType = response.header("Content-Type") ?: "audio/mpeg"

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
                                _progress.update { map ->
                                    map[songId]?.let {
                                        map + (songId to it.copy(
                                            state = LocalDownloadState.State.DOWNLOADING,
                                            progress = p,
                                            bytesDownloaded = bytesDownloaded,
                                            totalBytes = contentLength,
                                        ))
                                    } ?: map
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

                    _progress.update { map ->
                        map[songId]?.let {
                            map + (songId to it.copy(
                                state = LocalDownloadState.State.COMPLETED,
                                progress = 1f,
                                localPath = localPath,
                            ))
                        } ?: map
                    }
                } catch (e: Exception) {
                    // Roll back the pending MediaStore entry on failure/cancellation.
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _progress.update { map ->
                map + (songId to (map[songId] ?: LocalDownloadState(
                    songId,
                    safeTitle,
                    artist,
                    LocalDownloadState.State.FAILED,
                )).copy(
                    state = LocalDownloadState.State.FAILED,
                    error = e.message,
                ))
            }
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

    private fun queryDataColumn(uri: android.net.Uri): String? = runCatching {
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    companion object {
        private const val BUFFER_SIZE = 64L * 1024L
        private const val FOLDER_NAME = "NovaMusic"

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
