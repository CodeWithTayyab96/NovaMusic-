/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.novamusic.app.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novamusic.app.innertube.YouTube
import com.novamusic.app.innertube.models.SearchSuggestions
import com.novamusic.app.innertube.models.YTItem
import com.novamusic.app.innertube.models.filterExplicit
import com.novamusic.app.innertube.models.filterVideo
import com.novamusic.app.constants.HideExplicitKey
import com.novamusic.app.constants.HideVideoKey
import com.novamusic.app.db.MusicDatabase
import com.novamusic.app.db.entities.SearchHistory
import com.novamusic.app.utils.dataStore
import com.novamusic.app.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnlineSearchSuggestionViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    database: MusicDatabase,
) : ViewModel() {
    val query = MutableStateFlow("")
    private val _viewState = MutableStateFlow(SearchSuggestionViewState())
    val viewState = _viewState.asStateFlow()

    init {
        // History flow: updates immediately from DB
        viewModelScope.launch {
            query
                .flatMapLatest { query ->
                    if (query.isEmpty()) {
                        database.searchHistory().map { history ->
                            SearchSuggestionViewState(history = history)
                        }
                    } else {
                        database
                            .searchHistory(query)
                            .map { it.take(3) }
                            .map { history ->
                                SearchSuggestionViewState(history = history)
                            }
                    }
                }.collect {
                    _viewState.value = it
                }
        }

        // Suggestions flow: fetches from network independently
        viewModelScope.launch {
            query
                .flatMapLatest { query ->
                    if (query.isEmpty()) {
                        flowOf<SearchSuggestions?>(null)
                    } else {
                        flow<SearchSuggestions?> {
                            emit(null) // clear stale suggestions immediately
                            emit(YouTube.searchSuggestions(query).getOrNull())
                        }
                    }
                }.collect { result ->
                    val history = _viewState.value.history
                    _viewState.value = _viewState.value.copy(
                        suggestions = result
                            ?.queries
                            ?.filter { s -> history.none { it.query == s } }
                            .orEmpty(),
                        items = result
                            ?.recommendedItems
                            ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                            ?.filterVideo(context.dataStore.get(HideVideoKey, false))
                            .orEmpty(),
                    )
                }
        }
    }
}

data class SearchSuggestionViewState(
    val history: List<SearchHistory> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val items: List<YTItem> = emptyList(),
)