package com.example.recipeclipper.ui.tour

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.FirstRunTour
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** The welcome's cards (#151), in order. [WEEKLY] only with the `mealPlan` flag on. */
enum class WelcomeCard { APP, CLIP, DAILY, WEEKLY }

/** Where the welcome goes when it is done. */
sealed interface WelcomeExit {
    data object Done : WelcomeExit
    data class OpenRecipe(val id: Long) : WelcomeExit
}

data class WelcomeUiState(
    val cards: List<WelcomeCard> = listOf(WelcomeCard.APP, WelcomeCard.CLIP, WelcomeCard.DAILY),
    val page: Int = 0,
    /** The daily card mentions Chef mode only with its flag on. */
    val chefMode: Boolean = false,
    /** "Try it" is opening the sample. */
    val opening: Boolean = false,
    /** Set once the welcome is done; the screen navigates, then calls `onExitHandled`. */
    val exit: WelcomeExit? = null
) {
    val card: WelcomeCard get() = cards[page]
    val isLast: Boolean get() = page == cards.lastIndex
}

/**
 * The first-run welcome (#151): three or four cards, skippable, the last offering the sample
 * recipe or Start. Every way out (Skip, Start, Try it, Back from the first card) marks it seen.
 * Opened from Settings' "Show the tour again" ([AGAIN_ARG]), it shows every tip once more too.
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val tour: FirstRunTour,
    flags: FeatureFlags,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val state = MutableStateFlow(
        WelcomeUiState(
            cards = listOfNotNull(
                WelcomeCard.APP, WelcomeCard.CLIP, WelcomeCard.DAILY,
                WelcomeCard.WEEKLY.takeIf { flags.isOn(Flag.MEAL_PLAN) }
            ),
            chefMode = flags.isOn(Flag.CHEF_MODE)
        )
    )
    val uiState: StateFlow<WelcomeUiState> = state.asStateFlow()

    init {
        if (savedStateHandle.get<Boolean>(AGAIN_ARG) == true) tour.replay()
        // The first welcome ever adds the sample, so it is waiting in Recipes whichever way out.
        viewModelScope.launch { tour.addSampleOnce(language()) }
    }

    fun onNext() = state.update { it.copy(page = (it.page + 1).coerceAtMost(it.cards.lastIndex)) }

    fun onPrevious() = state.update { it.copy(page = (it.page - 1).coerceAtLeast(0)) }

    /** Skip, Start, or Back from the first card. */
    fun onDone() {
        tour.finishWelcome()
        state.update { it.copy(exit = WelcomeExit.Done) }
    }

    fun onTrySample() {
        if (state.value.opening || state.value.exit != null) return
        state.update { it.copy(opening = true) }
        viewModelScope.launch {
            val id = tour.sampleToOpen(language())
            tour.finishWelcome()
            state.update { it.copy(opening = false, exit = id?.let(WelcomeExit::OpenRecipe) ?: WelcomeExit.Done) }
        }
    }

    fun onExitHandled() = state.update { it.copy(exit = null) }

    /** The UI's language picks the sample's (English if it isn't written in it). */
    private fun language(): String = Locale.getDefault().language

    companion object {
        const val AGAIN_ARG = "again"
    }
}
