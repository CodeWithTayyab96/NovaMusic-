/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.novamusic.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novamusic.app.db.MusicDatabase
import com.novamusic.app.db.entities.Song
import com.novamusic.app.playback.LocalDownloadState
import com.novamusic.app.playback.LocalFileDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadItem(
    val songId: String,
    val song: Song?,
    val title: String,
    val artist: String,
    val state: LocalDownloadState.State,
    val progress: Float,
)

@HiltViewModel
class DownloadQueueViewModel @Inject constructor(
    private val localFileDownloader: LocalFileDownloader,
    database: MusicDatabase,
) : ViewModel() {

    val downloads = combine(
        localFileDownloader.progress,
        database.allSongs()
    ) { progressMap, allSongs ->
        val songMap = allSongs.associateBy { it.id }
        progressMap.values
            .filter {
                it.state == LocalDownloadState.State.QUEUED ||
                    it.state == LocalDownloadState.State.DOWNLOADING
            }
            .map { state ->
                DownloadItem(
                    songId = state.songId,
                    song = songMap[state.songId],
                    title = state.title,
                    artist = state.artist,
                    state = state.state,
                    progress = state.progress,
                )
            }
            .sortedBy { it.title }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeDownload(songId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            localFileDownloader.deleteLocalFile(songId)
        }
    }

    fun removeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            localFileDownloader.progress.value.keys.toList().forEach { songId ->
                localFileDownloader.deleteLocalFile(songId)
            }
        }
    }
}
