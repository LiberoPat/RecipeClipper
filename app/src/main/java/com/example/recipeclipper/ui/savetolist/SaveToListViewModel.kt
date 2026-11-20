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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [creatingList] is the "+ New list" field being expanded inline — a text field in the sheet,
 * never a dialog stacked on top of it. [recipeTitle] names the recipe under that field, as the
 * one that goes into the new list (#286).
 *
 * For several recipes (#377, [recipeCount] above one) a list's `containsRecipe` means it holds all of
 * them, [partialListIds] are the lists holding some but not all (shown indeterminate), and the
 * note under the new-list field counts them instead of naming one.
 */
data class SaveToListUiState(
    val lists: List<RecipeList> = emptyList(),
    val creatingList: Boolean = false,
    val newListName: String = "",
    val recipeTitle: String = "",
    val recipeCount: Int = 1,
    val partialListIds: Set<Long> = emptySet()
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

    // One id for a recipe's own sheet, several for a selection (#377), none until set.
    private val recipeIds = MutableStateFlow<List<Long>>(emptyList())
    private val recipeTitle = MutableStateFlow("")
    private val creatingList = MutableStateFlow(false)
    private val newListName = MutableStateFlow("")

    /** The lists, and which of them hold only some of the recipes (none, for one recipe). */
    private val listsFor = recipeIds.flatMapLatest { ids ->
        when (ids.size) {
            0 -> flowOf(emptyList<RecipeList>() to emptySet<Long>())
            1 -> repository.observeListsFor(ids.single()).map { it to emptySet<Long>() }
            else -> combine(repository.observeLists(), repository.observeMemberCounts(ids)) { lists, counts ->
                lists.map { it.copy(containsRecipe = counts[it.id] == ids.size) } to
                    lists.filter { (counts[it.id] ?: 0) in 1 until ids.size }.map { it.id }.toSet()
            }
        }
    }

    val uiState: StateFlow<SaveToListUiState> = combine(
        listsFor,
        creatingList,
        newListName,
        recipeTitle,
        recipeIds
    ) { (lists, partial), creating, name, title, ids ->
        SaveToListUiState(
            lists = lists, creatingList = creating, newListName = name, recipeTitle = title,
            recipeCount = maxOf(ids.size, 1), partialListIds = partial
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SaveToListUiState())

    /**
     * Called once the recipe screen knows which recipe it is showing, and again when its title
     * changes (an edit); the same id doesn't re-read the lists.
     */
    fun setRecipe(id: Long, title: String) {
        recipeIds.value = listOf(id)
        recipeTitle.value = title
    }

    /**
     * The sheet for several recipes at once (#377): a tick adds all of them to the list, an untick
     * takes all of them off, and a list holding only some shows indeterminate.
     */
    fun setRecipes(ids: List<Long>) {
        recipeIds.value = ids
        recipeTitle.value = ""
    }

    /**
     * Ticking or unticking a list. Writes straight through: the sheet is dismissed, not
     * submitted, so there is no Save button and no later moment at which to persist.
     */
    fun onListToggled(listId: Long, inList: Boolean) {
        val ids = recipeIds.value
        when {
            ids.isEmpty() -> return
            ids.size == 1 -> viewModelScope.launch { repository.setMembership(ids.single(), listId, inList) }
            else -> viewModelScope.launch { repository.setMembershipAll(ids, listId, inList) }
        }
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
        val ids = recipeIds.value
        viewModelScope.launch {
            if (ids.size > 1) repository.createListWith(name, ids) else repository.createList(name, ids.singleOrNull())
            creatingList.value = false
            newListName.value = ""
        }
    }
}
