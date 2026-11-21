package com.example.recipeclipper.ui.article

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.model.ArticleRecipe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One of the article's recipes (#435): its place on the page, name, counts, and its id once saved. */
data class ArticleChoiceRow(
    val index: Int,
    val name: String,
    val ingredients: Int,
    val steps: Int,
    val savedId: Long? = null
)

/**
 * The chooser over a page's recipes (#435): the page's [headline] and [image] above one row per
 * recipe. [pageUrl] is the page's link, which the editor finds them by. [loading] until read;
 * empty [rows] after that mean the page no longer holds any.
 */
data class ArticleChoiceUiState(
    val loading: Boolean = true,
    val pageUrl: String = "",
    val headline: String = "",
    val image: String? = null,
    val rows: List<ArticleChoiceRow> = emptyList()
)

/**
 * Several recipes written inside one article (#435): the cook picks one to check in the editor.
 * Nothing is saved here. Each time the screen shows again ([onShown], back from the editor or a
 * saved recipe) it asks which are saved by now, so a saved one says so and opens as itself.
 * iOS: `ArticleChoiceViewModel`.
 */
@HiltViewModel
class ArticleChoiceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RecipeRepository
) : ViewModel() {

    private val url: String = savedStateHandle.get<String>(URL_ARG).orEmpty()

    private val _uiState = MutableStateFlow(ArticleChoiceUiState(pageUrl = url))
    val uiState: StateFlow<ArticleChoiceUiState> = _uiState.asStateFlow()

    private var recipes: List<ArticleRecipe> = emptyList()

    init {
        viewModelScope.launch {
            recipes = repository.articleRecipes(url)
            val first = recipes.firstOrNull()
            val saved = repository.savedIds(recipes.map { it.sourceUrl })
            _uiState.update {
                it.copy(
                    loading = false,
                    headline = first?.headline.orEmpty(),
                    image = first?.image,
                    rows = rows(saved)
                )
            }
        }
    }

    /** The screen is showing again: which recipes are saved by now. */
    fun onShown() {
        if (_uiState.value.loading || recipes.isEmpty()) return
        viewModelScope.launch {
            val saved = repository.savedIds(recipes.map { it.sourceUrl })
            _uiState.update { it.copy(rows = rows(saved)) }
        }
    }

    private fun rows(saved: Map<String, Long>) = recipes.mapIndexed { i, recipe ->
        ArticleChoiceRow(i, recipe.name, recipe.ingredients.size, recipe.steps.size, saved[recipe.sourceUrl])
    }

    companion object {
        const val URL_ARG = "url"
    }
}
