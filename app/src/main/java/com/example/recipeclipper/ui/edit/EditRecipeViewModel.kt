package com.example.recipeclipper.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.Entitlements
import com.example.recipeclipper.data.PhotoPost
import com.example.recipeclipper.data.PhotoTextReader
import com.example.recipeclipper.data.PhotoTextResult
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.model.SourceType
import com.example.recipeclipper.data.needsNotice
import com.example.recipeclipper.data.remote.CheckReason
import com.example.recipeclipper.data.remote.LineFlag
import com.example.recipeclipper.data.remote.PhotoBox
import com.example.recipeclipper.data.remote.PhotoReading
import com.example.recipeclipper.data.remote.PhotoTextSorter
import com.example.recipeclipper.data.remote.RecipeTextSplitter
import com.example.recipeclipper.di.DefaultDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** How reading a post's photo (#198) went, for the line above the editor. */
enum class PhotoOutcome {
    /** The splitter sorted the lines into ingredients and steps: check them. */
    READ,
    /** No text, or none the splitter could sort: finish it by hand. */
    NOT_SORTED,
    /** No picture could be fetched or read: try again. */
    FAILED,
    /** The phone's photo reader isn't there yet (Play services' model): try again or type it. */
    NOT_READY
}

/**
 * The strip of a picture a line was read from (#286): [image], a post's picture (a web link, from
 * the image cache the read filled) or a scan's page (a local URI, never cached: [local]), cropped
 * to [box].
 */
data class PhotoStrip(val image: String, val box: PhotoBox, val local: Boolean)

/**
 * A line to check in the review (#198, #283, #286): [flag] as the sorter listed it, kept to the
 * line as it is now in the box ([LineFlag.raw], which the card's field and the box both edit);
 * where that line is, line [index] of the steps box when [steps], else of the ingredients box;
 * and the [strip] of the photo it was read from, null when the reader didn't say where.
 */
data class LineToCheck(val flag: LineFlag, val steps: Boolean, val index: Int, val strip: PhotoStrip?)

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
    val savedId: Long? = null,
    /** A new recipe couldn't be saved: the free library is full and all protected (#107). */
    val libraryFull: Boolean = false,
    /** A purchase from that prompt that is pending or failed; shown until the next edit. */
    val unlockNotice: PurchaseOutcome? = null,
    /** "Read the photo" (#198): the Reddit post whose photos fill this editor; null otherwise.
     *  For a scan (#226) its pictures are the cook's pages, with no link and no title. */
    val photo: PhotoPost? = null,
    /** "Scan a recipe" (#226): the pages are the cook's own, saved as a typed-in recipe. */
    val scan: Boolean = false,
    /** The photos are being fetched and read; the fields wait. */
    val reading: Boolean = false,
    val photoOutcome: PhotoOutcome? = null,
    /** The lines to check against the photo, as the sorter listed them (#198, #283), each with
     *  why: the photo was hard to read there, its amount holds a fraction look-alike (the
     *  [fractions] as one-tap replacements, #245) or looks too large (#281); each a card with
     *  its strip of the photo and its line as a field (#286). */
    val checks: List<LineToCheck> = emptyList(),
    val fractions: List<String> = PhotoTextSorter.FRACTIONS,
    /** The picture shown large under the thumbnails, tapped open (#286); null when none is. */
    val openPage: Int? = null
) {
    /** The [checks]' lines, each with why. */
    val flags: List<LineFlag> get() = checks.map { it.flag }

    /** "Check these lines: the photo was hard to read there" heads the list, unless every line
     *  is listed only because its amount looks too large: each of those says so itself (#281). */
    val checkHeading: Boolean get() = checks.any { !it.flag.largeOnly }

    /** The pictures read, for "2 pages · 5 lines to check" over the cards (#286). */
    val pageCount: Int get() = photo?.imageUrls?.size ?: 0
}

