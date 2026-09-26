package com.example.recipeclipper.ui.recipe

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.Tip
import com.example.recipeclipper.ui.common.noticeMessage
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.ui.groceries.AddToGroceriesSheet
import com.example.recipeclipper.ui.groceries.AddToGroceriesViewModel
import com.example.recipeclipper.ui.plan.AddToPlanBottomSheet
import com.example.recipeclipper.ui.plan.AddToPlanViewModel
import com.example.recipeclipper.ui.savetolist.SaveToListBottomSheet
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import com.example.recipeclipper.ui.tour.TipCallout

/** Every event the recipe screen can raise, bundled so views take one parameter, not eighteen. */
internal class RecipeActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onReportSite: () -> Unit,
    val onIngredientChecked: (Int, Boolean) -> Unit,
    val onNotesChange: (String) -> Unit,
    val onServingsChange: (Int) -> Unit,
    val onUnitSystemChange: (UnitSystem) -> Unit,
    val onCookStart: () -> Unit,
    val onCookExit: () -> Unit,
    val onStepSelected: (Int) -> Unit,
    val onStepDone: () -> Unit,
    val onIngredientsToggle: () -> Unit,
    val onTimerStart: (Int) -> Unit,
    val onTimerToggle: (Int) -> Unit,
    val onTimerReset: (Int) -> Unit,
    val onTimerAlerted: (Int) -> Unit,
    /** Chef mode (#100): a step with a short version shows as written, or short again. */
    val onStepAsWrittenToggle: (Int) -> Unit = {},
    val onShare: () -> Unit,
    val onOpenOriginal: (url: String) -> Unit,
    val onSaveToList: () -> Unit,
    val onDelete: () -> Unit,
    val onEdit: () -> Unit = {},
    val onUpdateFromSource: () -> Unit = {},
    /** "Add to plan" (#49); null hides it, as while the tab flag is off. */
    val onAddToPlan: (() -> Unit)? = null,
    /** "Add to groceries" (#50); null hides it, as while the tab flag is off. */
    val onAddToGroceries: (() -> Unit)? = null
)

