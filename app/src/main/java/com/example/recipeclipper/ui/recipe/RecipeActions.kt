package com.example.recipeclipper.ui.recipe

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.app.ShareCompat
import androidx.core.net.toUri
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.ui.groceries.AddToGroceriesViewModel
import com.example.recipeclipper.ui.plan.AddToPlanViewModel
import com.example.recipeclipper.ui.sharefile.SendFileViewModel

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
    /** "Add to plan" (#49); null (screen tests) hides it. */
    val onAddToPlan: (() -> Unit)? = null,
    /** "Add to groceries" (#50); null (screen tests) hides it. */
    val onAddToGroceries: (() -> Unit)? = null,
    /** "Send as file" (#149): the recipe as a small file for someone else's app; null hides it. */
    val onSendFile: (() -> Unit)? = null
)

/**
 * The recipe screen's [RecipeActions]: the ViewModel's events, and the platform effects that live
 * in the view layer (opening a link, the share sheet, the notification prompt). The sheets are
 * the screen's to open: [onOpenSaveSheet], [onOpenPlanSheet] and [onOpenGroceriesSheet].
 */
@Composable
internal fun rememberRecipeActions(
    viewModel: RecipeViewModel,
    onBack: () -> Unit,
    onEdit: (recipeId: Long) -> Unit,
    planViewModel: AddToPlanViewModel?,
    groceriesViewModel: AddToGroceriesViewModel?,
    sendFileViewModel: SendFileViewModel?,
    onOpenSaveSheet: () -> Unit,
    onOpenPlanSheet: () -> Unit,
    onOpenGroceriesSheet: () -> Unit
): RecipeActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    // Android's UriHandler fires ACTION_VIEW, so the browser opens the draft issue. A local,
    // not a direct Intent, so a UI test can supply its own and see the link without leaving.
    val uriHandler = LocalUriHandler.current
    // The first timer started asks for permission to post its "time's up" notification.
    val askForNotifications = rememberNotificationPrompt()
    return remember(
        viewModel, onBack, onEdit, context, uriHandler, askForNotifications, planViewModel, groceriesViewModel, sendFileViewModel
    ) {
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
            onSaveToList = onOpenSaveSheet,
            onDelete = viewModel::onDelete,
            onEdit = {
                (viewModel.uiState.value.content as? RecipeContent.Success)?.recipe?.id?.let(onEdit)
            },
            onUpdateFromSource = viewModel::onUpdateFromSource,
            onAddToPlan = if (planViewModel == null) null else {
                {
                    (viewModel.uiState.value.content as? RecipeContent.Success)?.let { loaded ->
                        planViewModel.setRecipe(loaded.recipe.id, loaded.servings?.base)
                        onOpenPlanSheet()
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
                        onOpenGroceriesSheet()
                    }
                }
            },
            onSendFile = if (sendFileViewModel == null) null else {
                {
                    (viewModel.uiState.value.content as? RecipeContent.Success)?.let { loaded ->
                        sendFileViewModel.sendRecipe(loaded.recipe.id, loaded.recipe.name)
                    }
                }
            }
        )
    }
}
