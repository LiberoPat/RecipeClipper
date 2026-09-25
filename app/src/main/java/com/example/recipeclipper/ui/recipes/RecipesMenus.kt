package com.example.recipeclipper.ui.recipes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R

/** The + in the Recipes header (#102): type a recipe in, or paste a link. */
@Composable
internal fun AddMenu(onTypeRecipe: () -> Unit, onPasteLink: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_recipe))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_type_recipe)) },
                onClick = { expanded = false; onTypeRecipe() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_paste_link)) },
                onClick = { expanded = false; onPasteLink() }
            )
        }
    }
}

/** The order of the rows: an exclusive choice, so radio rows (the pantry's pattern). */
@Composable
internal fun SortMenu(sort: RecipeSort, onSort: (RecipeSort) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                RecipeSort.RECENTLY_VIEWED to R.string.sort_recently_viewed,
                RecipeSort.NAME to R.string.sort_name,
                RecipeSort.DATE_ADDED to R.string.sort_date_added
            ).forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { RadioButton(selected = option == sort, onClick = null) },
                    onClick = { expanded = false; onSort(option) }
                )
            }
        }
    }
}

/** "Paste a link": one field, opened only when it reads as a link (as Home's field would). */
@Composable
internal fun PasteLinkDialog(
    value: String,
    canOpen: Boolean,
    onValueChange: (String) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_paste_link)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(stringResource(R.string.label_recipe_url)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = onOpen, enabled = canOpen) { Text(stringResource(R.string.action_go)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
