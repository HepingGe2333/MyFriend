package com.myfriend.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.data.repo.ChatRepository
import com.myfriend.app.data.repo.SearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: SearchResults = SearchResults(),
    val searching: Boolean = false
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {
    private val query = MutableStateFlow("")

    val uiState: StateFlow<SearchUiState> = query
        .debounce { if (it.isBlank()) 0L else 250L }
        .mapLatest { text ->
            if (text.isBlank()) {
                SearchUiState(query = text, results = SearchResults(), searching = false)
            } else {
                val results = runCatching { chatRepository.search(text) }.getOrElse { SearchResults() }
                SearchUiState(query = text, results = results, searching = false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun updateQuery(value: String) {
        query.value = value
    }
}
