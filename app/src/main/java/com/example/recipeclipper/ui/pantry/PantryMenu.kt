package com.example.recipeclipper.ui.pantry

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.PantrySort
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.ui.tour.tooltipAnchor

/**
 * "Send list" (the in-stock items as text) and "Send as file" (#149), disabled while nothing is
 * in stock, then the sort, then "Clear run-out items" (#194), disabled while nothing has run out.
 */
@Composable
internal fun PantryMenu(
    sort: PantrySort,
    onSort: (PantrySort) -> Unit,
    canSend: Boolean,
    onShare: () -> Unit,
    onSendFile: (() -> Unit)?,
    canClearRunOut: Boolean,
    onClearRunOut: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.tooltipAnchor(Tooltip.PANTRY_MENU)) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share_groceries)) },
                enabled = canSend,
                onClick = {
                    expanded = false
                    onShare()
                }
            )
            if (onSendFile != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_send_file)) },
                    enabled = canSend,
                    onClick = {
                        expanded = false
                        onSendFile()
                    }
                )
            }
            HorizontalDivider()
            // An exclusive choice, so radio rows.
            listOf(PantrySort.AISLE to R.string.pantry_sort_aisle, PantrySort.EXPIRY to R.string.pantry_sort_expiry)
                .forEach { (option, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { RadioButton(selected = option == sort, onClick = null) },
                        onClick = {
                            expanded = false
                            onSort(option)
                        }
                    )
                }
            HorizontalDivider()
            // Asks first; the grocery list is left alone, and Undo puts the items back (#194).
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_clear_run_out)) },
                enabled = canClearRunOut,
                onClick = {
                    expanded = false
                    onClearRunOut()
                }
            )
        }
    }
}
