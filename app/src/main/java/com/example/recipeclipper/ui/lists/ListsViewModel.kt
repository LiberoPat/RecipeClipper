package com.example.recipeclipper.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.model.RecipeList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [loaded] is false until the database has answered once, matching `HomeUiState`: the four
 * built-in lists always exist, so a momentary empty list would be a lie rather than a state.
 */
data class ListsUiState(
    val loaded: Boolean = false,
    val lists: List<RecipeList> = emptyList(),
    val creatingList: Boolean = false,
    val newListName: String = ""
)

/**
 * The Lists screen: every list with its count, and creating a new one. Renaming and deleting
 * live on the list's own screen rather than here, so there is one place a list is managed
 * from — the same reason the recipe screen owns deleting a recipe.
 */
@HiltViewModel
class ListsViewModel @Inject constructor(
    private val repository: ListRepository
) : ViewModel() {

    private val creatingList = MutableStateFlow(false)
    private val newListName = MutableStateFlow("")

    val uiState: StateFlow<ListsUiState> = combine(
        repository.observeLists(),
        creatingList,
        newListName
    ) { lists, creating, name ->
        ListsUiState(loaded = true, lists = lists, creatingList = creating, newListName = name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsUiState())

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

    /** No recipe in hand here, so the new list starts empty. A blank name is ignored. */
    fun onCreateList() {
        val name = newListName.value.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            repository.createList(name, addRecipeId = null)
            creatingList.value = false
            newListName.value = ""
        }
    }
}
