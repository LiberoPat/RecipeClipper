package com.example.recipeclipper.ui.clip

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.ClipDraftStore
import com.example.recipeclipper.data.Entitlements
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.needsNotice
import com.example.recipeclipper.data.model.ClipDraft
import com.example.recipeclipper.data.model.ClipField
import com.example.recipeclipper.data.model.ClipSelection
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.UrlCleaner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the snackbar says. The screen picks the words; [Assigned] and [Cleared] offer Undo. */
sealed class ClipMessage {
    data class Assigned(val field: ClipField, val count: Int) : ClipMessage()
    data class Cleared(val field: ClipField) : ClipMessage()

    /** A session draft for this page was brought back; offers Discard. */
    object DraftRestored : ClipMessage()
    object SaveFailed : ClipMessage()

    /** The Unlock from the full-library prompt (#107) is pending or failed. */
    data class Unlock(val outcome: PurchaseOutcome) : ClipMessage()
}

/** A [ClipMessage] to show once. [serial] tells two identical messages apart. */
data class ClipNotice(val message: ClipMessage, val serial: Long)

/**
 * [selection] is the page's current selection split the way it would be assigned (one item a
 * line). [newMarkId] is the mark the page should take from its current selection: the id the
 * last assignment recorded. [savedRecipeId] is set once Save lands, so the screen can open it.
 */
data class ClipUiState(
    val url: String,
    val draft: ClipDraft,
    val selection: List<String> = emptyList(),
    val pickingPhoto: Boolean = false,
    val newMarkId: String? = null,
    val reviewing: Boolean = false,
    val saving: Boolean = false,
    val notice: ClipNotice? = null,
    val savedRecipeId: Long? = null,
    /** The clip couldn't be saved: the free library is full and all protected (#107). */
    val libraryFull: Boolean = false
)

/**
 * "Clip it yourself" (#37): the user selects text on a page with no recipe data and says where
 * each part goes. The page itself lives in the view layer; this only hears what happened on it
 * (a selection, a tag or image tapped) and holds the [ClipDraft].
 *
 * The draft is kept in [ClipDraftStore] as it changes, so Cancel keeps it for the session, and
 * mirrored into [SavedStateHandle] so it survives the process being killed in the background.
 */