@Composable
fun RecipeScreen(
    onBack: () -> Unit,
    onClip: (url: String) -> Unit = {},
    onEdit: (recipeId: Long) -> Unit = {},
    viewModel: RecipeViewModel = hiltViewModel(),
    saveViewModel: SaveToListViewModel = hiltViewModel(),
    mealPlanEnabled: Boolean = LocalFlagValues.current.isOn(Flag.MEAL_PLAN),
    amountsInStepsEnabled: Boolean = LocalFlagValues.current.isOn(Flag.AMOUNTS_IN_STEPS),
    // Only resolved behind the tab flag (#49), so screen tests without Hilt need not pass one.
    planViewModel: AddToPlanViewModel? = if (mealPlanEnabled) hiltViewModel() else null,
    groceriesViewModel: AddToGroceriesViewModel? = if (mealPlanEnabled) hiltViewModel() else null,
    cookedPhotosEnabled: Boolean = LocalFlagValues.current.isOn(Flag.COOKED_PHOTOS),
    // "I made this" (#116), only behind its flag, like the plan's sheets above.
    photosViewModel: CookedPhotosViewModel? = if (cookedPhotosEnabled) hiltViewModel() else null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val saveState by saveViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    // Android's UriHandler fires ACTION_VIEW, so the browser opens the draft issue. A local,
    // not a direct Intent, so a UI test can supply its own and see the link without leaving.
    val uriHandler = LocalUriHandler.current
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var planSheetOpen by rememberSaveable { mutableStateOf(false) }
    var groceriesSheetOpen by rememberSaveable { mutableStateOf(false) }
    // The first timer started asks for permission to post its "time's up" notification.
    val askForNotifications = rememberNotificationPrompt()
    val actions = remember(viewModel, onBack, onEdit, context, uriHandler, askForNotifications, planViewModel, groceriesViewModel) {
        RecipeActions(
            onBack = onBack,
            onRetry = viewModel::onRetry,
            onReportSite = {
                viewModel.uiState.value.reportSiteUrl?.let { url ->
                    // No browser at all: nothing to open, and nothing lost by staying put.
                    try {
                        uriHandler.openUri(url)
                    } catch (e: ActivityNotFoundException) {
                        // Nothing to do.
                    } catch (e: IllegalArgumentException) {
                        // Newer Compose wraps ActivityNotFoundException in this.
                    }
                }
            },
            onIngredientChecked = viewModel::onIngredientChecked,
            onNotesChange = viewModel::onNotesChange,
            onServingsChange = viewModel::onServingsChange,
            onUnitSystemChange = viewModel::onUnitSystemChange,
            onCookStart = viewModel::onCookStart,
            onCookExit = viewModel::onCookExit,
            onStepSelected = viewModel::onStepSelected,
            onStepDone = viewModel::onStepDone,
            onIngredientsToggle = viewModel::onIngredientsToggle,
            onTimerStart = { step ->
                askForNotifications()
                viewModel.onTimerStart(step)
            },
            onTimerToggle = viewModel::onTimerToggle,
            onTimerReset = viewModel::onTimerReset,
            onTimerAlerted = viewModel::onTimerAlerted,
            onStepAsWrittenToggle = viewModel::onStepAsWrittenToggle,
            onShare = {
                viewModel.shareText(shareLabels(resources))?.let { text ->
                    val title = (viewModel.uiState.value.content as? RecipeContent.Success)
                        ?.recipe?.name.orEmpty()
                    ShareCompat.IntentBuilder(context)
                        .setType("text/plain")
                        .setSubject(title)
                        .setText(text)
                        .setChooserTitle(title)
                        .startChooser()
                }
            },
            // A platform effect, so it lives here rather than in the ViewModel. With no app
            // to open a web link (rare, but possible on a locked-down device) the tap does
            // nothing rather than crash.
            onOpenOriginal = { url ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                } catch (_: ActivityNotFoundException) {
                }
            },
            onSaveToList = { sheetOpen = true },
            onDelete = viewModel::onDelete,
            onEdit = {
                (viewModel.uiState.value.content as? RecipeContent.Success)?.recipe?.id?.let(onEdit)
            },
            onUpdateFromSource = viewModel::onUpdateFromSource,
            onAddToPlan = if (planViewModel == null) null else {
                {
                    (viewModel.uiState.value.content as? RecipeContent.Success)?.let { loaded ->
                        planViewModel.setRecipe(loaded.recipe.id, loaded.servings?.base)
                        planSheetOpen = true
                    }
                }
            },
            // The lines exactly as the reading view shows them: scaled and converted.
            onAddToGroceries = if (groceriesViewModel == null) null else {
                {
                    (viewModel.uiState.value.content as? RecipeContent.Success)?.let { loaded ->
                        groceriesViewModel.setRecipe(
                            loaded.recipe.id, loaded.recipe.name, loaded.words?.language, loaded.ingredients
                        )
                        groceriesSheetOpen = true
                    }
                }
            }
        )
    }

    // The recipe is gone the moment the delete lands; leave the screen rather than show it
    // in some half-deleted state.
    LaunchedEffect(state.deleted) {
        if (state.deleted) actions.onBack()
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

    val photos = cookedPhotosUi(photosViewModel, content, snackbarHostState)

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
                        if (cooking) CookView(shown, state, actions)
                        else ReadingView(shown, state, actions, saveState.isSaved, photos?.count ?: 0, photos?.section)
                    }
                    is RecipeContent.Loading -> StatusView(actions.onBack) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    is RecipeContent.Error -> StatusView(actions.onBack) {
                        Text(
                            content.error.toMessage(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        // Every error offers "Try again", no-recipe included: a café or hotel
                        // captive portal serves its login page, which parses as a page with no
                        // recipe, and the same link works once you're through it.
                        Spacer(Modifier.height(16.dp))
                        Column {
                            val clipUrl = state.clipUrl
                            if (clipUrl == null) {
                                Button(onClick = actions.onRetry, shape = RoundedCornerShape(12.dp)) {
                                    Text(stringResource(R.string.action_try_again))
                                }
                            } else {
                                // A page with no recipe data (#37): Try again stays first,
                                // outlined; clipping it by hand is the one filled button, and
                                // reporting the site (#30) is the quiet option under it.
                                OutlinedButton(onClick = actions.onRetry, shape = RoundedCornerShape(12.dp)) {
                                    Text(stringResource(R.string.action_try_again))
                                }
                                Spacer(Modifier.height(20.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.clip_offer),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { onClip(clipUrl) }, shape = RoundedCornerShape(12.dp)) {
                                    Text(stringResource(R.string.action_clip_it_yourself))
                                }
                            }
                            // Only for a page with no recipe (the ViewModel decides): the one
                            // error that means "unsupported" rather than "try again".
                            if (state.reportSiteUrl != null) {
                                TextButton(onClick = actions.onReportSite) {
                                    Text(
                                        stringResource(R.string.action_report_site),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
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
        }
    }
}

/** Resolves a [ParseError] cause to the copy shown for it. The data layer only hands over
 *  the cause; choosing the sentence is the UI's job. */
@Composable
private fun ParseError.toMessage(): String = when (this) {
    ParseError.NoRecipeFound -> stringResource(R.string.error_no_recipe_found)
    is ParseError.Blocked -> stringResource(R.string.error_blocked, httpStatus)
    ParseError.Offline -> stringResource(R.string.error_offline)
    is ParseError.FetchFailed -> stringResource(
        R.string.error_fetch_failed,
        detail ?: stringResource(R.string.error_fetch_failed_unknown_detail)
    )
    ParseError.SaveFailed -> stringResource(R.string.error_save_failed)
    ParseError.NotSaved -> stringResource(R.string.error_not_saved)
    ParseError.NothingToShow -> stringResource(R.string.error_nothing_to_show)
}

/** Loading and errors: a back button and one message, nothing to read yet. */
@Composable
private fun StatusView(onBack: () -> Unit, body: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 4.dp)) {
        BackButton(onBack)
        Spacer(Modifier.height(24.dp))
        body()
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack) {
        Text(
            stringResource(R.string.action_back),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The reading view opens on the recipe: photo, title, times, ingredients. Servings and units
 * sit in one always-visible row just above the ingredients, and cooking is one bottom button.
 */
@Composable
private fun ReadingView(
    content: RecipeContent.Success,
    state: RecipeUiState,
    actions: RecipeActions,
    isSaved: Boolean,
    photoCount: Int = 0,
    cookedPhotos: (@Composable () -> Unit)? = null
) {
    val recipe = content.recipe
    // Only whether the keyboard is up for the note, so the cooking bar steps aside for it.
    // Focus doesn't survive rotation anyway, so plain remember is right here.
    var editingNotes by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 112.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    BackButton(actions.onBack)
                    Spacer(Modifier.weight(1f))
                    // Filled once the recipe is in at least one list — "saved" is derived from
                    // membership, so the icon is reading the same thing the database is.
                    // Lists and the overflow's actions need a saved recipe: not one shown but not
                    // kept (#107).
                    if (!state.notKept) IconButton(onClick = actions.onSaveToList) {
                        Icon(
                            painter = painterResource(
                                if (isSaved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border
                            ),
                            contentDescription = stringResource(
                                if (isSaved) R.string.cd_in_a_list else R.string.cd_save_to_list
                            ),
                            tint = if (isSaved) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            }
                        )
                    }
                    IconButton(onClick = actions.onShare) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.cd_share_recipe))
                    }
                    if (state.updatingFromSource) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(12.dp).size(20.dp)
                        )
                    }
                    if (!state.notKept) RecipeOverflowMenu(
                        recipeName = recipe.name,
                        canUpdateFromSource = recipe.canUpdateFromSource && !state.updatingFromSource,
                        clipped = recipe.origin == ContentOrigin.CLIPPED,
                        onEdit = actions.onEdit,
                        onUpdateFromSource = actions.onUpdateFromSource,
                        onDelete = actions.onDelete,
                        photoCount = photoCount,
                        onAddToPlan = actions.onAddToPlan,
                        onAddToGroceries = actions.onAddToGroceries
                    )
                }
            }

            recipe.image?.let { image ->
                item {
                    AsyncImage(
                        model = image,
                        contentDescription = recipe.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            item {
                Text(recipe.name, style = MaterialTheme.typography.headlineSmall)
                val domain = content.sourceDomain
                if (domain != null) {
                    SourceCredit(
                        domain,
                        clipped = recipe.origin == ContentOrigin.CLIPPED,
                        onOpen = { actions.onOpenOriginal(recipe.sourceUrl) }
                    )
                    // Picked by the on-device model (#103): every line is on the page, but
                    // which lines were picked is the model's call, so say so, quietly.
                    if (recipe.origin == ContentOrigin.EXTRACTED) {
                        Text(
                            stringResource(R.string.extracted_from_page),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                } else {
                    Spacer(Modifier.height(14.dp))
                }
                Times(recipe.prepTime, recipe.cookTime, recipe.totalTime)
                Spacer(Modifier.height(16.dp))
                ServesUnitsRow(
                    servings = content.servings,
                    yieldText = recipe.yield,
                    words = content.words,
                    unitSystem = state.unitSystem,
                    onServingsChange = actions.onServingsChange,
                    onUnitSystemChange = actions.onUnitSystemChange
                )
                // The first recipe opened (#151): the row above, and the bookmark.
                TipCallout(Tip.RECIPE, Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(20.dp))
                SectionHeading(stringResource(R.string.heading_ingredients))
                Spacer(Modifier.height(6.dp))
            }

            itemsIndexed(content.ingredients) { index, ingredient ->
                IngredientRow(
                    text = ingredient,
                    checked = index in state.checkedIngredients,
                    onCheckedChange = { actions.onIngredientChecked(index, it) }
                )
            }

            item {
                Spacer(Modifier.height(24.dp))
                SectionHeading(stringResource(R.string.heading_instructions))
                Spacer(Modifier.height(6.dp))
            }

            itemsIndexed(content.instructions) { index, _ ->
                // Chef mode (#100): a step with a short version shows it; a tap shows it as written.
                val step = content.shownStep(index, state.asWrittenSteps)
                val toggle = if (content.hasShortStep(index)) {
                    val label = stringResource(
                        if (index in state.asWrittenSteps) R.string.step_show_short else R.string.step_show_as_written
                    )
                    Modifier.clickable(onClickLabel = label) { actions.onStepAsWrittenToggle(index) }
                } else {
                    Modifier
                }
                Row(Modifier.fillMaxWidth().then(toggle).padding(vertical = 8.dp)) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.width(32.dp)
                    )
                    Text(
                        stepText(step, content.shownStepAmounts(index, state.asWrittenSteps), MaterialTheme.colorScheme.tertiary),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // After the steps: the reading view still opens on the recipe, and a note like
            // "needs 10 more minutes" is read once the method is.
            // A recipe that wasn't kept (#107) has nowhere to keep a note.
            if (!state.notKept) item {
                Spacer(Modifier.height(24.dp))
                NotesSection(
                    notes = state.notes,
                    onNotesChange = actions.onNotesChange,
                    onFocusChange = { editingNotes = it }
                )
            }
            // "Your cooks" (#116): last, so the reading view still opens on the recipe.
            if (!state.notKept && cookedPhotos != null) item {
                Spacer(Modifier.height(28.dp))
                cookedPhotos()
            }
        }

        if (content.instructions.isNotEmpty() && !editingNotes) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Hairline()
                Button(
                    onClick = actions.onCookStart,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .height(52.dp)
                ) {
                    Text(stringResource(R.string.action_start_cooking), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * Overflow menu: "Add to plan" (#49) and "Add to groceries" (#50), while the tab flag is on, Edit, "Update from source" for the user's version of a linked recipe (#29),
 * behind a warning that the edits will be lost, and Delete, behind a confirm dialog naming
 * the recipe.
 */
@Composable
private fun RecipeOverflowMenu(
    recipeName: String,
    canUpdateFromSource: Boolean,
    clipped: Boolean,
    onEdit: () -> Unit,
    onUpdateFromSource: () -> Unit,
    onDelete: () -> Unit,
    photoCount: Int = 0,
    onAddToPlan: (() -> Unit)? = null,
    onAddToGroceries: (() -> Unit)? = null
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var confirmingUpdate by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (onAddToPlan != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_add_to_plan)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_tab_week), contentDescription = null) },
                onClick = {
                    expanded = false
                    onAddToPlan()
                }
            )
        }
        if (onAddToGroceries != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_add_to_groceries)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_tab_groceries), contentDescription = null) },
                onClick = {
                    expanded = false
                    onAddToGroceries()
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_edit)) },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = {
                expanded = false
                onEdit()
            }
        )
        if (canUpdateFromSource) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_update_from_source)) },
                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                onClick = {
                    expanded = false
                    confirmingUpdate = true
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = {
                expanded = false
                confirming = true
            }
        )
    }
    if (confirmingUpdate) {
        AlertDialog(
            onDismissRequest = { confirmingUpdate = false },
            // A clip (#37) says what it loses in its own words: the parts picked from the page.
            title = {
                Text(stringResource(if (clipped) R.string.update_from_source_clip_title else R.string.update_from_source_title))
            },
            text = {
                Text(stringResource(if (clipped) R.string.update_from_source_clip_body else R.string.update_from_source_body))
            },
            confirmButton = {
                TextButton(onClick = { confirmingUpdate = false; onUpdateFromSource() }) {
                    Text(stringResource(R.string.action_update))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUpdate = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.delete_recipe_title, recipeName)) },
            // The user's photos go with the recipe (#116), and the dialog says so.
            text = {
                Text(
                    when (photoCount) {
                        0 -> stringResource(R.string.delete_recipe_body)
                        1 -> stringResource(R.string.delete_recipe_body_photo)
                        else -> stringResource(R.string.delete_recipe_body_photos, photoCount)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/**
 * Credits the site under the title: its domain in muted text, then "Open original" as a quiet
 * paprika link to the page in the browser. Reading view only; cook mode has no room for it.
 */
@Composable
private fun SourceCredit(domain: String, clipped: Boolean, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A clip (#37) says whose selection it is, so a difference from the page, and a
        // re-share that doesn't refresh it, both make sense.
        Text(
            if (clipped) stringResource(R.string.clipped_by_you_on, domain) else domain,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            stringResource(R.string.action_open_original),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clickable(
                    onClickLabel = stringResource(R.string.cd_open_original, domain),
                    role = Role.Button,
                    onClick = onOpen
                )
        )
    }
}

/** Plain labeled numbers. Deliberately not chips: chips imply tappable, these aren't. */
@Composable
private fun Times(prep: String?, cook: String?, total: String?) {
    val entries = listOfNotNull(
        prep?.let { stringResource(R.string.label_prep) to it },
        cook?.let { stringResource(R.string.label_cook) to it },
        total?.let { stringResource(R.string.label_total) to it }
    )
    if (entries.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        entries.forEach { (label, value) ->
            Column {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

