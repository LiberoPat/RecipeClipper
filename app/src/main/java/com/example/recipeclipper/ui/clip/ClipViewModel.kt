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
import com.example.recipeclipper.data.model.WebImageUrl
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
    /**
     * Guided steps (#251): [field] is armed and nothing is selected yet, so the header is "Step
     * [number] of [ClipUiState.STEPS] · Tap the …", with Skip. [added] is the add that just armed
     * it, if any, offered for Undo.
     */
    data class Step(val field: ClipField, val number: Int, val added: ClipAdded?) : ClipHint()

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
 *
 * Guided steps (#251): the armed field is the current step, Name → Ingredients → Steps → Photo;
 * [skipped] holds the steps the cook skipped. The steps are over ([guideDone]) once each one is
 * filled or skipped.
 */
data class ClipUiState(
    val url: String,
    val draft: ClipDraft,
    val selection: List<String> = emptyList(),
    val armed: ClipField? = null,
    val skipped: Set<ClipField> = emptySet(),
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

    /** What the page outlines (#251): what a tap there would take for the armed field. */
    val outline: ClipOutline?
        get() = when {
            pickingPhoto -> ClipOutline.IMAGES
            armed != null -> ClipOutline.TEXT
            else -> null
        }

    /** Every step is filled or skipped: the header is the Done prompt. */
    val guideDone: Boolean get() = nextStep(null) == null

    /**
     * The step after [from] still to do (empty and not skipped), in the steps' order and then
     * round from the start, never [from] itself; null when there is none.
     */
    fun nextStep(from: ClipField?): ClipField? {
        val order = ClipField.entries
        val start = from?.let { order.indexOf(it) + 1 } ?: 0
        return order.indices.map { order[(start + it) % order.size] }
            .firstOrNull { it != from && draft.count(it) == 0 && it !in skipped }
    }

    val hint: ClipHint
        get() {
            val text = armedText
            return when {
                selection.isNotEmpty() && text != null ->
                    ClipHint.Confirm(text, if (text == ClipField.NAME) 1 else selection.size)
                selection.isNotEmpty() -> ClipHint.Selected(selection.size)
                armed != null -> ClipHint.Step(armed, armed.ordinal + 1, lastAdded)
                else -> ClipHint.Next(lastAdded, nextStep(null))
            }
        }

    companion object {
        /** How many guided steps there are: one a field. */
        val STEPS = ClipField.entries.size
    }
}

/** What the page outlines while a field is armed (#251): its blocks of text, or its images. */
enum class ClipOutline { TEXT, IMAGES }

/**
 * "Clip it yourself" (#37): on a page with no recipe data, the user taps a field (Name,
 * Ingredients, Steps, Photo), then taps or selects its text on the page and confirms (#237).
 * The page itself lives in the view layer; this only hears what happened on it (a selection,
 * a tag or image tapped) and holds the [ClipDraft], the armed field and the last change, for Undo.
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

    // The draft before the last change made on the page (an add, a tag's removal), for Undo, with
    // the field an add filled (null for a removal), which Undo makes the current step again. One
    // change only (#264): Undo is offered only for the change just made, by the hint bar while
    // [ClipUiState.lastAdded] is set, or by a removal's snackbar, which goes at the next change. A
    // hand edit in Review drops it: Undo never steps over typing.
    private var undo: Pair<ClipDraft, ClipField?>? = null

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
            ).startGuide()
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
        push(draft, null)
        setDraft(draft.removeMark(markId), newMarkId = null, message = ClipMessage.Removed(mark.field, mark.lines.size))
    }

    /**
     * While picking a photo, the next image tapped on the page becomes the photo: a web image
     * only ([WebImageUrl], #235). Any other address (`file:`, `content:`, `data:`, relative, …)
     * is no picture, as [onNoImageTapped] says.
     */
    fun onImageTapped(src: String) {
        if (!_uiState.value.pickingPhoto) return
        val photo = WebImageUrl.of(src) ?: return onNoImageTapped()
        disarm()
        if (assign(ClipField.PHOTO, photo)) advanceFrom(ClipField.PHOTO)
    }

    /**
     * While Photo is armed, the tap found no picture with an address the app can read (not an
     * image, or one drawn some other way). Picking ends and the screen says so: no photo is
     * better than a guessed one, and the photo is optional, so the step counts as skipped.
     */
    fun onNoImageTapped() {
        if (!_uiState.value.pickingPhoto) return
        _uiState.update {
            it.copy(armed = null, skipped = it.skipped + ClipField.PHOTO, notice = notice(ClipMessage.PhotoUnreadable))
        }
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
                else -> _uiState.update { it.copy(check = ClipCheck.NO_RECIPE).startGuide() }
            }
        }
    }

    // --- Events from the toolbar: field first (#237) ---

    /**
     * A field button. With text already selected (a long press, or a tap) and a text field, the
     * selection goes straight into it (#251: choosing the field is the confirm), as the confirm
     * would put it, and the next step arms. Otherwise it arms [field] and makes it the current
     * step, so the page's taps select for it (or, for the photo, the next tap picks it); the
     * armed field again disarms it. The photo is picked on the page, so arming it drops a
     * selection and leaves the Text view.
     */
    fun onFieldButton(field: ClipField) {
        val state = _uiState.value
        when {
            field != ClipField.PHOTO && state.selection.isNotEmpty() -> add(field)
            state.armed == field -> _uiState.update { it.copy(armed = null, lastAdded = null) }
            field == ClipField.PHOTO -> {
                if (state.selection.isNotEmpty()) clearSelection()
                _uiState.update {
                    it.copy(armed = field, skipped = it.skipped - field, lastAdded = null, showingText = false)
                }
            }
            else -> _uiState.update { it.copy(armed = field, skipped = it.skipped - field, lastAdded = null) }
        }
    }

    /**
     * The hint bar's confirm ("Add 12 lines to Ingredients"): the selection goes into the armed
     * field (a name replaces; ingredients and steps add), and the next step still to do arms
     * (#251). Another block for the same field is one tap on its button away.
     */
    fun onConfirm() {
        val field = _uiState.value.armedText ?: return
        if (_uiState.value.selection.isEmpty()) return
        add(field)
    }

    private fun add(field: ClipField) {
        if (!assign(field, selectionText)) return
        _uiState.update { it.copy(skipped = it.skipped - field) }
        advanceFrom(field)
    }

    /**
     * The current step is done: the next one still to do arms, or none, and the steps are over.
     * In the Text view the Photo step waits for the page: its photo is picked there.
     */
    private fun advanceFrom(field: ClipField) = _uiState.update { it.copy(armed = it.nextStep(field)) }

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

    /**
     * Skip (#251): leaves the current step as it is and arms the next one still to do. Skip on
     * Photo, the last and optional step, ends the steps.
     */
    fun onSkip() {
        val field = _uiState.value.armed ?: return
        if (_uiState.value.selection.isNotEmpty()) clearSelection()
        _uiState.update {
            val skipped = it.skipped + field
            val next = if (field == ClipField.PHOTO) null else it.copy(skipped = skipped).nextStep(field)
            it.copy(skipped = skipped, armed = next, lastAdded = null)
        }
    }

    /**
     * Takes back the last change from the page (an add, or a tag's removal). An add's field is
     * the current step again, to redo it.
     */
    fun onUndo() {
        val (previous, field) = undo ?: return
        undo = null
        dismissRemoved()
        _uiState.update {
            if (field == null) it.copy(lastAdded = null) else it.copy(lastAdded = null, armed = field, skipped = it.skipped - field)
        }
        setDraft(previous, newMarkId = null)
    }

    /** Throws the session draft away and starts over on the same page, from the first step. */
    fun onDiscardDraft() {
        undo = null
        drafts.remove(url)
        _uiState.update { it.copy(lastAdded = null, skipped = emptySet()) }
        setDraft(ClipDraft(url), newMarkId = null)
        _uiState.update { it.startGuide() }
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

    private fun push(draft: ClipDraft, field: ClipField?) {
        undo = draft to field
        dismissRemoved()
    }

    /**
     * A removal's snackbar goes once another change is made (#264): its Undo takes back the
     * last change, which is then no longer that removal.
     */
    private fun dismissRemoved() =
        _uiState.update { if (it.notice?.message is ClipMessage.Removed) it.copy(notice = null) else it }

    /**
     * The add itself. Its words go in the hint bar ([ClipUiState.lastAdded]), with Undo there,
     * rather than a snackbar. The page drops its selection when it records the new mark. False
     * when there was nothing to add.
     */
    private fun assign(field: ClipField, text: String): Boolean {
        val draft = _uiState.value.draft
        val markId = draft.pendingMarkId
        val assigned = draft.assign(field, text)
        if (assigned === draft) return false
        push(draft, field)
        selectionText = ""
        _uiState.update {
            it.copy(selection = emptyList(), lastAdded = ClipAdded(field, assigned.mark(markId)?.lines?.size ?: 1))
        }
        setDraft(assigned, newMarkId = if (field == ClipField.PHOTO) null else markId)
        return true
    }

    /** A hand edit in Review. Not undoable, so it ends Undo for what came before. */
    private fun edit(change: (ClipDraft) -> ClipDraft) {
        undo = null
        dismissRemoved()
        _uiState.update { it.copy(lastAdded = null) }
        setDraft(change(_uiState.value.draft), newMarkId = _uiState.value.newMarkId)
    }

    private fun setDraft(changed: ClipDraft, newMarkId: String?, message: ClipMessage? = null) {
        // Mark ids only ever rise (#264): an undone or discarded draft's ids are on the page.
        val draft = changed.copy(nextMark = maxOf(changed.nextMark, _uiState.value.draft.nextMark))
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

    /**
     * The draft mirrored before the process died. Its text marks are gone with the page; the
     * photo, which the page finds again by its address, gets a mark of its own (#264), so its tag
     * can still take it back.
     */
    private fun restoreFrom(handle: SavedStateHandle): ClipDraft? {
        if (!handle.contains(KEY_NAME)) return null
        val draft = ClipDraft(
            sourceUrl = url,
            name = handle.get<String>(KEY_NAME).orEmpty(),
            ingredients = handle.get<ArrayList<String>>(KEY_INGREDIENTS).orEmpty(),
            steps = handle.get<ArrayList<String>>(KEY_STEPS).orEmpty(),
            serves = handle.get<String>(KEY_SERVES).orEmpty(),
            totalTime = handle.get<String>(KEY_TOTAL_TIME).orEmpty()
        )
        val photo = handle.get<String>(KEY_PHOTO)
        return (if (photo == null) draft else draft.assign(ClipField.PHOTO, photo)).takeUnless { it.isEmpty }
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
        private val ALL_KEYS = listOf(KEY_NAME, KEY_INGREDIENTS, KEY_STEPS, KEY_PHOTO, KEY_SERVES, KEY_TOTAL_TIME)
    }
}

/**
 * Guided on entry (#251): the first step still to do arms by itself, so the clip opens on
 * "Step 1 of 4 · Tap the recipe's name". Not while the page is Cloudflare's check (#220): a tap
 * there must reach the check.
 */
private fun ClipUiState.startGuide(): ClipUiState =
    if (check == ClipCheck.WAITING) this else copy(armed = nextStep(null))
