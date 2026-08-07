/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.novamusic.app.playback

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novamusic.app.di.LocalFileDownloaderEntryPoint
import dagger.hilt.EntryPointAccessors
import kotlinx.coroutines.CancellationException

/**
 * WorkManager worker that downloads a song to disk via [LocalFileDownloader].
 * Running inside WorkManager lets downloads continue when the app is closed
 * or killed; failed downloads are retried a few times.
 */
class LocalFileDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val songId = inputData.getString(KEY_SONG_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: songId
        val artist = inputData.getString(KEY_ARTIST).orEmpty()

        return try {
            val downloader =
                EntryPointAccessors
                    .fromApplication(applicationContext, LocalFileDownloaderEntryPoint::class.java)
                    .localFileDownloader()
            downloader.download(songId, title, artist)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_SONG_ID = "song_id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        private const val MAX_RETRIES = 3
    }
}
