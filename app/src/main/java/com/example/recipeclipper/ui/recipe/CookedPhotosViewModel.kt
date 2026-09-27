package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.CookedPhotoRepository
import com.example.recipeclipper.data.model.CookedPhoto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Your cooks" (#116) under a recipe: [photos] newest cook first; [open] the one shown full
 * screen, with [noteDraft] its note as typed; [deleted] the one just deleted, until its Undo
 * snackbar is settled. [addFailed] is set when a picked picture couldn't be stored, until shown.
 */
data class CookedPhotosUiState(
    val photos: List<CookedPhoto> = emptyList(),
    val open: CookedPhoto? = null,
    val noteDraft: String = "",
    val adding: Boolean = false,
    val deleted: CookedPhoto? = null,
    val addFailed: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CookedPhotosViewModel @Inject constructor(
    private val repository: CookedPhotoRepository
) : ViewModel() {

    private val recipeId = MutableStateFlow<Long?>(null)
    private val local = MutableStateFlow(CookedPhotosUiState())
    private var noteSave: Job? = null

    private val photos = recipeId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observe(id) }

    val uiState: StateFlow<CookedPhotosUiState> = combine(local, photos) { state, list ->
        // The open photo follows the database (a new date), but its note stays as typed.
        state.copy(photos = list, open = state.open?.let { open -> list.firstOrNull { it.id == open.id } ?: open })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CookedPhotosUiState())

    /** The recipe on screen; its id is only known once the import route has saved it. */
    fun setRecipe(id: Long) {
        recipeId.value = id
    }

    /** Pictures from the Photo Picker or the camera: each becomes an entry, cooked today. */
    fun onAdd(sources: List<String>) {
        val id = recipeId.value ?: return
        if (sources.isEmpty()) return
        local.update { it.copy(adding = true) }
        viewModelScope.launch {
            val added = repository.add(id, sources)
            // The first new one opens, so its note and date are right there to fill in.
            local.update {
                it.copy(adding = false, addFailed = added.size < sources.size)
                    .let { s -> added.firstOrNull()?.let { photo -> s.copy(open = photo, noteDraft = "") } ?: s }
            }
        }
    }

    fun onAddFailedShown() = local.update { it.copy(addFailed = false) }

    fun onOpen(photo: CookedPhoto) {
        saveNote()
        local.update { it.copy(open = photo, noteDraft = photo.note.orEmpty()) }
    }

    fun onClose() {
        saveNote()
        local.update { it.copy(open = null, noteDraft = "") }
    }

    /** The note is written once typing pauses, or when the photo closes. */
    fun onNoteChange(text: String) {
        local.update { it.copy(noteDraft = text.take(CookedPhoto.MAX_NOTE)) }
        noteSave?.cancel()
        noteSave = viewModelScope.launch {
            delay(NOTE_DELAY_MILLIS)
            saveNote()
        }
    }

    fun onDayChange(day: Long) {
        val open = uiState.value.open ?: local.value.open ?: return
        viewModelScope.launch { repository.edit(open.id, day, local.value.noteDraft) }
    }

    /** Deletes the open photo at once; [onUndoDelete] brings it back until [onDeleteSettled]. */
    fun onDelete() {
        noteSave?.cancel()
        val open = local.value.open ?: return
        local.update { it.copy(open = null, noteDraft = "") }
        viewModelScope.launch {
            val removed = repository.delete(open.id) ?: return@launch
            // A still-pending earlier delete stands now.
            local.value.deleted?.let { repository.forget(listOf(it)) }
            local.update { it.copy(deleted = removed) }
        }
    }

    fun onUndoDelete() {
        val removed = local.value.deleted ?: return
        local.update { it.copy(deleted = null) }
        viewModelScope.launch { repository.restore(removed) }
    }

    fun onDeleteSettled() {
        val removed = local.value.deleted ?: return
        local.update { it.copy(deleted = null) }
        viewModelScope.launch { repository.forget(listOf(removed)) }
    }

    private fun saveNote() {
        noteSave?.cancel()
        noteSave = null
        val open = local.value.open ?: return
        val note = CookedPhoto.cleanNote(local.value.noteDraft)
        val current = uiState.value.photos.firstOrNull { it.id == open.id } ?: open
        if (note == current.note) return
        viewModelScope.launch { repository.edit(open.id, current.day, note) }
    }

    override fun onCleared() {
        // The screen is gone: a pending note is written, and a pending delete stands.
        val open = local.value.open
        val removed = local.value.deleted
        if (open != null || removed != null) {
            // viewModelScope is cancelled by now, as in RecipeViewModel.onCleared.
            CoroutineScope(Dispatchers.Unconfined).launch {
                if (open != null) {
                    val note = CookedPhoto.cleanNote(local.value.noteDraft)
                    if (note != open.note) repository.edit(open.id, open.day, note)
                }
                removed?.let { repository.forget(listOf(it)) }
            }
        }
    }

    companion object {
        const val NOTE_DELAY_MILLIS = 500L
    }
}