/**
 * Edits a recipe's content, or types a new one in (#29). Opened with a recipe id from the
 * recipe screen's overflow menu, or with none from Home's "New recipe".
 *
 * With a post ([PHOTO_URL_ARG], #198) it is the review of a Reddit photo read on the device:
 * the post's title and first photo, and the lines read, sorted by the Reddit splitter into
 * ingredients and steps, or, when nothing sorts, all in the ingredients box to finish by hand.
 * Nothing is saved until the cook taps Save; then it is kept under the post's link as the
 * user's version (CLIPPED, like #37's clips), so a re-share never replaces it.
 *
 * With pages ([SCAN_PAGES_ARG], #226) it is the review of a scan: photos the cook took, picked
 * or shared in, read the same way, in order. Saved as a typed-in recipe (MANUAL, `manual:<uuid>`)
 * in the language its words say, with no picture: the pages aren't kept.
 */
@HiltViewModel
class EditRecipeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RecipeRepository,
    private val entitlements: Entitlements = Entitlements.Unavailable,
    private val photoReader: PhotoTextReader = PhotoTextReader.Unavailable,
    /** Where the read lines are sorted and checked, off the main thread (#283). */
    @DefaultDispatcher private val sorting: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val recipeId: Long? = savedStateHandle.get<Long>(RECIPE_ID_ARG)?.takeIf { it > 0 }

    private val photo: PhotoPost? =
        savedStateHandle.get<String>(PHOTO_URL_ARG)?.takeIf { it.isNotBlank() }?.let { url ->
            PhotoPost(
                url = url,
                title = savedStateHandle.get<String>(PHOTO_TITLE_ARG).orEmpty(),
                imageUrls = savedStateHandle.get<String>(PHOTO_IMAGES_ARG).orEmpty()
                    .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            )
        }

    /** A scan's pages (#226), local URIs in order; null when this isn't a scan. */
    private val scanPages: List<String>? =
        savedStateHandle.get<String>(SCAN_PAGES_ARG)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() && photo == null && recipeId == null }

    /** What is read: the post's pictures, or the scan's pages. */
    private val pictures: PhotoPost? =
        photo ?: scanPages?.let { PhotoPost(url = "", title = "", imageUrls = it) }

    private val _uiState = MutableStateFlow(
        EditRecipeUiState(isNew = recipeId == null, photo = pictures, scan = scanPages != null)
    )
    val uiState: StateFlow<EditRecipeUiState> = _uiState.asStateFlow()

    private var readJob: Job? = null

    /** The language the photo's lines were read in (#208), or null when nothing was clear. */
    private var photoLanguage: String? = null

    init {
        if (recipeId != null) {
            viewModelScope.launch {
                val recipe = repository.open(recipeId)
                _uiState.update {
                    if (recipe == null) it.copy(loading = false, missing = true)
                    else it.copy(loading = false, draft = RecipeDraft.of(recipe))
                }
            }
        } else if (pictures != null) {
            readPhoto(pictures)
        }
    }

    /** "Try again" after the photos couldn't be fetched or read, or the reader wasn't ready. */
    fun onReadAgain() {
        val post = pictures ?: return
        if (_uiState.value.reading) return
        readPhoto(post)
    }

    private fun readPhoto(post: PhotoPost) {
        readJob?.cancel()
        photoLanguage = null
        _uiState.update {
            it.copy(
                reading = true, photoOutcome = null, checks = emptyList(),
                // A post's first picture is its photo; a scan's pages are never kept (#226).
                draft = RecipeDraft(
                    name = post.title,
                    image = if (scanPages != null) "" else post.imageUrls.firstOrNull().orEmpty()
                )
            )
        }
        readJob = viewModelScope.launch {
            val result = photoReader.read(post.imageUrls)
            // Sorting and checking a long scan is real work: off the main thread (#283). A read
            // cancelled meanwhile (Try again, or the screen gone) is dropped here.
            val reading = (result as? PhotoTextResult.Read)?.let { withContext(sorting) { PhotoTextSorter.sort(it.lines) } }
            photoLanguage = reading?.language
            _uiState.update { state ->
                when {
                    result == PhotoTextResult.NotReady -> state.copy(reading = false, photoOutcome = PhotoOutcome.NOT_READY)
                    reading == null -> state.copy(reading = false, photoOutcome = PhotoOutcome.FAILED)
                    else -> state.copy(
                        reading = false,
                        photoOutcome = if (reading.sorted) PhotoOutcome.READ else PhotoOutcome.NOT_SORTED,
                        checks = checksOf(reading, post),
                        draft = state.draft.copy(
                            yield = reading.yield.orEmpty(),
                            prepTime = reading.prepTime.orEmpty(),
                            cookTime = reading.cookTime.orEmpty(),
                            totalTime = reading.totalTime.orEmpty(),
                            ingredientsText = reading.ingredients.joinToString("\n"),
                            instructionsText = reading.instructions.joinToString("\n")
                        )
                    )
                }
            }
        }
    }

    /** The sorter's list as cards (#286): each line found in its box (the ingredients first, as
     *  listed), with the strip of the picture it was read from. Every listed line is a box's. */
    private fun checksOf(reading: PhotoReading, post: PhotoPost): List<LineToCheck> = reading.flags.mapNotNull { flag ->
        val inIngredients = reading.ingredients.indexOf(flag.raw)
        val steps = inIngredients < 0
        val index = if (steps) reading.instructions.indexOf(flag.raw) else inIngredients
        if (index < 0) return@mapNotNull null
        val image = flag.page?.let { post.imageUrls.getOrNull(it) }
        val box = flag.box
        val strip = if (image != null && box != null) PhotoStrip(image, box, scanPages != null) else null
        LineToCheck(flag, steps, index, strip)
    }

    /**
     * A fraction chip (#245): [flag]'s look-alike ("%" in "2% cups oats") becomes [fraction], the
     * cook's reading of the photo, in the box that holds the line and in its card, which keeps any
     * markup the line has ("▢ 2⅔ cups oats", #283). The card's chips go.
     */
    fun onFraction(flag: LineFlag, fraction: String) = _uiState.update { state ->
        val index = state.flags.indexOf(flag)
        if (index < 0) return@update state
        val fixed = PhotoTextSorter.replaceFraction(flag, fraction, RecipeTextSplitter.wordsFor(photoLanguage))
            ?: return@update state.copy(
                checks = state.checks.toMutableList().apply {
                    this[index] = this[index].copy(flag = flag.copy(reasons = flag.reasons - CheckReason.FRACTION))
                }
            )
        // The card is the box's line, so the box changes with it.
        val changed = withLine(state, index, fixed.raw)
        changed.copy(checks = changed.checks.toMutableList().apply { this[index] = this[index].copy(flag = fixed) })
    }

    /** A card's field (#286): its line, typed over, in the box that holds it. */
    fun onCheckLine(index: Int, text: String) = _uiState.update { withLine(it, index, text.replace("\n", " ")) }

    /** [state] with card [index]'s line in its box holding [line] instead. */
    private fun withLine(state: EditRecipeUiState, index: Int, line: String): EditRecipeUiState {
        val check = state.checks.getOrNull(index) ?: return state
        val box = if (check.steps) state.draft.instructionsText else state.draft.ingredientsText
        val lines = box.split("\n").toMutableList()
        if (check.index !in lines.indices) return state
        lines[check.index] = line
        val text = lines.joinToString("\n")
        return withDraft(state, if (check.steps) state.draft.copy(instructionsText = text) else state.draft.copy(ingredientsText = text))
    }

    fun onDraftChange(draft: RecipeDraft) = _uiState.update { withDraft(it, draft) }

    /**
     * [state] with [draft] (#286): each card follows its line through the edit ([followLine]) and
     * shows it as it now is ([PhotoTextSorter.edited]); a card whose line was edited away goes.
     */
    private fun withDraft(state: EditRecipeUiState, draft: RecipeDraft): EditRecipeUiState {
        val words = RecipeTextSplitter.wordsFor(photoLanguage)
        val old = state.draft.ingredientsText.split("\n") to state.draft.instructionsText.split("\n")
        val new = draft.ingredientsText.split("\n") to draft.instructionsText.split("\n")
        val checks = state.checks.mapNotNull { check ->
            val (before, after) = if (check.steps) old.second to new.second else old.first to new.first
            val index = followLine(before, after, check.index)?.takeIf { it in after.indices } ?: return@mapNotNull null
            check.copy(flag = PhotoTextSorter.edited(check.flag, after[index], words), index = index)
        }
        return state.copy(draft = draft, checks = checks, saveFailed = false, unlockNotice = null)
    }

    /** A page's thumbnail (#286): shows that picture large under the row, or hides it again. */
    fun onPage(page: Int) = _uiState.update { it.copy(openPage = if (it.openPage == page) null else page) }

    fun onSave() {
        val state = _uiState.value
        if (state.loading || state.saving || state.missing || state.reading) return
        if (!state.draft.isValid) {
            _uiState.update { it.copy(showInvalid = true) }
            return
        }
        _uiState.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val post = photo
            if (post != null) {
                savePhoto(post, state.draft)
                return@launch
            }
            val saved = if (scanPages != null) {
                repository.addManual(state.draft, scannedLanguage(state.draft))
            } else if (recipeId == null) {
                repository.addManual(state.draft)
            } else {
                repository.saveEdit(recipeId, state.draft)
            }
            _uiState.update {
                when {
                    saved == null -> it.copy(saving = false, saveFailed = true)
                    saved.id == 0L -> it.copy(saving = false, libraryFull = true)
                    else -> it.copy(saving = false, savedId = saved.id)
                }
            }
        }
    }

    /** The checked recipe, under the post's link, as the user's version (#198). */
    private suspend fun savePhoto(post: PhotoPost, draft: RecipeDraft) {
        val content = draft.applyTo(
            Recipe(
                name = "", image = null, ingredients = emptyList(), instructions = emptyList(),
                prepTime = null, cookTime = null, totalTime = null, yield = null,
                sourceUrl = post.url,
                sourceType = SourceType.REDDIT
            )
        )
        // Reddit declares no language: the photo's words (#208), then the checked recipe's,
        // decide, else English (#14's rule).
        val recipe = content.copy(
            language = LanguageWords.resolve(photoLanguage, null) {
                LanguageWords.detectionText(content.name, content.ingredients)
            }
        )
        val result = repository.saveClip(recipe)
        _uiState.update {
            when {
                result !is ParseResult.Success -> it.copy(saving = false, saveFailed = true)
                !result.kept -> it.copy(saving = false, libraryFull = true)
                else -> it.copy(saving = false, savedId = result.recipe.id)
            }
        }
    }

    /** A scan's language (#226): its words (#208), then the checked recipe's, else English. */
    private fun scannedLanguage(draft: RecipeDraft): String? {
        val content = draft.applyTo(
            Recipe(
                name = "", image = null, ingredients = emptyList(), instructions = emptyList(),
                prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = ""
            )
        )
        return LanguageWords.resolve(photoLanguage, null) {
            LanguageWords.detectionText(content.name, content.ingredients)
        }
    }

    /** Unlock from the full-library prompt (#107), then save the recipe as typed. */
    fun onUnlock() {
        _uiState.update { it.copy(libraryFull = false) }
        viewModelScope.launch {
            val outcome = entitlements.purchase()
            if (outcome == PurchaseOutcome.UNLOCKED) onSave()
            else if (outcome.needsNotice) _uiState.update { it.copy(unlockNotice = outcome) }
        }
    }

    fun onLibraryFullDismiss() = _uiState.update { it.copy(libraryFull = false) }

    companion object {
        const val RECIPE_ID_ARG = "recipeId"

        /** "Read the photo" (#198): the post's link, title and pictures (one per line). */
        const val PHOTO_URL_ARG = "url"
        const val PHOTO_TITLE_ARG = "title"
        const val PHOTO_IMAGES_ARG = "images"

        /** "Scan a recipe" (#226): the pages, local URIs one per line, in order. */
        const val SCAN_PAGES_ARG = "pages"
    }
}

/**
 * Where line [index] of a box's [old] lines is in its [new] lines, after one edit (#286): the
 * lines before the first that changed, and after the last, are the same lines (moved by as many
 * as were added or taken away). A line inside the change keeps its place in it while the change
 * still has a line there (typed over, or split by a new line), and is gone when it hasn't (the
 * line deleted): null.
 */
internal fun followLine(old: List<String>, new: List<String>, index: Int): Int? {
    var start = 0
    while (start < old.size && start < new.size && old[start] == new[start]) start++
    var endOld = old.size
    var endNew = new.size
    while (endOld > start && endNew > start && old[endOld - 1] == new[endNew - 1]) {
        endOld--
        endNew--
    }
    return when {
        index < start -> index
        index >= endOld -> index + new.size - old.size
        index < endNew -> index
        else -> null
    }
}
