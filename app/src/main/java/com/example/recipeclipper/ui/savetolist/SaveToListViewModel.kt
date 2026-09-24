package com.example.recipeclipper.ui.savetolist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.model.RecipeList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [creatingList] is the "+ New list" field being expanded inline — a text field in the sheet,
 * never a dialog stacked on top of it.
 */
data class SaveToListUiState(
    val lists: List<RecipeList> = emptyList(),
    val creatingList: Boolean = false,
    val newListName: String = ""
) {
    /** What the bookmark icon renders from: filled once the recipe is in at least one list. */
    val isSaved: Boolean get() = lists.any { it.containsRecipe }
}

/**
 * Backs both the save-to-list sheet and the recipe screen's bookmark icon, which is why the
 * recipe screen holds one of these whether or not the sheet is open: the icon has to know
 * whether the recipe is in any list before anyone taps anything.
 *
 * The recipe id arrives through [setRecipe] rather than `SavedStateHandle`, because the sheet
 * is not a navigation destination — and because on the import route there is no id until the
 * parse finishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SaveToListViewModel @Inject constructor(
    private val repository: ListRepository
) : ViewModel() {

    private val recipeId = MutableStateFlow<Long?>(null)
    private val creatingList = MutableStateFlow(false)
    private val newListName = MutableStateFlow("")

    val uiState: StateFlow<SaveToListUiState> = combine(
        recipeId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repository.observeListsFor(id)
        },
        creatingList,
        newListName
    ) { lists, creating, name ->
        SaveToListUiState(lists = lists, creatingList = creating, newListName = name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SaveToListUiState())

    /** Called once the recipe screen knows which recipe it is showing. */
    fun setRecipe(id: Long) {
        recipeId.value = id
    }

    /**
     * Ticking or unticking a list. Writes straight through: the sheet is dismissed, not
     * submitted, so there is no Save button and no later moment at which to persist.
     */
    fun onListToggled(listId: Long, inList: Boolean) {
        val recipe = recipeId.value ?: return
        viewModelScope.launch { repository.setMembership(recipe, listId, inList) }
    }

    fun onStartCreating() {
        creatingList.value = true
    }

    fun onCancelCreating() {
        creatingList.value = false
        newListName.value = ""
    }

    fun onNewListNameChange(value: String) {
        newListName.value = value
    }

    /** Creating a list from the sheet puts the current recipe in it; a blank name is ignored. */
    fun onCreateList() {
        val name = newListName.value.trim()
        if (name.isEmpty()) return
        val recipe = recipeId.value
        viewModelScope.launch {
            repository.createList(name, recipe)
            creatingList.value = false
            newListName.value = ""
        }
    }
}
