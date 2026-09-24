package com.example.recipeclipper.ui.listdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [list] is null while loading and again once the list has been deleted, so [loaded] is what
 * tells an empty list apart from one that hasn't arrived yet. [deleted] is set by the delete
 * action itself rather than inferred from a null [list], so the screen navigates back exactly
 * once and only in response to what the user did.
 */
data class ListDetailUiState(
    val loaded: Boolean = false,
    val list: RecipeList? = null,
    val recipes: List<RecipeSummary> = emptyList(),
    val renaming: Boolean = false,
    val renameValue: String = "",
    val deleted: Boolean = false
)

@HiltViewModel
class ListDetailViewModel @Inject constructor(
    private val repository: ListRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val listId: Long = checkNotNull(savedStateHandle[LIST_ID_ARG])

    private val renaming = MutableStateFlow(false)
    private val renameValue = MutableStateFlow("")
    private val deleted = MutableStateFlow(false)

    /**
     * The list itself comes from `observeLists()` rather than its own query: there are only
     * ever a handful of lists, the count comes along for free, and it is one flow to keep
     * correct instead of two.
     *
     * It is collected into its own state here, in [viewModelScope], rather than read back off
     * [uiState] when an action needs it. [uiState] is a `WhileSubscribed` flow, so its `value`
     * is the initial state whenever nothing is collecting — and [onDelete] reading a null list
     * there would silently refuse a delete that should have gone through.
     */
    private val currentList = MutableStateFlow<RecipeList?>(null)

    init {
        viewModelScope.launch {
            repository.observeLists()
                .map { lists -> lists.firstOrNull { it.id == listId } }
                .collect { currentList.value = it }
        }
    }

    val uiState: StateFlow<ListDetailUiState> = combine(
        currentList,
        repository.observeRecipesIn(listId),
        renaming,
        renameValue,
        deleted
    ) { list, recipes, isRenaming, rename, isDeleted ->
        ListDetailUiState(
            loaded = true,
            list = list,
            recipes = recipes,
            renaming = isRenaming,
            renameValue = rename,
            deleted = isDeleted
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListDetailUiState())

    /** Seeds the field with the current name, so renaming is an edit rather than a retype. */
    fun onStartRenaming() {
        renameValue.value = currentList.value?.name.orEmpty()
        renaming.value = true
    }

    fun onCancelRenaming() {
        renaming.value = false
        renameValue.value = ""
    }

    fun onRenameValueChange(value: String) {
        renameValue.value = value
    }

    /** Built-ins are renameable; only deleting them is refused. A blank name is ignored. */
    fun onRenameConfirm() {
        val name = renameValue.value.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            repository.rename(listId, name)
            renaming.value = false
            renameValue.value = ""
        }
    }

    /**
     * Deletes the list, never the recipes in it — they fall back to ordinary history.
     * Refused for Favorites only; every other list, seeded or not, can go.
     */
    fun onDelete() {
        if (currentList.value?.isFavorites != false) return
        viewModelScope.launch {
            repository.deleteList(listId)
            deleted.value = true
        }
    }

    companion object {
        const val LIST_ID_ARG = "listId"
    }
}
