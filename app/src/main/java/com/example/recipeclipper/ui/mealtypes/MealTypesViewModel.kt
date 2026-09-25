package com.example.recipeclipper.ui.mealtypes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.MealPlanRepository
import com.example.recipeclipper.data.model.MealType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [renaming] and [deleting] are the meal type whose rename field or delete confirmation is
 * open. [creating] is the "+ New meal type" field, expanded inline as on Lists.
 */
data class MealTypesUiState(
    val types: List<MealType> = emptyList(),
    val creating: Boolean = false,
    val newName: String = "",
    val renaming: MealType? = null,
    val renameText: String = "",
    val deleting: MealType? = null
)

/**
 * The Week menu's "Meal types" screen (#49): add, rename and reorder any, delete the user's
 * own. The types are collected here, not through `stateIn`, because moving one reads the
 * current order.
 */
@HiltViewModel
class MealTypesViewModel @Inject constructor(
    private val repository: MealPlanRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MealTypesUiState())
    val uiState: StateFlow<MealTypesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeMealTypes().collect { types -> _uiState.update { it.copy(types = types) } }
        }
    }

    fun onStartCreating() = _uiState.update { it.copy(creating = true) }

    fun onNewNameChange(value: String) = _uiState.update { it.copy(newName = value) }

    fun onCancelCreating() = _uiState.update { it.copy(creating = false, newName = "") }

    fun onCreate() {
        val name = _uiState.value.newName.trim()
        if (name.isEmpty()) return
        _uiState.update { it.copy(creating = false, newName = "") }
        viewModelScope.launch { repository.addMealType(name) }
    }

    fun onRenameStart(type: MealType) = _uiState.update { it.copy(renaming = type, renameText = type.name) }

    fun onRenameTextChange(value: String) = _uiState.update { it.copy(renameText = value) }

    fun onRenameConfirm() {
        val type = _uiState.value.renaming ?: return
        val name = _uiState.value.renameText.trim()
        if (name.isEmpty()) return
        _uiState.update { it.copy(renaming = null, renameText = "") }
        viewModelScope.launch { repository.renameMealType(type.id, name) }
    }

    fun onRenameDismissed() = _uiState.update { it.copy(renaming = null, renameText = "") }

    fun onMoveUp(type: MealType) = move(type, -1)

    fun onMoveDown(type: MealType) = move(type, +1)

    private fun move(type: MealType, by: Int) {
        val order = _uiState.value.types.toMutableList()
        val from = order.indexOfFirst { it.id == type.id }
        val to = from + by
        if (from < 0 || to !in order.indices) return
        order.add(to, order.removeAt(from))
        // Shown at once; the database's echo agrees.
        _uiState.update { it.copy(types = order) }
        viewModelScope.launch { repository.reorderMealTypes(order.map { it.id }) }
    }

    /** Only the user's own types offer Delete; a seeded one is ignored here too. */
    fun onDeleteStart(type: MealType) {
        if (type.isBuiltIn) return
        _uiState.update { it.copy(deleting = type) }
    }

    fun onDeleteConfirm() {
        val type = _uiState.value.deleting ?: return
        _uiState.update { it.copy(deleting = null) }
        viewModelScope.launch { repository.deleteMealType(type.id) }
    }

    fun onDeleteDismissed() = _uiState.update { it.copy(deleting = null) }
}
