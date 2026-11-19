package com.example.recipeclipper.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * Every list as a square cover in a two-column grid (#286), in the cook's own order (#381: a
 * press and hold lifts a cover to drag it into place; TalkBack gets Move earlier and Move later
 * on each cover instead), with "New list" as the last tile. Renaming and deleting are on the list's own screen, so this one only
 * lists and creates — one place a list is managed from.
 *
 * There is no empty state: the built-in lists are seeded when the database is created, so
 * this screen is never actually empty.
 */
@Composable
fun ListsScreen(
    onBack: () -> Unit,
    onOpenList: (Long) -> Unit,
    viewModel: ListsViewModel = hiltViewModel(),
    reorderEnabled: Boolean = LocalFlagValues.current.isOn(Flag.REORDER_LISTS)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val reorder = remember { GridReorderState() }
    val haptics = LocalHapticFeedback.current

    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize()
                    // Edge-to-edge: the background fills behind the bars, the covers stay
                    // clear of them, the display cutout and the keyboard.
                    .safeDrawingPadding()
                    // Press and hold a cover to drag it into place (#381).
                    .gridReorderGestures(
                        gridState = gridState,
                        reorder = reorder,
                        enabled = reorderEnabled,
                        onLift = { id ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.onDragStart(id)
                        },
                        onOver = viewModel::onDragOver,
                        onDrop = viewModel::onDragEnd,
                        onCancel = viewModel::onDragCancel
                    )
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        BackButton(onBack)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.lists_title),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }

                // Keys are prefixed so a list id can never collide with the other items.
                itemsIndexed(state.lists, key = { _, list -> listKey(list.id) }) { index, list ->
                    ListCoverTile(
                        list = list,
                        photos = state.coverPhotos[list.id].orEmpty(),
                        onClick = { onOpenList(list.id) },
                        // The held cover rides the finger; the others slide to their new places.
                        modifier = if (reorder.draggedKey == listKey(list.id)) {
                            Modifier.heldCover(listKey(list.id), gridState, reorder)
                        } else {
                            Modifier.animateItem()
                        },
                        onMoveEarlier = if (reorderEnabled && index > 0) {
                            { viewModel.onMoveEarlier(list.id) }
                        } else {
                            null
                        },
                        onMoveLater = if (reorderEnabled && index < state.lists.lastIndex) {
                            { viewModel.onMoveLater(list.id) }
                        } else {
                            null
                        }
                    )
                }

                if (state.creatingList) {
                    item(key = "new-list-field", span = { GridItemSpan(maxLineSpan) }) {
                        NewListField(
                            name = state.newListName,
                            onNameChange = viewModel::onNewListNameChange,
                            onCancel = viewModel::onCancelCreating,
                            onCreate = viewModel::onCreateList
                        )
                    }
                } else {
                    item(key = "new-list") { NewListTile(onClick = viewModel::onStartCreating) }
                }
            }
        }
    }
}

/** The tile opens into today's inline field, spanning the grid, with Cancel and Create. */
@Composable
private fun NewListField(
    name: String,
    onNameChange: (String) -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.label_list_name)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Box(Modifier.width(4.dp))
            TextButton(onClick = onCreate, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_create))
            }
        }
    }
}
