package com.example.recipeclipper.ui.recipe

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.ui.tour.tooltipAnchor

/**
 * Overflow menu: "Add to plan" (#49) and "Add to groceries" (#50), Edit, "Update from source" for the user's version of a linked recipe (#29),
 * behind a warning that the edits will be lost, and Delete, behind a confirm dialog naming
 * the recipe.
 */
@Composable
internal fun RecipeOverflowMenu(
    recipeName: String,
    canUpdateFromSource: Boolean,
    clipped: Boolean,
    onEdit: () -> Unit,
    onUpdateFromSource: () -> Unit,
    onDelete: () -> Unit,
    photoCount: Int = 0,
    onAddToPlan: (() -> Unit)? = null,
    onAddToGroceries: (() -> Unit)? = null,
    onSendFile: (() -> Unit)? = null
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var confirmingUpdate by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }, modifier = Modifier.tooltipAnchor(Tooltip.RECIPE_MENU)) {
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
        // The share icon stays one tap for text; the file is the second way to send it.
        if (onSendFile != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_send_file)) },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                onClick = {
                    expanded = false
                    onSendFile()
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
