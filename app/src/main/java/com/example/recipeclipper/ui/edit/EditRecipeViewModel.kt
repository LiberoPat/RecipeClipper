package com.example.recipeclipper.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.model.RecipeDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [isNew] is "New recipe" from Home; otherwise the recipe [draft] was loaded from.
 * [showInvalid] is set by a Save that failed validation, so the rule is only pointed out
 * once the user has tried. [savedId] is set once the save lands, for the screen to open it.
 */
data class EditRecipeUiState(
    val isNew: Boolean,
    val loading: Boolean = !isNew,
    val draft: RecipeDraft = RecipeDraft(),
    val showInvalid: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    /** The recipe to edit is gone (deleted elsewhere). */
    val missing: Boolean = false,
    val savedId: Long? = null
)

/**
 * Edits a recipe's content, or types a new one in (#29). Opened with a recipe id from the
 * recipe screen's overflow menu, or with none from Home's "New recipe".
 */
@HiltViewModel
class EditRecipeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RecipeRepository
) : ViewModel() {

    private val recipeId: Long? = savedStateHandle.get<Long>(RECIPE_ID_ARG)?.takeIf { it > 0 }

    private val _uiState = MutableStateFlow(EditRecipeUiState(isNew = recipeId == null))
    val uiState: StateFlow<EditRecipeUiState> = _uiState.asStateFlow()

    init {
        if (recipeId != null) {
            viewModelScope.launch {
                val recipe = repository.open(recipeId)
                _uiState.update {
                    if (recipe == null) it.copy(loading = false, missing = true)
                    else it.copy(loading = false, draft = RecipeDraft.of(recipe))
                }
            }
        }
    }

    fun onDraftChange(draft: RecipeDraft) =
        _uiState.update { it.copy(draft = draft, saveFailed = false) }

    fun onSave() {
        val state = _uiState.value
        if (state.loading || state.saving || state.missing) return
        if (!state.draft.isValid) {
            _uiState.update { it.copy(showInvalid = true) }
            return
        }
        _uiState.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val saved = if (recipeId == null) {
                repository.addManual(state.draft)
            } else {
                repository.saveEdit(recipeId, state.draft)
            }
            _uiState.update {
                if (saved == null) it.copy(saving = false, saveFailed = true)
                else it.copy(saving = false, savedId = saved.id)
            }
        }
    }

    companion object {
        const val RECIPE_ID_ARG = "recipeId"
    }
}
