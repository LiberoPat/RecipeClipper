package com.example.recipeclipper.ui.history

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.common.RecipeRow
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/** Everything you've opened, newest first. Automatic: nothing here was saved on purpose. */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now = remember { System.currentTimeMillis() }
    val snackbarHostState = remember { SnackbarHostState() }

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

    // Keyed on the whole pending list: a second swipe replaces the snackbar with one naming
    // the batch. The restart cancels showSnackbar, so neither branch below runs and the
    // earlier capture is left intact for the new snackbar's Undo to restore.
    LaunchedEffect(state.pendingDeletes) {
        val message = deletedMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = undoLabel,
            withDismissAction = false
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.onUndoDelete()
        else viewModel.onSnackbarDismissed()
    }

    RecipeClipperTheme {
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
                        Text(
                            stringResource(R.string.history_title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        SearchField(query = state.query, onQueryChange = viewModel::onQueryChange)
                        Spacer(Modifier.height(12.dp))
                    }

                    val recipes = state.recipes
                    if (recipes != null && recipes.isEmpty()) {
                        item {
                            Text(
                                if (state.query.isBlank()) {
                                    stringResource(R.string.history_empty)
                                } else {
                                    stringResource(R.string.history_no_results, state.query)
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
                            now = now,
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
        label = { Text(stringResource(R.string.label_search_history)) },
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
    now: Long,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState()
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
            RecipeRow(recipe, now, onClick = onClick)
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
