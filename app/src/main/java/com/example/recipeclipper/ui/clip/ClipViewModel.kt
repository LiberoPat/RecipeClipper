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
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.UrlCleaner
import com.example.recipeclipper.data.remote.RedditPageText
import com.example.recipeclipper.data.remote.RedditUrls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the snackbar says. The screen picks the words; [Removed] offers Undo. */
sealed class ClipMessage {
    /** A page tag took back one add: [count] lines of [field] (1 for a name or a photo). */
    data class Removed(val field: ClipField, val count: Int) : ClipMessage()

    /** A session draft for this page was brought back; offers Discard. */
    object DraftRestored : ClipMessage()
    object SaveFailed : ClipMessage()

    /** The tap while picking a photo found no picture the app can read; the photo is optional. */
    object PhotoUnreadable : ClipMessage()

    /** The Unlock from the full-library prompt (#107) is pending or failed. */
    data class Unlock(val outcome: PurchaseOutcome) : ClipMessage()

    /**
     * Done or Save with too little to save: what's still missing, a [name] and/or ingredients or
     * steps ([lines]). Save never does nothing silently.
     */
    data class Missing(val name: Boolean, val lines: Boolean) : ClipMessage()

    /** The Text view found no post in the page yet (still loading, or Reddit's check). */
    object TextUnreadable : ClipMessage()
}

/**
 * Where the cook is with Cloudflare's check (#220), when the clip view opened for it in the
 * import's place. [WAITING]: the page is the check, with a note saying what to do, and no clip
 * toolbar; each page it settles on is read, and one with a recipe opens as an ordinary import.
 * [NO_RECIPE]: past the check, the page has no recipe data, so the clip toolbar is offered with
 * a note saying why.
 */
enum class ClipCheck { WAITING, NO_RECIPE }

/** A [ClipMessage] to show once. [serial] tells two identical messages apart. */
data class ClipNotice(val message: ClipMessage, val serial: Long)

/** The add just made, for the hint bar (#237): [count] lines put into [field]. */
data class ClipAdded(val field: ClipField, val count: Int)

/**
 * The one line over the field buttons (#237): what to do now. The screen picks the words.
 */
sealed class ClipHint {
    /** Photo is armed: tap the recipe's photo (or Skip). */
    object PickPhoto : ClipHint()

    /** [field] is armed and nothing is selected yet: tap (or select) it on the page. */
    data class Select(val field: ClipField) : ClipHint()

    /** [field] is armed and [lines] are selected: one tap adds them. */
    data class Confirm(val field: ClipField, val lines: Int) : ClipHint()

    /** Text selected with no field armed (a long press): tap the field it goes in. */
    data class Selected(val lines: Int) : ClipHint()

    /** What was just [added], if anything, and the field to fill [next] (null: all there). */
    data class Next(val added: ClipAdded?, val next: ClipField?) : ClipHint()
}

/**
 * [selection] is the page's current selection split the way it would be assigned (one item a
 * line). [newMarkId] is the mark the page should take from its current selection: the id the
 * last assignment recorded. [savedRecipeId] is set once Save lands, so the screen can open it.
 *
 * Field first (#237): [armed] is the field the cook tapped, which the page's taps select for;
 * [lastAdded] is the add just made; [clearSelection] counts the times the page's selection was
 * dropped (a disarm, Clear, the other view), so the page clears its own when it changes.
 */
