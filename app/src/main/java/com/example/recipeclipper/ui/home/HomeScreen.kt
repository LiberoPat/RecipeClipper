package com.example.recipeclipper.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.TooltipScreen
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.ui.askai.AskAiSheet
import com.example.recipeclipper.ui.edit.PasteTarget
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.settings.BackupStatus
import com.example.recipeclipper.ui.settings.BackupStatusText
import com.example.recipeclipper.ui.settings.IMPORT_MIME_TYPES
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import com.example.recipeclipper.ui.tour.TooltipHost
import com.example.recipeclipper.ui.recipes.PasteLinkDialog
import androidx.compose.ui.platform.testTag

/**
 * Home (#286): Continue cooking as a photo card with where the cook stopped, then the ways in
 * (Paste a link, Scan a recipe, Type a recipe, and Ask an AI, #351), and the Recipes row with its
 * count (and the Lists row, without `oneCookbook`). No Recently viewed shelf (#371): Recipes'
 * default sort is that. Sections with nothing in them don't appear, but
 * the Recipes row always does. Settings is the gear beside the title.
 */
@Composable
fun HomeScreen(
    onOpenUrl: (String) -> Unit,
    /** Pasted text that isn't a link (#372): the editor, the meal-plan review, Groceries' list sheet. */
    onOpenPasted: (PasteTarget) -> Unit = {},
    onOpenRecipe: (Long) -> Unit,
    onOpenRecipes: () -> Unit,
    onOpenLists: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewRecipe: () -> Unit = {},
    onScan: (List<String>) -> Unit = {},
    /** "Resume cooking" (#286): the recipe, opened in cook mode. */
    onResumeCooking: (Long) -> Unit = {},
    /** Today's meal (#402): the planned recipe, its planned servings, and whether to start cooking it. */
    onOpenPlanned: (Long, Int?, Boolean) -> Unit = { _, _, _ -> },
    viewModel: HomeViewModel = hiltViewModel(),
    scanEnabled: Boolean = LocalFlagValues.current.isOn(Flag.PHOTO_TEXT),
    /** "Ask an AI" (#351): the fourth tile and its sheet. */
    askAiEnabled: Boolean = LocalFlagValues.current.isOn(Flag.ASK_AI),
    /** One cookbook (#345): Recipes lists the lists and makes new ones, so no Lists row here (#371). */
    oneCookbook: Boolean = LocalFlagValues.current.isOn(Flag.ONE_COOKBOOK),
    /** Today's card (#402): off, Continue cooking always shows the most recent recipe. */
    homeTonight: Boolean = LocalFlagValues.current.isOn(Flag.HOME_TONIGHT)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Restore from a backup file (#150): the same file picker as Settings' Import.
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onRestorePicked(it.toString()) }
    }
    // The one-time folder card (#150): the system's folder picker.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onBackupFolderPicked(uri?.toString())
    }

    RecipeClipperTheme {
        // Inside the theme, like every dialog (#186). The Recipes screen's "Paste a link".
        state.linkInput?.let { link ->
            PasteLinkDialog(
                value = link,
                canOpen = state.canOpenLink,
                onValueChange = viewModel::onLinkChange,
                onOpen = { viewModel.onOpenLink { if (it is PasteTarget.Link) onOpenUrl(it.url) else onOpenPasted(it) } },
                noRecipe = state.linkNoRecipe,
                onDismiss = viewModel::onLinkDismiss
            )
        }
        if (state.askAiOpen && askAiEnabled) AskAiSheet(
                onDismiss = viewModel::onAskAiDismiss,
                onOpenPasted = { if (it is PasteTarget.Link) onOpenUrl(it.url) else onOpenPasted(it) }
            )
        TooltipHost(TooltipScreen.HOME) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 32.dp),
                    modifier = Modifier.fillMaxSize().testTag("home")
                        // Edge-to-edge: the background fills behind the bars, the rows stay clear
                        // of them, the display cutout and the keyboard.
                        .safeDrawingPadding()
                ) {
                    item(key = "title") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.home_title),
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.weight(1f)
                            )
                            // Settings' only entry point today (see the note by the nav block
                            // below). The gear sits up here because it is a destination you visit
                            // rarely and on purpose, unlike Recipes and Lists, which are part of
                            // the daily path and stay as named rows.
                            IconButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.offset(x = 8.dp) // optical edge, past the icon's padding
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.nav_settings),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Continue cooking (#286): a photo card with where the cook stopped. With
                    // homeTonight (#402) it shows only while a recipe is being cooked; otherwise
                    // today's next meal from Week takes its place, and with none there is no card.
                    val today = state.today.takeIf { homeTonight && state.resume == null }
                    if (today != null) {
                        item(key = "today") {
                            TodayCard(
                                today,
                                onStartCooking = { meal -> meal.recipeId?.let { onOpenPlanned(it, meal.servings, true) } },
                                onOpen = { meal -> meal.recipeId?.let { onOpenPlanned(it, meal.servings, false) } },
                                // Closed until tomorrow (#448, `todayCardClose`); Continue cooking has none.
                                onClose = viewModel::onCloseToday.takeIf { state.todayClosable }
                            )
                        }
                    } else if (!homeTonight || state.resume != null) {
                        state.continueCooking?.let { latest ->
                            item(key = "resume") {
                                ResumeCard(
                                    latest, state.resume,
                                    onResume = { onResumeCooking(latest.id) },
                                    onOpen = { onOpenRecipe(latest.id) }
                                )
                            }
                        }
                    }

                    // The three ways in (#286): Paste a link, Scan a recipe (the photoText
                    // flag, #226) and Type a recipe (#29), as equal tiles.
                    item(key = "add") {
                        AddRecipeBlock(
                            onPasteLink = viewModel::onPasteLink,
                            onScan = onScan.takeIf { scanEnabled },
                            onTypeRecipe = onNewRecipe,
                            onAskAi = viewModel::onAskAi.takeIf { askAiEnabled }
                        )
                    }

                    if (state.loaded && state.continueCooking == null) {
                        item {
                            Spacer(Modifier.height(28.dp))
                            Text(
                                stringResource(R.string.home_empty_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // A fresh install with an empty library (#150): bring a backup back in.
                    if (state.showsRestore && viewModel.canRestore) {
                        item {
                            TextButton(
                                onClick = { restorePicker.launch(IMPORT_MIME_TYPES) },
                                enabled = state.restore != BackupStatus.Importing,
                                modifier = Modifier.offset(x = (-12).dp).padding(top = 8.dp)
                            ) {
                                Text(
                                    stringResource(R.string.home_restore_backup),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            BackupStatusText(state.restore)
                        }
                    }

                    if (state.offersBackupFolder) {
                        item {
                            BackupFolderCard(
                                onChoose = { folderPicker.launch(null) },
                                onDismiss = viewModel::onBackupPromptDismissed
                            )
                        }
                    }

                    // Both entries are unconditional. The first (then History) used to appear only once there was a
                    // "continue cooking" recipe, so the block changed shape depending on what was
                    // in the database; a fixed block is easier to aim at than one that moves.
                    //
                    // Settings is not here — it's the gear beside the title, and today that gear
                    // is its only entry point. It could open from elsewhere too: RecipeViewModel
                    // collects AppPreferences.settings, so a recipe left underneath Settings
                    // follows a change as it is made (#24). Whether the recipe screen offers it
                    // is a product call, not a technical constraint.
                    item(key = "nav") {
                        Spacer(Modifier.height(20.dp))
                        Hairline()
                        NavRow(stringResource(R.string.recipes_title), state.recipeCount, onOpenRecipes)
                        if (!oneCookbook) NavRow(stringResource(R.string.nav_lists), state.listCount, onOpenLists)
                    }
                }
            }
        }
    }
}

/**
 * "Keep a backup copy?" (#150): asked once, after the first recipe, never at launch and never in
 * the way of a share. Either answer puts it away for good; Settings keeps the folder row.
 */
@Composable
internal fun BackupFolderCard(onChoose: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.padding(top = 24.dp)) {
        Hairline()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.backup_prompt_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.backup_prompt_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.offset(x = (-12).dp)) {
            TextButton(onClick = onChoose) {
                Text(
                    stringResource(R.string.backup_prompt_choose),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.backup_prompt_not_now),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

