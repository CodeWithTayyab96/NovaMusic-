/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.novamusic.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.novamusic.app.di.LocalFileDownloaderEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * Receives the Cancel / Cancel All actions from the download progress notifications
 * and cancels the corresponding WorkManager work (which in turn removes the in-memory
 * progress entry and rolls back the pending MediaStore file).
 */
class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_CANCEL_ALL -> {
                val downloader =
                    runCatching {
                        EntryPointAccessors
                            .fromApplication(appContext, LocalFileDownloaderEntryPoint::class.java)
                            .localFileDownloader()
                    }.getOrNull()
                val songIds = downloader?.progress?.value?.keys?.toList().orEmpty()
                songIds.forEach { songId ->
                    downloader?.cancelWork(appContext, songId)
                    DownloadNotificationManager.cancelPaused(appContext, songId)
                    DownloadNotificationManager.cancelSong(appContext, songId)
                }
                if (songIds.isEmpty()) {
                    // No in-memory progress (process restarted): the paused/queued
                    // notifications may still be up; cancel them via WorkManager.
                    WorkManager.getInstance(appContext).cancelAllWorkByTag(LocalFileDownloadWorker.WORK_TAG)
                }
            }

            else -> {
                intent.getStringExtra(EXTRA_SONG_ID)?.let { songId ->
                    val downloader =
                        runCatching {
                            EntryPointAccessors
                                .fromApplication(appContext, LocalFileDownloaderEntryPoint::class.java)
                                .localFileDownloader()
                        }.getOrNull()
                    downloader?.cancelWork(appContext, songId)
                        ?: WorkManager.getInstance(appContext).cancelUniqueWork(LocalFileDownloader.uniqueWorkName(songId))
                    // Also dismiss paused/queued notifications: a paused (non-running)
                    // worker never executes its CancellationException handler, so the
                    // notification would otherwise stay up with a dead Cancel button.
                    DownloadNotificationManager.cancelPaused(appContext, songId)
                    DownloadNotificationManager.cancelSong(appContext, songId)
                }
            }
        }
        DownloadNotificationManager.clearGrouped(appContext)
    }

    companion object {
        const val ACTION_CANCEL = "com.novamusic.app.action.CANCEL_LOCAL_DOWNLOAD"
        const val ACTION_CANCEL_ALL = "com.novamusic.app.action.CANCEL_ALL_LOCAL_DOWNLOADS"
        const val EXTRA_SONG_ID = "song_id"
    }
}