data class ClipUiState(
    val url: String,
    val draft: ClipDraft,
    val selection: List<String> = emptyList(),
    val armed: ClipField? = null,
    val lastAdded: ClipAdded? = null,
    val clearSelection: Int = 0,
    val newMarkId: String? = null,
    val reviewing: Boolean = false,
    val saving: Boolean = false,
    val notice: ClipNotice? = null,
    val savedRecipeId: Long? = null,
    /** The clip couldn't be saved: the free library is full and all protected (#107). */
    val libraryFull: Boolean = false,
    /**
     * Opened by itself because Reddit wouldn't let the app read the post (#213): the screen
     * says so above the page.
     */
    val readBlocked: Boolean = false,
    /** Opened for the cook to pass Cloudflare's check (#220); null for every other clip. */
    val check: ClipCheck? = null,
    /**
     * The address the page loads: [url], except a Reddit link on another of Reddit's hosts,
     * which loads from www.reddit.com (`RedditUrls.clipPageUrl`, #213). The clip is still saved
     * under [url].
     */
    val pageUrl: String = url,
    /** A Reddit post (#213): the clip offers the Text view beside the page. */
    val offersText: Boolean = false,
    /** The Text view asked for: the view layer reads the page and hands it to [ClipViewModel.onPageText]. */
    val readingText: Boolean = false,
    /** The post as plain text, read from the page the last time the Text view opened. */
    val pageText: RedditPageText? = null,
    /** The Text view is showing, over the page, which stays loaded under it. */
    val showingText: Boolean = false
) {
    /** Photo is armed: the page's next tap picks the photo. */
    val pickingPhoto: Boolean get() = armed == ClipField.PHOTO

    /** The armed field a page selection goes to, when it is one of the text fields. */
    val armedText: ClipField? get() = armed?.takeUnless { it == ClipField.PHOTO }

    val hint: ClipHint
        get() = when {
            pickingPhoto -> ClipHint.PickPhoto
            selection.isNotEmpty() && armed != null ->
                ClipHint.Confirm(armed, if (armed == ClipField.NAME) 1 else selection.size)
            selection.isNotEmpty() -> ClipHint.Selected(selection.size)
            lastAdded != null -> ClipHint.Next(lastAdded, draft.nextField)
            armed != null -> ClipHint.Select(armed)
            else -> ClipHint.Next(null, draft.nextField)
        }
}

