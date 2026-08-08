/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.novamusic.app.playback

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.novamusic.app.di.LocalFileDownloaderEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException

/**
 * WorkManager worker that downloads a song to disk via [LocalFileDownloader].
 * Running inside WorkManager lets downloads continue when the app is closed
 * or killed; failed downloads are retried a few times.
 *
 * The worker runs as a foreground worker so it survives app close/kill. It shows a
 * Spotify-style notification: a per-song progress notification with a Cancel action,
 * grouped under a single "Downloading N songs" summary when several downloads run at
 * once. When multiple workers are alive the summary is refreshed from the shared
 * [LocalFileDownloader.progress] map.
 */
class LocalFileDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val songId = inputData.getString(KEY_SONG_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: songId
        val artist = inputData.getString(KEY_ARTIST).orEmpty()

        DownloadNotificationManager.ensureChannel(applicationContext)

        val downloader =
            EntryPointAccessors
                .fromApplication(applicationContext, LocalFileDownloaderEntryPoint::class.java)
                .localFileDownloader()

        return try {
            // Start in QUEUED state so a foreground notification exists immediately.
            val queued =
                LocalDownloadState(songId, title, artist, LocalDownloadState.State.QUEUED)
            setForeground(
                ForegroundInfo(
                    DownloadNotificationManager.songNotificationId(songId),
                    DownloadNotificationManager.buildProgressNotification(applicationContext, queued),
                ),
            )

            downloader.download(songId, title, artist) { state ->
                updateNotifications(downloader, state)
            }

            DownloadNotificationManager.cancelSong(applicationContext, songId)
            DownloadNotificationManager.showCompleted(applicationContext, title)
            Result.success()
        } catch (e: CancellationException) {
            // The byte-copy loop can re-add a DOWNLOADING entry to the progress map after the
            // cancel action already removed it (one more non-suspending _progress.update before
            // cancellation propagates at the next read suspension point). Re-remove it here so
            // the Download Queue UI never shows a stuck forever-downloading row.
            downloader.cancelWork(applicationContext, songId)
            DownloadNotificationManager.cancelSong(applicationContext, songId)
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                DownloadNotificationManager.cancelSong(applicationContext, songId)
                DownloadNotificationManager.showFailed(applicationContext, title)
                Result.failure()
            }
        }
    }

    /**
     * Drives the notifications from a (already throttled by the downloader) progress
     * emission: this song's notification, and — when 2+ downloads are active — the
     * grouped summary refreshed from the shared progress map.
     */
    private suspend fun updateNotifications(
        downloader: LocalFileDownloader,
        state: LocalDownloadState,
    ) {
        when (state.state) {
            LocalDownloadState.State.QUEUED,
            LocalDownloadState.State.DOWNLOADING,
            -> {
                setForeground(
                    ForegroundInfo(
                        DownloadNotificationManager.songNotificationId(state.songId),
                        DownloadNotificationManager.buildProgressNotification(applicationContext, state),
                    ),
                )
            }

            LocalDownloadState.State.COMPLETED,
            LocalDownloadState.State.FAILED,
            -> DownloadNotificationManager.cancelSong(applicationContext, state.songId)
        }

        // When several songs download in parallel the shared progress map holds them all;
        // refresh the summary so "Downloading N songs" + overall progress stay current.
        val active =
            downloader.progress.value.values.filter {
                it.state == LocalDownloadState.State.QUEUED ||
                    it.state == LocalDownloadState.State.DOWNLOADING
            }
        if (active.size > 1) {
            DownloadNotificationManager.postGrouped(applicationContext, active)
        } else {
            DownloadNotificationManager.clearGrouped(applicationContext)
        }
    }

    companion object {
        const val KEY_SONG_ID = "song_id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        private const val MAX_RETRIES = 3
    }
}