@HiltViewModel
class ClipViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: RecipeRepository,
    private val drafts: ClipDraftStore,
    private val entitlements: Entitlements = Entitlements.Unavailable
) : ViewModel() {

    private val url: String = UrlCleaner.clean(savedStateHandle.get<String>(URL_ARG).orEmpty())

    private val _uiState: MutableStateFlow<ClipUiState>
    val uiState: StateFlow<ClipUiState>

    // The page's selection as given, before splitting: a name joins it rather than splitting it.
    private var selectionText = ""

    // The draft before the last assignment or clear, for the snackbar's Undo.
    private var undoTo: ClipDraft? = null

    private var serial = 0L

    init {
        val restored = drafts.get(url) ?: restoreFrom(savedStateHandle)
        _uiState = MutableStateFlow(
            ClipUiState(
                url = url,
                draft = restored ?: ClipDraft(url),
                notice = restored?.let { notice(ClipMessage.DraftRestored) }
            )
        )
        uiState = _uiState.asStateFlow()
    }

    // --- Events from the page ---

    fun onSelectionChanged(text: String) {
        selectionText = text
        val lines = ClipSelection.lines(text)
        _uiState.update {
            // A new selection is never the one the last assignment was taken from.
            it.copy(selection = lines, newMarkId = if (lines.isEmpty()) it.newMarkId else null)
        }
    }

    /** Tapping a field's tag on the page clears that field, with Undo. */
    fun onTagTapped(field: ClipField) {
        val draft = _uiState.value.draft
        if (draft.count(field) == 0) return
        undoTo = draft
        setDraft(draft.clear(field), newMarkId = null, message = ClipMessage.Cleared(field))
    }

    /** While picking a photo, the next image tapped on the page becomes the photo. */
    fun onImageTapped(src: String) {
        if (!_uiState.value.pickingPhoto) return
        _uiState.update { it.copy(pickingPhoto = false) }
        assign(ClipField.PHOTO, src)
    }

    // --- Events from the toolbar ---

    /** Puts the current selection into [field], replacing what it held. */
    fun onAssign(field: ClipField) {
        if (field == ClipField.PHOTO) return
        assign(field, selectionText)
    }

    fun onPhotoButton() {
        _uiState.update { it.copy(pickingPhoto = !it.pickingPhoto) }
    }

    fun onUndo() {
        val previous = undoTo ?: return
        undoTo = null
        setDraft(previous, newMarkId = null)
    }

    /** Throws the session draft away and starts over on the same page. */
    fun onDiscardDraft() {
        undoTo = null
        drafts.remove(url)
        setDraft(ClipDraft(url), newMarkId = null)
    }

    fun onNoticeShown(serial: Long) {
        _uiState.update { if (it.notice?.serial == serial) it.copy(notice = null) else it }
    }

    // --- Review ---

    fun onReview() {
        if (_uiState.value.draft.canFinish) _uiState.update { it.copy(reviewing = true, pickingPhoto = false) }
    }

    fun onBackToPage() = _uiState.update { it.copy(reviewing = false) }

    fun onNameChange(text: String) = edit { it.copy(name = text) }
    fun onServesChange(text: String) = edit { it.copy(serves = text) }
    fun onTotalTimeChange(text: String) = edit { it.copy(totalTime = text) }
    fun onLineChange(field: ClipField, index: Int, text: String) = edit { it.editLine(field, index, text) }
    fun onLineRemove(field: ClipField, index: Int) = edit { it.removeLine(field, index) }
    fun onLineAdd(field: ClipField) = edit { it.addLine(field) }
    fun onRemovePhoto() = edit { it.clear(ClipField.PHOTO) }

    fun onSave() {
        val state = _uiState.value
        if (state.saving) return
        val recipe = state.draft.toRecipe() ?: return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            when (val result = repository.saveClip(recipe)) {
                is ParseResult.Success -> if (!result.kept) {
                    _uiState.update { it.copy(saving = false, libraryFull = true) }
                } else {
                    drafts.remove(url)
                    clearSavedState()
                    _uiState.update { it.copy(saving = false, savedRecipeId = result.recipe.id) }
                }
                is ParseResult.Error -> _uiState.update {
                    it.copy(saving = false, notice = notice(ClipMessage.SaveFailed))
                }
            }
        }
    }

    /** Unlock from the full-library prompt (#107), then save the clip as it stands. */
    fun onUnlock() {
        _uiState.update { it.copy(libraryFull = false) }
        viewModelScope.launch {
            val outcome = entitlements.purchase()
            if (outcome == PurchaseOutcome.UNLOCKED) onSave()
            else if (outcome.needsNotice) _uiState.update { it.copy(notice = notice(ClipMessage.Unlock(outcome))) }
        }
    }

    fun onLibraryFullDismiss() = _uiState.update { it.copy(libraryFull = false) }

    // --- Internals ---

    private fun assign(field: ClipField, text: String) {
        val draft = _uiState.value.draft
        val markId = draft.pendingMarkId
        val assigned = draft.assign(field, text)
        if (assigned === draft) return
        undoTo = draft
        selectionText = ""
        _uiState.update { it.copy(selection = emptyList()) }
        setDraft(
            assigned,
            newMarkId = if (field == ClipField.PHOTO) null else markId,
            message = ClipMessage.Assigned(field, assigned.count(field))
        )
    }

    /** A hand edit in Review. Not undoable from the snackbar, so it ends any pending Undo. */
    private fun edit(change: (ClipDraft) -> ClipDraft) {
        undoTo = null
        setDraft(change(_uiState.value.draft), newMarkId = _uiState.value.newMarkId)
    }

    private fun setDraft(draft: ClipDraft, newMarkId: String?, message: ClipMessage? = null) {
        drafts.put(draft)
        saveState(draft)
        _uiState.update {
            it.copy(
                draft = draft,
                newMarkId = newMarkId,
                notice = message?.let(::notice) ?: it.notice,
                // Discarding everything leaves nothing to review.
                reviewing = it.reviewing && !draft.isEmpty
            )
        }
    }

    private fun notice(message: ClipMessage) = ClipNotice(message, ++serial)

    private fun saveState(draft: ClipDraft) {
        if (draft.isEmpty) return clearSavedState()
        savedStateHandle[KEY_NAME] = draft.name
        savedStateHandle[KEY_INGREDIENTS] = ArrayList(draft.ingredients)
        savedStateHandle[KEY_STEPS] = ArrayList(draft.steps)
        savedStateHandle[KEY_PHOTO] = draft.photo
        savedStateHandle[KEY_SERVES] = draft.serves
        savedStateHandle[KEY_TOTAL_TIME] = draft.totalTime
    }

    private fun clearSavedState() {
        ALL_KEYS.forEach { savedStateHandle.remove<Any>(it) }
    }

    /** The draft mirrored before the process died. Its page marks are gone with the page. */
    private fun restoreFrom(handle: SavedStateHandle): ClipDraft? {
        if (!handle.contains(KEY_NAME)) return null
        return ClipDraft(
            sourceUrl = url,
            name = handle.get<String>(KEY_NAME).orEmpty(),
            ingredients = handle.get<ArrayList<String>>(KEY_INGREDIENTS).orEmpty(),
            steps = handle.get<ArrayList<String>>(KEY_STEPS).orEmpty(),
            photo = handle.get<String>(KEY_PHOTO),
            serves = handle.get<String>(KEY_SERVES).orEmpty(),
            totalTime = handle.get<String>(KEY_TOTAL_TIME).orEmpty()
        ).takeUnless { it.isEmpty }
    }

    companion object {
        const val URL_ARG = "url"
        private const val KEY_NAME = "clip.name"
        private const val KEY_INGREDIENTS = "clip.ingredients"
        private const val KEY_STEPS = "clip.steps"
        private const val KEY_PHOTO = "clip.photo"
        private const val KEY_SERVES = "clip.serves"
        private const val KEY_TOTAL_TIME = "clip.totalTime"
        private val ALL_KEYS = listOf(KEY_NAME, KEY_INGREDIENTS, KEY_STEPS, KEY_PHOTO, KEY_SERVES, KEY_TOTAL_TIME)
    }
}
