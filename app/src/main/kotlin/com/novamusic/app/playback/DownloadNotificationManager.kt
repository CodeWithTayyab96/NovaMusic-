/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.novamusic.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.novamusic.app.MainActivity
import com.novamusic.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Builds and posts the notifications for [LocalFileDownloadWorker].
 *
 * - A single download shows one progress notification (song title, percent progress,
 *   a Cancel action) on a low-importance "Downloads" channel so it never beeps per song.
 * - When several downloads are active at once the per-song notifications are grouped
 *   under a single Spotify-style summary notification ("Downloading N songs") with an
 *   overall progress bar and a Cancel All action.
 * - On completion a brief "Download complete" notification is shown and auto-dismissed
 *   after a few seconds; failures show a "Download failed" notification.
 */
object DownloadNotificationManager {
    const val CHANNEL_ID = "downloads"
    const val GROUP_KEY = "local_downloads"
    const val SUMMARY_NOTIFICATION_ID = 5001
    private const val BASE_SONG_NOTIFICATION_ID = 6000
    private const val COMPLETED_NOTIFICATION_ID = 7001
    private const val FAILED_NOTIFICATION_ID = 7002
    private const val COMPLETED_VISIBLE_MS = 4000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Stable positive notification id derived from [songId]. */
    fun songNotificationId(songId: String): Int = BASE_SONG_NOTIFICATION_ID + (songId.hashCode() and 0x3FFF)

    /**
     * Creates the low-importance "Downloads" channel if it doesn't exist yet.
     * IMPORTANCE_LOW: no sound/vibration, but the progress is still visible.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.downloads_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.downloads_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Foreground notification for a single in-flight download. Call from the worker via
     * `setForeground(ForegroundInfo(...))` so WorkManager keeps the process alive.
     */
    fun buildProgressNotification(
        context: Context,
        state: LocalDownloadState,
    ): Notification {
        val indeterminate = state.totalBytes <= 0
        val progress = (state.progress * 100).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.downloading)
            .setContentTitle(state.title)
            .setContentText(state.artist.ifBlank { context.getString(R.string.downloading) })
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .setGroup(GROUP_KEY)
            .setContentIntent(downloadQueueIntent(context))
            .addAction(
                R.drawable.close,
                context.getString(R.string.action_cancel),
                cancelIntent(context, state.songId),
            )
            .build()
    }

    /**
     * Spotify-style summary shown while 2+ downloads are active: "Downloading N songs"
     * with an overall progress bar and a Cancel All action.
     */
    fun buildSummaryNotification(
        context: Context,
        active: List<LocalDownloadState>,
    ): Notification {
        val overall =
            if (active.isEmpty()) {
                0f
            } else {
                active.map { it.progress }.average().toFloat()
            }
        val progress = (overall * 100).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.downloading)
            .setContentTitle(
                context.resources.getQuantityString(
                    R.plurals.downloading_n_songs,
                    active.size,
                    active.size,
                ),
            )
            .setContentText(context.getString(R.string.downloading))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(downloadQueueIntent(context))
            .addAction(
                R.drawable.close,
                context.getString(R.string.action_cancel_all),
                cancelAllIntent(context),
            )
            .build()
    }

    /**
     * Posts (or refreshes) the grouped summary when 2+ downloads are active. The per-song
     * progress notifications are the workers' foreground notifications (same [GROUP_KEY]);
     * Android collapses them under this summary. With a single active download the summary
     * is cancelled and the one foreground notification stands alone.
     */
    fun postGrouped(
        context: Context,
        active: List<LocalDownloadState>,
    ) {
        if (active.size < 2) {
            clearGrouped(context)
            return
        }
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(SUMMARY_NOTIFICATION_ID, buildSummaryNotification(context, active))
        }
    }

    /** Dismisses the summary and any grouped child notifications. */
    fun clearGrouped(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        runCatching { manager.cancel(SUMMARY_NOTIFICATION_ID) }
    }

    /** Dismisses the per-song notification for [songId]. */
    fun cancelSong(context: Context, songId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(songNotificationId(songId)) }
    }

    /** Shows "Download complete" briefly, then auto-dismisses it. */
    fun showCompleted(context: Context, title: String) {
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.download)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.download_complete))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setContentIntent(downloadQueueIntent(context))
                .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(COMPLETED_NOTIFICATION_ID, notification)
        }
        scope.launch {
            delay(COMPLETED_VISIBLE_MS)
            runCatching { NotificationManagerCompat.from(context).cancel(COMPLETED_NOTIFICATION_ID) }
        }
    }

    /** Shows "Download failed" for a terminal failure. */
    fun showFailed(context: Context, title: String) {
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.error)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.download_failed))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setContentIntent(downloadQueueIntent(context))
                .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(FAILED_NOTIFICATION_ID, notification)
        }
    }

    private fun downloadQueueIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_DOWNLOAD_QUEUE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelIntent(context: Context, songId: String): PendingIntent {
        val receiverIntent =
            Intent(context, DownloadCancelReceiver::class.java)
                .setAction(DownloadCancelReceiver.ACTION_CANCEL)
                .putExtra(DownloadCancelReceiver.EXTRA_SONG_ID, songId)
        return PendingIntent.getBroadcast(
            context,
            songNotificationId(songId),
            receiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelAllIntent(context: Context): PendingIntent {
        val receiverIntent =
            Intent(context, DownloadCancelReceiver::class.java)
                .setAction(DownloadCancelReceiver.ACTION_CANCEL_ALL)
        return PendingIntent.getBroadcast(
            context,
            SUMMARY_NOTIFICATION_ID,
            receiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
