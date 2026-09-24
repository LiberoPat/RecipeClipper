package com.example.recipeclipper.ui.recipe

import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.ui.savetolist.SaveToListBottomSheet
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/** Every event the recipe screen can raise, bundled so views take one parameter, not eighteen. */
internal class RecipeActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onReportSite: () -> Unit,
    val onIngredientChecked: (Int, Boolean) -> Unit,
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
    val onShare: () -> Unit,
    val onOpenOriginal: (url: String) -> Unit,
    val onSaveToList: () -> Unit,
    val onDelete: () -> Unit
)

@Composable
fun RecipeScreen(
    onBack: () -> Unit,
    viewModel: RecipeViewModel = hiltViewModel(),
    saveViewModel: SaveToListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val saveState by saveViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Android's UriHandler fires ACTION_VIEW, so the browser opens the draft issue. A local,
    // not a direct Intent, so a UI test can supply its own and see the link without leaving.
    val uriHandler = LocalUriHandler.current
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val actions = remember(viewModel, onBack, context, uriHandler) {
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
            onServingsChange = viewModel::onServingsChange,
            onUnitSystemChange = viewModel::onUnitSystemChange,
            onCookStart = viewModel::onCookStart,
            onCookExit = viewModel::onCookExit,
            onStepSelected = viewModel::onStepSelected,
            onStepDone = viewModel::onStepDone,
            onIngredientsToggle = viewModel::onIngredientsToggle,
            onTimerStart = viewModel::onTimerStart,
            onTimerToggle = viewModel::onTimerToggle,
            onTimerReset = viewModel::onTimerReset,
            onTimerAlerted = viewModel::onTimerAlerted,
            onShare = {
                viewModel.shareText()?.let { text ->
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
            onDelete = viewModel::onDelete
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
    val recipeId = (content as? RecipeContent.Success)?.recipe?.id
    LaunchedEffect(recipeId) {
        if (recipeId != null) saveViewModel.setRecipe(recipeId)
    }

    val cooking = content is RecipeContent.Success && state.cook.active

    // Cook mode follows the system theme like every other screen unless the user has asked
    // for it to stay dark.
    RecipeClipperTheme(forceDark = cooking && state.darkWhileCooking) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            TimerAlerts(state.cook.timers, actions.onTimerAlerted)

            when (content) {
                is RecipeContent.Success ->
                    if (cooking) CookView(content, state, actions)
                    else ReadingView(content, state, actions, saveState.isSaved)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = actions.onRetry, shape = RoundedCornerShape(12.dp)) {
                            Text(stringResource(R.string.action_try_again))
                        }
                        // Only for a page with no recipe (the ViewModel decides): the one error
                        // that means "unsupported" rather than "try again". Secondary, beside it.
                        if (state.reportSiteUrl != null) {
                            Spacer(Modifier.width(8.dp))
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

            // Only reachable once the recipe has an id: there is nothing to put in a list
            // while it is still loading or has failed.
            if (sheetOpen && recipeId != null) {
                SaveToListBottomSheet(saveViewModel, onDismiss = { sheetOpen = false })
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
    isSaved: Boolean
) {
    val recipe = content.recipe
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
                    IconButton(onClick = actions.onSaveToList) {
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
                    RecipeOverflowMenu(recipeName = recipe.name, onDelete = actions.onDelete)
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
                    SourceCredit(domain, onOpen = { actions.onOpenOriginal(recipe.sourceUrl) })
                    Spacer(Modifier.height(4.dp))
                } else {
                    Spacer(Modifier.height(14.dp))
                }
                Times(recipe.prepTime, recipe.cookTime, recipe.totalTime)
                Spacer(Modifier.height(16.dp))
                ServesUnitsRow(
                    servings = content.servings,
                    yieldText = recipe.yield,
                    unitSystem = state.unitSystem,
                    onServingsChange = actions.onServingsChange,
                    onUnitSystemChange = actions.onUnitSystemChange
                )
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

            itemsIndexed(content.instructions) { index, step ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.width(32.dp)
                    )
                    Text(step, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (content.instructions.isNotEmpty()) {
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

/** Overflow menu: currently just Delete, behind a confirm dialog naming the recipe. */
@Composable
private fun RecipeOverflowMenu(recipeName: String, onDelete: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = {
                expanded = false
                confirming = true
            }
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.delete_recipe_title, recipeName)) },
            text = { Text(stringResource(R.string.delete_recipe_body)) },
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
private fun SourceCredit(domain: String, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            domain,
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