/**
 * "Clip it yourself" (#37): on a page with no recipe data, the user taps a field (Name,
 * Ingredients, Steps, Photo), then taps or selects its text on the page and confirms (#237).
 * The page itself lives in the view layer; this only hears what happened on it (a selection,
 * a tag or image tapped) and holds the [ClipDraft], the armed field and the undo stack.
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

    // The drafts before each change made on the page (an add, a tag's removal), newest last, for
    // Undo. A hand edit in Review empties it: Undo never steps over typing.
    private val undo = ArrayDeque<ClipDraft>()

    private var serial = 0L

    // Reading the page the check settled on (#220); a newer page replaces it.
    private var checkJob: Job? = null

    // A recipe read past the check that a full library didn't keep (#107), for the Unlock.
    private var unkept: Recipe? = null

    init {
        val restored = drafts.get(url) ?: restoreFrom(savedStateHandle)
        _uiState = MutableStateFlow(
            ClipUiState(
                url = url,
                pageUrl = RedditUrls.clipPageUrl(url),
                draft = restored ?: ClipDraft(url),
                notice = restored?.let { notice(ClipMessage.DraftRestored) },
                readBlocked = savedStateHandle.get<Boolean>(BLOCKED_ARG) == true,
                check = ClipCheck.WAITING.takeIf { savedStateHandle.get<Boolean>(CHECK_ARG) == true },
                offersText = RedditUrls.isReddit(url)
            )
        )
        uiState = _uiState.asStateFlow()
    }

    // --- Events from the page ---

    fun onSelectionChanged(text: String) {
        // Selecting new text (a long press) moves on from the photo: the other fields never wait on it.
        val selectedAnew = text != selectionText
        selectionText = text
        val lines = ClipSelection.lines(text)
        _uiState.update {
            it.copy(
                selection = lines,
                // A new selection is never the one the last assignment was taken from.
                newMarkId = if (lines.isEmpty()) it.newMarkId else null,
                armed = if (it.pickingPhoto && lines.isNotEmpty() && selectedAnew) null else it.armed,
                lastAdded = if (lines.isEmpty()) it.lastAdded else null
            )
        }
    }

    /** Tapping an add's tag on the page takes that add back, with Undo. */
    fun onTagTapped(markId: String) {
        val draft = _uiState.value.draft
        val mark = draft.mark(markId)
        _uiState.update { it.copy(armed = it.armed.takeUnless { a -> a == ClipField.PHOTO }, lastAdded = null) }
        if (mark == null) return
        push(draft)
        setDraft(draft.removeMark(markId), newMarkId = null, message = ClipMessage.Removed(mark.field, mark.lines.size))
    }

    /** While Photo is armed, the image tapped on the page becomes the photo. */
    fun onImageTapped(src: String) {
        if (!_uiState.value.pickingPhoto) return
        disarm()
        assign(ClipField.PHOTO, src)
    }

    /**
     * While Photo is armed, the tap found no picture with an address the app can read (not an
     * image, or one drawn some other way). Picking ends and the screen says so: no photo is
     * better than a guessed one, and the photo is optional.
     */
    fun onNoImageTapped() {
        if (!_uiState.value.pickingPhoto) return
        _uiState.update { it.copy(armed = null, notice = notice(ClipMessage.PhotoUnreadable)) }
    }

    /**
     * The page settled while waiting on Cloudflare's check (#220), as [html]: read through the
     * repository. A recipe opens as an import would ([ClipUiState.savedRecipeId]); the check
     * still showing keeps waiting; a page past it with no recipe offers the clip.
     */
    fun onPageLoaded(html: String) {
        if (_uiState.value.check != ClipCheck.WAITING) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            val result = repository.importPage(url, html)
            if (_uiState.value.check != ClipCheck.WAITING) return@launch
            when {
                result is ParseResult.Success && result.kept -> {
                    drafts.remove(url)
                    clearSavedState()
                    _uiState.update { it.copy(savedRecipeId = result.recipe.id) }
                }
                result is ParseResult.Success -> {
                    unkept = result.recipe
                    _uiState.update { it.copy(libraryFull = true) }
                }
                result is ParseResult.Error && result.error == ParseError.HumanCheck -> Unit
                result is ParseResult.Error && result.error == ParseError.SaveFailed ->
                    _uiState.update { it.copy(notice = notice(ClipMessage.SaveFailed)) }
                else -> _uiState.update { it.copy(check = ClipCheck.NO_RECIPE) }
            }
        }
    }

    // --- Events from the toolbar: field first (#237) ---

    /**
     * A field button: arms [field], so the page's taps select for it (or, for the photo, the
     * next tap picks it); the armed field again disarms it, and another switches. A selection
     * already made stays for the field switched to; disarming drops it. The photo is picked on
     * the page, so arming it also leaves the Text view.
     */
    fun onFieldButton(field: ClipField) {
        val state = _uiState.value
        when {
            state.armed == field -> {
                if (state.selection.isNotEmpty()) clearSelection()
                _uiState.update { it.copy(armed = null, lastAdded = null) }
            }
            field == ClipField.PHOTO -> {
                if (state.selection.isNotEmpty()) clearSelection()
                _uiState.update { it.copy(armed = field, lastAdded = null, showingText = false) }
            }
            else -> _uiState.update { it.copy(armed = field, lastAdded = null) }
        }
    }

    /**
     * The hint bar's confirm ("Add 12 lines to Ingredients"): the selection goes into the armed
     * field. A name replaces and disarms; ingredients and steps add, and stay armed for the next
     * block.
     */
    fun onConfirm() {
        val field = _uiState.value.armedText ?: return
        if (_uiState.value.selection.isEmpty()) return
        assign(field, selectionText)
        if (field == ClipField.NAME) disarm()
    }

    /** The hint bar's Clear: drops the selection, keeping the field armed. */
    fun onClearSelection() = clearSelection()

    // --- The Text view (#213) ---

    /**
     * Asks for the Text view: the view layer reads the page as it stands (with whatever
     * comments it has loaded) and calls [onPageText]. The selection belongs to the view it was
     * made in, so it goes; an armed text field stays armed.
     */
    fun onShowText() {
        if (!_uiState.value.offersText) return
        clearSelection()
        _uiState.update { it.copy(readingText = true, armed = it.armedText) }
    }

    /** The page's markup, read for the Text view. A page with no post in it yet says so. */
    fun onPageText(html: String) {
        if (!_uiState.value.readingText) return
        val text = RedditPageText.parse(html)
        _uiState.update {
            if (text.isEmpty) {
                it.copy(readingText = false, notice = notice(ClipMessage.TextUnreadable))
            } else {
                // The same text keeps the same value, so the view doesn't reload it (and its marks).
                it.copy(readingText = false, showingText = true, pageText = if (text == it.pageText) it.pageText else text)
            }
        }
    }

    /** Back to the page from the Text view: the draft and the armed field carry over. */
    fun onShowPage() {
        clearSelection()
        _uiState.update { it.copy(showingText = false, readingText = false) }
    }

    private fun clearSelection() {
        selectionText = ""
        _uiState.update { it.copy(selection = emptyList(), newMarkId = null, clearSelection = it.clearSelection + 1) }
    }

    /** Leaves the photo step without a (new) photo: it's optional. */
    fun onSkipPhoto() = disarm()

    /** Takes back the last change from the page (an add, or a tag's removal). */
    fun onUndo() {
        val previous = undo.removeLastOrNull() ?: return
        _uiState.update { it.copy(lastAdded = null) }
        setDraft(previous, newMarkId = null)
    }

    /** Throws the session draft away and starts over on the same page. */
    fun onDiscardDraft() {
        undo.clear()
        drafts.remove(url)
        _uiState.update { it.copy(lastAdded = null) }
        setDraft(ClipDraft(url), newMarkId = null)
    }

    fun onNoticeShown(serial: Long) {
        _uiState.update { if (it.notice?.serial == serial) it.copy(notice = null) else it }
    }

    // --- Review ---

    /**
     * Done: Review, when there's something to review. Never a silent no: with no name but some
     * lines, Review opens with a note to type the name there (a Reddit title is hard to select);
     * with no lines, the page stays and the note says what to select.
     */
    fun onReview() {
        val draft = _uiState.value.draft
        val missing = missing(draft)
        when {
            missing == null -> _uiState.update { it.copy(reviewing = true, armed = it.armedText) }
            !missing.lines -> _uiState.update { it.copy(reviewing = true, armed = it.armedText, notice = notice(missing)) }
            else -> _uiState.update { it.copy(notice = notice(missing)) }
        }
    }

    /** What the draft still needs before it can be saved, or null when it can be. */
    private fun missing(draft: ClipDraft): ClipMessage.Missing? =
        if (draft.canFinish) null else ClipMessage.Missing(name = draft.name.isBlank(), lines = !draft.hasLines)

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
        val recipe = state.draft.toRecipe()
        if (recipe == null) {
            missing(state.draft)?.let { missing -> _uiState.update { it.copy(notice = notice(missing)) } }
            return
        }
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

    /**
     * Unlock from the full-library prompt (#107), then save the clip as it stands, or the recipe
     * read past Cloudflare's check (#220).
     */
    fun onUnlock() {
        _uiState.update { it.copy(libraryFull = false) }
        viewModelScope.launch {
            val outcome = entitlements.purchase()
            if (outcome == PurchaseOutcome.UNLOCKED) {
                val read = unkept
                if (read == null) return@launch onSave()
                val kept = repository.keep(read)
                if (kept is ParseResult.Success && kept.kept) {
                    unkept = null
                    _uiState.update { it.copy(savedRecipeId = kept.recipe.id) }
                } else {
                    _uiState.update { it.copy(notice = notice(ClipMessage.SaveFailed)) }
                }
            } else if (outcome.needsNotice) {
                _uiState.update { it.copy(notice = notice(ClipMessage.Unlock(outcome))) }
            }
        }
    }

    fun onLibraryFullDismiss() = _uiState.update { it.copy(libraryFull = false) }

    // --- Internals ---

    private fun disarm() = _uiState.update { it.copy(armed = null) }

    private fun push(draft: ClipDraft) {
        undo.addLast(draft)
        if (undo.size > UNDO_DEPTH) undo.removeAt(0)
    }

    /**
     * The add itself. Its words go in the hint bar ([ClipUiState.lastAdded]), with Undo there,
     * rather than a snackbar. The page drops its selection when it records the new mark.
     */
    private fun assign(field: ClipField, text: String) {
        val draft = _uiState.value.draft
        val markId = draft.pendingMarkId
        val assigned = draft.assign(field, text)
        if (assigned === draft) return
        push(draft)
        selectionText = ""
        _uiState.update {
            it.copy(selection = emptyList(), lastAdded = ClipAdded(field, assigned.mark(markId)?.lines?.size ?: 1))
        }
        setDraft(assigned, newMarkId = if (field == ClipField.PHOTO) null else markId)
    }

    /** A hand edit in Review. Not undoable, so it ends Undo for what came before. */
    private fun edit(change: (ClipDraft) -> ClipDraft) {
        undo.clear()
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

        /** True when Reddit's block opened this in the import's place (#213). */
        const val BLOCKED_ARG = "blocked"

        /** True when Cloudflare's check opened this in the import's place (#220). */
        const val CHECK_ARG = "check"
        private const val KEY_NAME = "clip.name"
        private const val KEY_INGREDIENTS = "clip.ingredients"
        private const val KEY_STEPS = "clip.steps"
        private const val KEY_PHOTO = "clip.photo"
        private const val KEY_SERVES = "clip.serves"
        private const val KEY_TOTAL_TIME = "clip.totalTime"
        /** How many changes Undo can take back. */
        private const val UNDO_DEPTH = 50
        private val ALL_KEYS = listOf(KEY_NAME, KEY_INGREDIENTS, KEY_STEPS, KEY_PHOTO, KEY_SERVES, KEY_TOTAL_TIME)
    }
}
