/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.novamusic.app.playback

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.novamusic.app.R
import com.novamusic.app.di.LocalFileDownloaderEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber

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

        // Clear any stale paused notification that may have been left behind by
        // a previous worker run that was interrupted (retry backoff, constraint loss).
        DownloadNotificationManager.cancelPaused(applicationContext, songId)
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

            DownloadNotificationManager.cancelPaused(applicationContext, songId)
            DownloadNotificationManager.cancelSong(applicationContext, songId)
            DownloadNotificationManager.showCompleted(applicationContext, title)
            Result.success()
        } catch (e: CancellationException) {
            // Use NonCancellable so cleanup runs even though the coroutine is
            // being cancelled (the thread is not cancelled, only the coroutine).
            withContext(NonCancellable + Dispatchers.IO) {
                val isUserCancel = runCatching {
                    WorkManager.getInstance(applicationContext)
                        .getWorkInfoById(id)
                        .get()  // blocking, not suspending
                        ?.state == WorkInfo.State.CANCELLED
                }.getOrElse { false }

                if (isUserCancel) {
                    // Genuine user cancel (Cancel button, deleteLocalFile, or
                    // REPLACE re-enqueue). Clean up permanently.
                    Timber.w("$TAG: User cancelled download $songId")
                    downloader.cancelWork(applicationContext, songId)
                    DownloadNotificationManager.cancelPaused(applicationContext, songId)
                    DownloadNotificationManager.cancelSong(applicationContext, songId)
                } else {
                    // System-initiated stop: constraint no longer met (network
                    // lost), process teardown, or WorkManager re-enqueue (REPLACE).
                    // Do NOT call cancelWork — let WorkManager re-queue and
                    // re-run the worker when constraints are satisfied again.
                    // Show a paused notification so the download doesn't silently
                    // vanish from the user's view.
                    Timber.w("$TAG: Download $songId interrupted by system stop — pausing until constraints re-met")
                    val stopCount = downloader.markPaused(songId, title, artist, e.message)
                    if (stopCount > MAX_SYSTEM_STOPS) {
                        // Flaky/unresolvable network: after several pause/resume
                        // cycles, give up with a clear failure instead of letting
                        // the download cycle "downloading → paused" forever.
                        val reason = applicationContext.getString(R.string.download_couldnt_resume)
                        Timber.w("$TAG: Download $songId exceeded $MAX_SYSTEM_STOPS system stops — failing permanently")
                        // Cancel the work permanently so it never re-runs, then
                        // write FAILED to the in-memory progress so the queue row
                        // is still visible with an error message.
                        WorkManager.getInstance(applicationContext).cancelUniqueWork(
                            LocalFileDownloader.uniqueWorkName(songId),
                        )
                        downloader.markFailed(songId, title, artist, reason)
                        DownloadNotificationManager.cancelPaused(applicationContext, songId)
                        DownloadNotificationManager.cancelSong(applicationContext, songId)
                        DownloadNotificationManager.showFailed(applicationContext, title, reason)
                    } else {
                        DownloadNotificationManager.postPaused(
                            applicationContext, songId, title, artist,
                            applicationContext.getString(R.string.download_waiting_for_network),
                        )
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $songId (attempt ${runAttemptCount + 1}/$MAX_RETRIES): ${e.message}", e)
            if (runAttemptCount < MAX_RETRIES) {
                // Transition notification to "paused — will retry" instead of
                // vanishing completely during the backoff interval.
                downloader.markPaused(songId, title, artist, e.message)
                DownloadNotificationManager.postPaused(
                    applicationContext, songId, title, artist,
                    applicationContext.getString(R.string.download_waiting_for_network),
                )
                Result.retry()
            } else {
                DownloadNotificationManager.cancelPaused(applicationContext, songId)
                DownloadNotificationManager.cancelSong(applicationContext, songId)
                val reason = e.cause?.message?.take(120) ?: e.message?.take(120)
                DownloadNotificationManager.showFailed(applicationContext, title, reason)
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
        if (state.isPaused) {
            DownloadNotificationManager.postPaused(
                applicationContext, state.songId, state.title, state.artist,
                state.error?.take(80),
            )
            return
        }
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
            -> {
                DownloadNotificationManager.cancelPaused(applicationContext, state.songId)
                DownloadNotificationManager.cancelSong(applicationContext, state.songId)
            }
        }

        // When several songs download in parallel the shared progress map holds them all;
        // refresh the summary so "Downloading N songs" + overall progress stay current.
        val active =
            downloader.progress.value.values.filter { state ->
                state.isActive || state.isPaused
            }
        if (active.size > 1) {
            DownloadNotificationManager.postGrouped(applicationContext, active)
        } else {
            DownloadNotificationManager.clearGrouped(applicationContext)
        }
    }

    companion object {
        private const val TAG = "LocalFileDownloadWorker"
        const val WORK_TAG = "local_download"
        const val KEY_SONG_ID = "song_id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        private const val MAX_RETRIES = 3

        /** Ceiling on pause/resume cycles caused by system stops (network loss). */
        private const val MAX_SYSTEM_STOPS = 5
    }
}
