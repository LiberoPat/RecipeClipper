package com.example.recipeclipper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.model.RecipeSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * [loaded] is false until the database has answered once, so the screen doesn't flash its
 * "nothing here yet" hint at someone who has plenty of history.
 */
data class HomeUiState(
    val loaded: Boolean = false,
    val urlInput: String = "",
    val urlError: Boolean = false,
    val continueCooking: RecipeSummary? = null,
    val recent: List<RecipeSummary> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(repository: RecipeRepository) : ViewModel() {

    private val input = MutableStateFlow("")
    private val inputError = MutableStateFlow(false)

    /**
     * The most recent recipe is the "continue cooking" card; the five before it are "recent".
     *
     * There is deliberately no "Saved" section. Home used to carry one, fed by a
     * `observeRecentlySaved` query, but it showed the same recipes as "Recently viewed" —
     * saving something usually means you just opened it — and Lists already answers "what
     * have I kept?" properly. The duplication was visible on screen and also crashed the
     * LazyColumn, since one recipe rendered in two sections under one key.
     */
    val uiState: StateFlow<HomeUiState> = combine(
        input,
        inputError,
        repository.observeRecent(RECENT_COUNT + 1)
    ) { url, error, recent ->
        HomeUiState(
            loaded = true,
            urlInput = url,
            urlError = error,
            continueCooking = recent.firstOrNull(),
            recent = recent.drop(1)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onUrlChange(value: String) {
        input.value = value
        inputError.value = false
    }

    /** The link to open, or null (and an error shown) if what was typed isn't a link. */
    fun onGo(): String? {
        val url = UrlInput.normalize(input.value)
        inputError.update { url == null }
        if (url != null) input.value = ""
        return url
    }

    private companion object {
        const val RECENT_COUNT = 5
    }
}
