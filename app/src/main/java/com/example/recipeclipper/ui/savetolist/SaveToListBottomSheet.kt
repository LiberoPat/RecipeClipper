package com.example.recipeclipper.ui.savetolist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.ui.recipe.SectionHeading

/**
 * Spotify's add-to-playlist model: checkboxes, because a recipe belongs in as many lists as
 * you like, and every tick writes immediately. There is no Save or Cancel — the sheet is
 * dismissed, not submitted, so closing it never discards anything.
 *
 * "+ New list" expands inline into a text field rather than opening a dialog: a dialog
 * stacked on a sheet is two dismissable layers, and it is easy to lose track of which one a
 * back gesture closes.
 *
 * The [viewModel] is passed in rather than resolved here, because the recipe screen already
 * holds one for its bookmark icon and both must be the same instance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToListBottomSheet(viewModel: SaveToListViewModel, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // skipPartiallyExpanded: this sheet is a short list of checkboxes and one button, so a
    // half-height state has nothing to offer — it would only put "+ New list" below the fold
    // and make you drag the sheet up to reach a control that fits on screen anyway.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // "+ New list" opens a keyboard right at the bottom of the sheet. Material3
                // 1.4's default sheet insets (safeDrawing, top and bottom) already clear it
                // and the navigation bar, and consume them, so these two add nothing there;
                // they are kept so the field stays visible if those defaults change again
                // (older releases covered the navigation bar but not the keyboard).
                .navigationBarsPadding()
                .imePadding()
                // Enough lists will outgrow the sheet, and ModalBottomSheet does not scroll
                // arbitrary content for you.
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
        ) {
            SectionHeading(stringResource(R.string.save_to_list_title))
            Spacer(Modifier.height(8.dp))

            state.lists.forEach { list ->
                ListCheckRow(
                    list = list,
                    onToggle = { viewModel.onListToggled(list.id, it) }
                )
            }

            Spacer(Modifier.height(8.dp))

            if (state.creatingList) {
                NewListField(
                    value = state.newListName,
                    onValueChange = viewModel::onNewListNameChange,
                    onConfirm = viewModel::onCreateList,
                    onCancel = viewModel::onCancelCreating
                )
            } else {
                TextButton(onClick = viewModel::onStartCreating) {
                    Text(stringResource(R.string.action_new_list))
                }
            }
        }
    }
}

/**
 * The whole row toggles; the checkbox only draws the state, as in `IngredientRow`.
 *
 * `toggleable`, not `clickable(role = Role.Checkbox)`: only the former puts the on/off state
 * into the semantics tree, so a screen reader announces "ticked" and a UI test can assert it.
 * A clickable row with a Checkbox role looks identical on screen and says nothing.
 */
@Composable
private fun ListCheckRow(list: RecipeList, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = list.containsRecipe, role = Role.Checkbox, onValueChange = onToggle)
            .padding(vertical = 6.dp)
    ) {
        Checkbox(checked = list.containsRecipe, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(list.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                list.countLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NewListField(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.label_list_name)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Box(Modifier.width(4.dp))
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.action_create))
            }
        }
    }
}

/** "Empty" rather than "0 recipes": a state, not a tally. Shared by the sheet and Lists. */
@Composable
internal fun RecipeList.countLabel(): String =
    if (recipeCount == 0) {
        stringResource(R.string.list_count_empty)
    } else {
        pluralStringResource(R.plurals.list_count, recipeCount, recipeCount)
    }
