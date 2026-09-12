/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.novamusic.app.ui.utils

import androidx.media3.exoplayer.offline.Download
import com.novamusic.app.db.entities.Song
import com.novamusic.app.playback.LocalDownloadState

/**
 * Single source of truth for the download state shown in the UI.
 *
 * The state is derived exclusively from the **active** download system:
 *  - "downloaded" comes from [Song.song] -> `isDownloadedByApp()` (a real file in
 *    `Music/NovaMusic/` with `isLocal = true` and a valid `localPath`);
 *  - "in progress" comes from [LocalFileDownloader.progress][com.novamusic.app.playback.LocalFileDownloader.progress]
 *    (WorkManager-backed `LocalDownloadState`).
 *
 * The legacy Media3 `DownloadManager` is never enqueued and must not be consulted.
 * The returned value reuses Media3's `Download.STATE_*` integer constants purely as a
 * UI vocabulary so existing composables keep working — no Media3 `Download` object is
 * involved.
 */
fun downloadStateFor(
    songs: List<Song>,
    localStates: Map<String, LocalDownloadState>,
): Int {
    if (songs.isEmpty()) return Download.STATE_STOPPED

    val allCompleted = songs.all { it.song.isDownloadedByApp() }
    if (allCompleted) return Download.STATE_COMPLETED

    val allInProgress = songs.all { song ->
        song.song.isDownloadedByApp() ||
            localStates[song.id]?.state == LocalDownloadState.State.QUEUED ||
            localStates[song.id]?.state == LocalDownloadState.State.DOWNLOADING
    }
    if (allInProgress) return Download.STATE_DOWNLOADING

    return Download.STATE_STOPPED
}
