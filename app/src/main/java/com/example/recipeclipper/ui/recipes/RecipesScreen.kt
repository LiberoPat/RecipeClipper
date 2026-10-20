package com.example.recipeclipper.ui.recipes

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.ui.common.UndoSnackbarEffect
import com.example.recipeclipper.R
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.RecipeSort
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * Every recipe on the phone (#102, the library that replaced History): newest viewed first by
 * default, searchable, sortable, swipe to delete. The + adds one: typed in (the editor), or
 * from a pasted link (the usual import flow), or scanned (#226).
 */
@Composable
fun RecipesScreen(
    onBack: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onNewRecipe: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onScan: (List<String>) -> Unit = {},
    viewModel: RecipesViewModel = hiltViewModel(),
    scanEnabled: Boolean = LocalFlagValues.current.isOn(Flag.PHOTO_TEXT),
    // A parameter so a test can watch the one snackbar a burst shares (#268).
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Resolved here, not inside LaunchedEffect below: its block is a suspend lambda, not a
    // composable one, so stringResource cannot be called from it. Resolving the finished
    // message here rather than a template also keeps String.format out of it, and lets the
    // many-case be a real plurals lookup.
    val pending = state.pendingDeletes
    val deletedMessage = when {
        pending.isEmpty() -> null
        pending.size == 1 -> stringResource(R.string.snackbar_deleted_one, pending.single())
        else -> pluralStringResource(R.plurals.snackbar_deleted_many, pending.size, pending.size)
    }
    val undoLabel = stringResource(R.string.action_undo)

    // Keyed on the burst, not its list: a further swipe joins the batch and the one snackbar's
    // message counts it in place. Keyed on the list, each swipe took the snackbar down and
    // showed another, which flashed (#268).
    UndoSnackbarEffect(
        state.deleteBatch, deletedMessage, undoLabel, snackbarHostState,
        viewModel::onUndoDelete, viewModel::onSnackbarDismissed
    )

    RecipeClipperTheme {
        // Inside the theme, like every dialog (#186): outside it, Material's purple applies.
        state.linkInput?.let { link ->
            PasteLinkDialog(
                value = link,
                canOpen = state.canOpenLink,
                onValueChange = viewModel::onLinkChange,
                onOpen = { viewModel.onOpenLink()?.let(onOpenUrl) },
                onDismiss = viewModel::onLinkDismiss
            )
        }
        Scaffold(
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    // No colour overrides: the defaults are the scheme's inverse* slots,
                    // which Theme.kt defines. Overriding only the container here is what
                    // made the text and the Undo action unreadable.
                    Snackbar(snackbarData = data)
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            // Edge-to-edge: safeDrawing rather than the default system bars, so the
            // results also clear the keyboard while searching.
            contentWindowInsets = WindowInsets.safeDrawing
        ) { padding ->
            Surface(
                Modifier.fillMaxSize().padding(padding),
                color = MaterialTheme.colorScheme.background
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        BackButton(onBack)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text(
                                stringResource(R.string.recipes_title),
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.weight(1f)
                            )
                            AddMenu(
                                onTypeRecipe = onNewRecipe, onPasteLink = viewModel::onPasteLink,
                                onScan = onScan.takeIf { scanEnabled }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        SearchField(query = state.query, onQueryChange = viewModel::onQueryChange)
                        if (state.showsSortLine) {
                            SortLine(state, viewModel::onSortChange)
                        } else {
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    val recipes = state.recipes
                    if (recipes != null && recipes.isEmpty()) {
                        item {
                            Text(
                                if (state.query.isBlank()) {
                                    stringResource(R.string.recipes_empty)
                                } else {
                                    stringResource(R.string.recipes_no_results, state.query)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(recipes.orEmpty(), key = { it.id }) { recipe ->
                        Hairline()
                        SwipeToDeleteRow(
                            recipe = recipe,
                            onClick = { onOpenRecipe(recipe.id) },
                            onDelete = { viewModel.onDelete(recipe) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.label_search_recipes)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_clear_search))
                }
            }
        } else null,
        modifier = Modifier.fillMaxWidth()
    )
}

/** A history row that can be swiped away in either direction to delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(
    recipe: RecipeSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    // Remembered, not rememberSaveable like rememberSwipeToDismissBoxState: the lazy list keeps a
    // removed row's saved state under its key, so a row put back by Undo came back dismissed and
    // SwipeToDismissBox deleted it again, popping the snackbar back up (#268). A row swiped away
    // is gone, so there is nothing to keep across a rotation either.
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    // SwipeToDismissBox calls onDismiss from an effect keyed on the lambda itself, so the
    // lambda must stay the same instance across recompositions or a recomposition while the
    // row is still settled off-screen would delete it a second time.
    val currentOnDelete by rememberUpdatedState(onDelete)
    val onDismiss = remember { { _: SwipeToDismissBoxValue -> currentOnDelete() } }
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { DeleteBackground(dismissState) },
        modifier = Modifier.fillMaxWidth(),
        // Called once the row has settled off-screen, in either direction.
        onDismiss = onDismiss
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            LibraryRow(recipe, onClick = onClick)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteBackground(dismissState: SwipeToDismissBoxState) {
    val alignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
        Alignment.CenterEnd
    } else {
        Alignment.CenterStart
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment
    ) {
        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
    }
}

/**
 * The quiet line under search (#286): how many recipes on the left ("20 recipes", or on the
 * free tier "12 of 20 recipes", #107), and the order they're in on the right.
 */
@Composable
private fun SortLine(state: RecipesUiState, onSort: (RecipeSort) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val count = state.count
        val listed = state.listed
        Text(
            when {
                count == null -> pluralStringResource(R.plurals.recipes_listed, listed ?: 0, listed ?: 0)
                count.over -> stringResource(R.string.recipes_count_over, count.recipes, count.max)
                else -> stringResource(R.string.recipes_count, count.recipes, count.max)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 2.dp)
        )
        SortMenu(state.sort, onSort)
    }
}
