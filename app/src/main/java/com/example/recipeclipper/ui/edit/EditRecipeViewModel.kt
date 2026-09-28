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
import com.example.recipeclipper.data.remote.PhotoTextSorter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    /** "Read the photo" (#198): the Reddit post whose photos fill this editor; null otherwise. */
    val photo: PhotoPost? = null,
    /** The photos are being fetched and read; the fields wait. */
    val reading: Boolean = false,
    val photoOutcome: PhotoOutcome? = null,
    /** Lines the recogniser was unsure of, as they were put in the boxes: "check this". */
    val uncertain: List<String> = emptyList()
)

/**
 * Edits a recipe's content, or types a new one in (#29). Opened with a recipe id from the
 * recipe screen's overflow menu, or with none from Home's "New recipe".
 *
 * With a post ([PHOTO_URL_ARG], #198) it is the review of a Reddit photo read on the device:
 * the post's title and first photo, and the lines read, sorted by the Reddit splitter into
 * ingredients and steps, or, when nothing sorts, all in the ingredients box to finish by hand.
 * Nothing is saved until the cook taps Save; then it is kept under the post's link as the
 * user's version (CLIPPED, like #37's clips), so a re-share never replaces it.
 */
@HiltViewModel
class EditRecipeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RecipeRepository,
    private val entitlements: Entitlements = Entitlements.Unavailable,
    private val photoReader: PhotoTextReader = PhotoTextReader.Unavailable
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

    private val _uiState = MutableStateFlow(EditRecipeUiState(isNew = recipeId == null, photo = photo))
    val uiState: StateFlow<EditRecipeUiState> = _uiState.asStateFlow()

    private var readJob: Job? = null

    init {
        if (recipeId != null) {
            viewModelScope.launch {
                val recipe = repository.open(recipeId)
                _uiState.update {
                    if (recipe == null) it.copy(loading = false, missing = true)
                    else it.copy(loading = false, draft = RecipeDraft.of(recipe))
                }
            }
        } else if (photo != null) {
            readPhoto(photo)
        }
    }

    /** "Try again" after the photos couldn't be fetched or read, or the reader wasn't ready. */
    fun onReadAgain() {
        val post = photo ?: return
        if (_uiState.value.reading) return
        readPhoto(post)
    }

    private fun readPhoto(post: PhotoPost) {
        readJob?.cancel()
        _uiState.update {
            it.copy(
                reading = true, photoOutcome = null, uncertain = emptyList(),
                draft = RecipeDraft(name = post.title, image = post.imageUrls.firstOrNull().orEmpty())
            )
        }
        readJob = viewModelScope.launch {
            val result = photoReader.read(post.imageUrls)
            _uiState.update { state ->
                when (result) {
                    PhotoTextResult.Failed -> state.copy(reading = false, photoOutcome = PhotoOutcome.FAILED)
                    PhotoTextResult.NotReady -> state.copy(reading = false, photoOutcome = PhotoOutcome.NOT_READY)
                    is PhotoTextResult.Read -> {
                        val reading = PhotoTextSorter.sort(result.lines)
                        state.copy(
                            reading = false,
                            photoOutcome = if (reading.sorted) PhotoOutcome.READ else PhotoOutcome.NOT_SORTED,
                            uncertain = reading.uncertain,
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
    }

    fun onDraftChange(draft: RecipeDraft) =
        _uiState.update { it.copy(draft = draft, saveFailed = false, unlockNotice = null) }

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
            val saved = if (recipeId == null) {
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
        // Reddit declares no language: the words decide, else English (#14's rule).
        val recipe = content.copy(
            language = LanguageWords.resolve(null, null) {
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
    }
}
