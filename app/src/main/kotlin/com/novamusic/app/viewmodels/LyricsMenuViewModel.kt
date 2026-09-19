/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.novamusic.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novamusic.app.db.MusicDatabase
import com.novamusic.app.db.entities.LyricsEntity
import com.novamusic.app.lyrics.LyricsHelper
import com.novamusic.app.lyrics.LyricsResult
import com.novamusic.app.models.MediaMetadata
import com.novamusic.app.translation.TranslateLyricsUseCase
import com.novamusic.app.utils.NetworkConnectivityObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
 
import javax.inject.Inject

@HiltViewModel
class LyricsMenuViewModel
@Inject
constructor(
    private val lyricsHelper: LyricsHelper,
    val database: MusicDatabase,
    private val networkConnectivity: NetworkConnectivityObserver,
    private val translateLyricsUseCase: TranslateLyricsUseCase,
) : ViewModel() {
    private var job: Job? = null
    val results = MutableStateFlow(emptyList<LyricsResult>())
    val isLoading = MutableStateFlow(false)

    private val _isNetworkAvailable = MutableStateFlow(false)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    init {
        viewModelScope.launch {
            networkConnectivity.networkStatus.collect { isConnected ->
                _isNetworkAvailable.value = isConnected
            }
        }
        
        // Set initial state using synchronous check
        _isNetworkAvailable.value = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true // Assume connected as fallback
        }
    }

    fun search(
        mediaId: String,
        title: String,
        artist: String,
        duration: Int,
    ) {
        isLoading.value = true
        results.value = emptyList()
        job?.cancel()
        job =
            viewModelScope.launch(Dispatchers.IO) {
                lyricsHelper.getAllLyrics(mediaId, title, artist, null, duration) { result ->
                    results.update {
                        it + result
                    }
                }
                isLoading.value = false
            }
    }

    fun cancelSearch() {
        job?.cancel()
        job = null
    }

    fun refetchLyrics(
        mediaMetadata: MediaMetadata,
        lyricsEntity: LyricsEntity?,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lyrics = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    lyricsEntity?.let(::delete)
                    upsert(LyricsEntity(mediaMetadata.id, lyrics))
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Replaces the ORIGINAL lyrics — used by the manual edit dialog and when the user picks
     * a different lyrics search result.
     *
     * Distinct from [updateLyrics] on purpose: that one stores a translation and must never
     * touch the original, whereas this one IS the original changing. Any stored translation
     * is cleared, because it was derived from the previous original and would no longer
     * correspond to the new one.
     */
    fun updateOriginalLyrics(
        mediaMetadata: MediaMetadata,
        lyrics: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            database.query {
                upsert(LyricsEntity(mediaMetadata.id, lyrics))
            }
        }
    }

    /**
     * Stores a translation of the song's ORIGINAL lyrics.
     *
     * Never writes [LyricsEntity.lyrics], so the original survives and remains the source
     * for every later translation and for "original" display mode. Callers must pass text
     * translated FROM THE ORIGINAL (never from a previous translation) plus the language
     * that text is actually in.
     *
     * The lyrics screen observes database.lyrics(id) through PlayerConnection.currentLyrics,
     * so this write refreshes the UI with no manual refresh.
     */
    fun updateLyrics(
        mediaMetadata: MediaMetadata,
        translatedLyrics: String,
        translationLanguage: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            database.query {
                updateLyricsTranslation(
                    id = mediaMetadata.id,
                    translatedLyrics = translatedLyrics,
                    translationLanguage = translationLanguage,
                )
            }
        }
    }

    /**
     * Translates plain lyric lines through the provider stack.
     *
     * [lines] carry no timestamps — the caller strips them and reattaches them by index from
     * the original, so the model never produces timing information. Reuse of a stored
     * translation, provider selection, validation and persistence all happen behind the use
     * case; the caller only sees the resulting lines or a structured [TranslationError].
     */
    suspend fun translateLyrics(
        songId: String,
        lines: List<String>,
        targetLanguage: String,
    ): Result<List<String>> =
        translateLyricsUseCase(
            songId = songId,
            lines = lines,
            targetLanguage = targetLanguage,
        ).map { outcome -> outcome.lines }

}
