package com.example.recipeclipper.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeSummary

/**
 * What a recipe row offers on a long press (#376), as the screen wires it. [onAddToPlan], [onSelect]
 * and [onRemoveFromList] are null where the row has none (the flag off, or not a list's own screen).
 */
internal class RowActionSet(
    val onAddToList: () -> Unit,
    val onAddToPlan: (() -> Unit)?,
    val onShare: () -> Unit,
    val onSelect: (() -> Unit)?,
    val onRemoveFromList: (() -> Unit)?,
    val onDelete: () -> Unit
)

/**
 * A row and its long-press menu (#376). [actions] null leaves the row as it was, a plain tap; with
 * actions, [content] gets the long-press handler to put on the row's own click, and the menu hangs
 * off the row. Whether it is open survives rotation.
 */
@Composable
internal fun RowWithMenu(
    actions: RowActionSet?,
    modifier: Modifier = Modifier,
    content: @Composable (onLongClick: (() -> Unit)?) -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        content(if (actions != null) ({ open = true }) else null)
        if (actions != null) RecipeRowMenu(open, { open = false }, actions)
    }
}

/**
 * Add to list, Add to plan, Share, Select, Remove from this list (a list's own screen) and Delete,
 * in that order, the destructive one last. Each closes the menu, then acts.
 */
@Composable
internal fun RecipeRowMenu(expanded: Boolean, onDismiss: () -> Unit, actions: RowActionSet) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        RowMenuItem(R.string.row_action_add_to_list, "rowMenuAddToList", onDismiss, actions.onAddToList)
        actions.onAddToPlan?.let { RowMenuItem(R.string.action_add_to_plan, "rowMenuAddToPlan", onDismiss, it) }
        RowMenuItem(R.string.action_share, "rowMenuShare", onDismiss, actions.onShare)
        actions.onSelect?.let { RowMenuItem(R.string.action_select, "rowMenuSelect", onDismiss, it) }
        actions.onRemoveFromList?.let {
            RowMenuItem(R.string.row_action_remove_from_list, "rowMenuRemoveFromList", onDismiss, it)
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            onClick = {
                onDismiss()
                actions.onDelete()
            },
            modifier = Modifier.testTag("rowMenuDelete")
        )
    }
}

@Composable
private fun RowMenuItem(label: Int, tag: String, onDismiss: () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = {
            onDismiss()
            onClick()
        },
        modifier = Modifier.testTag(tag)
    )
}

/**
 * A recipe row's tap and long press (#376): a tap opens it, a long press opens the row's menu (the
 * handler [RowWithMenu] gives, null for none); while selecting (#377) the row is one checkbox and a
 * tap toggles it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.recipeRowClicks(
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
): Modifier = if (selecting) {
    toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
} else {
    combinedClickable(
        role = Role.Button,
        onLongClickLabel = if (onLongClick != null) stringResource(R.string.cd_recipe_actions) else null,
        onLongClick = onLongClick,
        onClick = onClick
    )
}

/**
 * The confirmation behind a row menu's Delete and a selection's (#376, #377): it names one recipe,
 * or counts several, and says they can be undone for a few seconds, which the Undo snackbar that
 * follows makes true. One dialog for everything asked about, so a selection is one confirmation.
 */
@Composable
internal fun ConfirmDeleteRecipesDialog(recipes: List<RecipeSummary>, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val one = recipes.size == 1
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (one) {
                    stringResource(R.string.delete_recipe_title, recipes.single().title)
                } else {
                    pluralStringResource(R.plurals.delete_recipes_title, recipes.size, recipes.size)
                }
            )
        },
        text = { Text(stringResource(if (one) R.string.delete_row_body else R.string.delete_rows_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirmDeleteRecipes")) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
