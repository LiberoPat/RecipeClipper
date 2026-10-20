package com.example.recipeclipper.ui.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeSort
import com.example.recipeclipper.ui.edit.ScanChoices
import com.example.recipeclipper.ui.edit.rememberScanSources

/**
 * The + in the Recipes header (#102): type a recipe in, paste a link, or, with [onScan] (the
 * `photoText` flag, #226), scan one, whose row turns the menu into its two choices: take a
 * photo or choose from the library.
 */
@Composable
internal fun AddMenu(onTypeRecipe: () -> Unit, onPasteLink: () -> Unit, onScan: ((List<String>) -> Unit)? = null) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val sources = rememberScanSources { pages -> onScan?.invoke(pages) }
    Box {
        IconButton(onClick = { scanning = false; expanded = true }) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_recipe))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (scanning) {
                ScanChoices(sources) { expanded = false }
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_type_recipe)) },
                    onClick = { expanded = false; onTypeRecipe() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_paste_link)) },
                    onClick = { expanded = false; onPasteLink() }
                )
                if (onScan != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_scan_recipe)) },
                        onClick = { scanning = true }
                    )
                }
            }
        }
    }
}

/**
 * The order of the rows, as the quiet control at the right of the line under search (#286):
 * the glyph and the current order, opening a menu of radio rows (an exclusive choice, the
 * pantry's pattern). It was the only item of a ⋮ menu in the header before.
 */
@Composable
internal fun SortMenu(sort: RecipeSort, onSort: (RecipeSort) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { expanded = true }
                .minimumInteractiveComponentSize()
                .padding(horizontal = 4.dp)
                .testTag("recipes.sort")
        ) {
            Icon(
                painterResource(R.drawable.ic_sort),
                contentDescription = stringResource(R.string.cd_sort),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Text(
                stringResource(SORT_LABELS.getValue(sort)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SORT_LABELS.forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { RadioButton(selected = option == sort, onClick = null) },
                    onClick = { expanded = false; onSort(option) }
                )
            }
        }
    }
}

/** Each order and its name, in the menu's order. */
private val SORT_LABELS = linkedMapOf(
    RecipeSort.RECENTLY_VIEWED to R.string.sort_recently_viewed,
    RecipeSort.NAME to R.string.sort_name,
    RecipeSort.DATE_ADDED to R.string.sort_date_added,
    RecipeSort.RECENTLY_COOKED to R.string.sort_recently_cooked
)

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
