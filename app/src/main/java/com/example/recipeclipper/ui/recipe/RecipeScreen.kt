package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.PhotoPost
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.TooltipScreen
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.ui.common.noticeMessage
import com.example.recipeclipper.ui.groceries.AddToGroceriesSheet
import com.example.recipeclipper.ui.groceries.AddToGroceriesViewModel
import com.example.recipeclipper.ui.plan.AddToPlanBottomSheet
import com.example.recipeclipper.ui.plan.AddToPlanViewModel
import com.example.recipeclipper.ui.savetolist.SaveToListBottomSheet
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import com.example.recipeclipper.ui.sharefile.SendFileEffect
import com.example.recipeclipper.ui.sharefile.SendFileViewModel
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import com.example.recipeclipper.ui.tour.TooltipHost

@Composable
fun RecipeScreen(
    onBack: () -> Unit,
    onClip: (url: String) -> Unit = {},
    // Reddit wouldn't let the app read the shared post (#213): "Clip it yourself" on it, in
    // this screen's place.
    onClipBlocked: (url: String) -> Unit = {},
    onHumanCheck: (url: String) -> Unit = {},
    onEdit: (recipeId: Long) -> Unit = {},
    // "Read the photo" (#198): a Reddit post with no recipe text, read on the device.
    onReadPhoto: (PhotoPost) -> Unit = {},
    photoTextEnabled: Boolean = LocalFlagValues.current.isOn(Flag.PHOTO_TEXT),
    viewModel: RecipeViewModel = hiltViewModel(),
    saveViewModel: SaveToListViewModel = hiltViewModel(),
    mealPlanEnabled: Boolean = LocalFlagValues.current.isOn(Flag.MEAL_PLAN),
    amountsInStepsEnabled: Boolean = LocalFlagValues.current.isOn(Flag.AMOUNTS_IN_STEPS),
    // Only resolved behind the tab flag (#49), so screen tests without Hilt need not pass one.
    planViewModel: AddToPlanViewModel? = if (mealPlanEnabled) hiltViewModel() else null,
    groceriesViewModel: AddToGroceriesViewModel? = if (mealPlanEnabled) hiltViewModel() else null,
    cookedPhotosEnabled: Boolean = LocalFlagValues.current.isOn(Flag.COOKED_PHOTOS),
    // "I made this" (#116), only behind its flag, like the plan's sheets above.
    photosViewModel: CookedPhotosViewModel? = if (cookedPhotosEnabled) hiltViewModel() else null,
    // "Send as file" (#149): the navigation passes one; null (screen tests) leaves it out.
    sendFileViewModel: SendFileViewModel? = null,
    // Using up the pantry when cook mode is finished or "I made this" adds a photo (#147): the
    // pantry is behind the tab flag.
    useUpViewModel: PantryUseUpViewModel? = if (mealPlanEnabled) hiltViewModel() else null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val saveState by saveViewModel.uiState.collectAsStateWithLifecycle()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var planSheetOpen by rememberSaveable { mutableStateOf(false) }
    var groceriesSheetOpen by rememberSaveable { mutableStateOf(false) }
    val actions = rememberRecipeActions(
        viewModel, onBack, onEdit, planViewModel, groceriesViewModel, sendFileViewModel,
        onOpenSaveSheet = { sheetOpen = true },
        onOpenPlanSheet = { planSheetOpen = true },
        onOpenGroceriesSheet = { groceriesSheetOpen = true }
    )

    // The recipe is gone the moment the delete lands; leave the screen rather than show it
    // in some half-deleted state.
    LaunchedEffect(state.deleted) {
        if (state.deleted) actions.onBack()
    }
    LaunchedEffect(state.clipBlockedPost) {
        state.clipBlockedPost?.let(onClipBlocked)
    }
    LaunchedEffect(state.humanCheckPage) {
        state.humanCheckPage?.let(onHumanCheck)
    }

    val content = state.content

    // The id isn't known until the parse finishes on the import route, so the list ViewModel
    // is told which recipe it is looking at here rather than from a navigation argument.
    val recipeId = (content as? RecipeContent.Success)?.recipe?.id?.takeIf { !state.notKept }
    LaunchedEffect(recipeId) {
        if (recipeId != null) saveViewModel.setRecipe(recipeId)
        if (recipeId != null) photosViewModel?.setRecipe(recipeId)
    }
    // While this recipe is on screen its timers beep here instead of posting a notification.
    MarkRecipeVisible(recipeId)

    val cooking = content is RecipeContent.Success && state.cook.active

    // "Update from source" failed: the recipe on screen is unchanged; say why, once.
    val snackbarHostState = remember { SnackbarHostState() }
    val updateError = state.updateError
    val updateErrorMessage = updateError?.let { stringResource(R.string.update_from_source_failed, it.toMessage()) }
    LaunchedEffect(updateError) {
        if (updateErrorMessage != null) {
            snackbarHostState.showSnackbar(updateErrorMessage)
            viewModel.onUpdateErrorShown()
        }
    }

    // "Send as file" (#149): the share sheet on the written file, or a line saying it failed.
    val sendFailedMessage = stringResource(R.string.send_file_failed)
    if (sendFileViewModel != null) {
        SendFileEffect(sendFileViewModel) { snackbarHostState.showSnackbar(sendFailedMessage) }
    }

    // Cook mode just finished with ingredients ticked (#147): they go to the pantry's use-up sheet.
    val finished = state.cookFinished
    LaunchedEffect(finished) {
        if (finished != null) {
            (content as? RecipeContent.Success)?.let { loaded ->
                useUpViewModel?.onCookFinished(loaded.recipe.id, finished.language, finished.lines)
            }
            viewModel.onCookFinishedHandled()
        }
    }

    // A full free library (#107): shown, not kept. Stays up until dismissed or unlocked, and
    // comes back after a pending or failed purchase has been explained.
    val notKeptMessage = stringResource(R.string.recipe_not_kept, LibraryLimit.FREE_RECIPES)
    val unlockLabel = stringResource(R.string.unlock)
    LaunchedEffect(state.notKept, state.unlockNotice) {
        if (state.notKept && state.unlockNotice == null) {
            val result = snackbarHostState.showSnackbar(
                notKeptMessage, actionLabel = unlockLabel, withDismissAction = true,
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.onUnlock()
        }
    }
    val unlockNotice = state.unlockNotice
    val unlockNoticeMessage = unlockNotice?.let { stringResource(it.noticeMessage()) }
    LaunchedEffect(unlockNotice) {
        if (unlockNoticeMessage != null) {
            snackbarHostState.showSnackbar(unlockNoticeMessage)
            viewModel.onUnlockNoticeShown()
        }
    }

    // Cook mode follows the system theme like every other screen unless the user has asked
    // for it to stay dark.
    RecipeClipperTheme(forceDark = cooking && state.darkWhileCooking) {
        // A photo added with "I made this" (#116), or "Mark as cooked" (#173), once closed: the recipe was cooked, so the
        // pantry's use-up sheet (#147) gets the lines as shown now, ticked or all. Called inside the theme (#186):
        // it shows the full-screen photo, a dialog, which outside it was Material purple.
        val photos = cookedPhotosUi(photosViewModel, content, snackbarHostState, onMadeThis = {
            val current = viewModel.uiState.value
            (current.content as? RecipeContent.Success)?.let { loaded ->
                useUpViewModel?.onMadeThis(
                    loaded.recipe.id, loaded.words?.language, loaded.ingredients, current.checkedIngredients
                )
            }
        })
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            TimerAlerts(state.cook.timers, actions.onTimerAlerted)

            // Edge-to-edge: the surface's colour fills behind the bars (so forced-dark cook
            // mode is dark edge to edge); the content stays clear of them, the display
            // cutout and the keyboard.
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                when (content) {
                    is RecipeContent.Success -> {
                        // Amounts in steps (#101) show only behind their flag.
                        val shown = if (amountsInStepsEnabled) content else content.copy(stepAmounts = null)
                        // The tooltips (#190): cook mode is its own screen; neither shows one
                        // over a snackbar.
                        val snackbar = snackbarHostState.currentSnackbarData != null
                        if (cooking) {
                            TooltipHost(TooltipScreen.COOK, blocked = snackbar) { CookView(shown, state, actions) }
                        } else {
                            TooltipHost(TooltipScreen.RECIPE, blocked = snackbar) {
                                ReadingView(shown, state, actions, saveState.isSaved, photos?.count ?: 0, photos?.section)
                            }
                        }
                    }
                    is RecipeContent.Loading -> StatusView(actions.onBack) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    is RecipeContent.Error -> RecipeErrorView(
                        content.error, state, actions, photoTextEnabled, onClip, onReadPhoto
                    )
                }
            }

            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
                SnackbarHost(snackbarHostState)
            }

            // Only reachable once the recipe has an id: there is nothing to put in a list
            // while it is still loading or has failed.
            if (sheetOpen && recipeId != null) {
                SaveToListBottomSheet(saveViewModel, onDismiss = { sheetOpen = false })
            }
            if (planSheetOpen && recipeId != null && planViewModel != null) {
                AddToPlanBottomSheet(planViewModel, onDismiss = { planSheetOpen = false })
            }
            if (groceriesSheetOpen && recipeId != null && groceriesViewModel != null) {
                AddToGroceriesSheet(groceriesViewModel, onDismiss = { groceriesSheetOpen = false })
            }
            if (useUpViewModel != null) PantryUseUpUi(useUpViewModel, snackbarHostState)
        }
    }
}
