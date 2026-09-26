package com.example.recipeclipper.ui.groceries

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.recipe.SectionHeading

/**
 * "Add this list" (#149): the lines of a list shared in or pasted, each ticked to start, and one
 * button for each place they can go. Adding to the pantry calls [onAddedToPantry], so the screen
 * can show where they went. Shows while [ReceiveListUiState.lines] is set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveListSheet(viewModel: ReceiveListViewModel, onAddedToPantry: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.added) {
        val added = state.added ?: return@LaunchedEffect
        viewModel.onDismiss()
        if (added == ReceiveTarget.PANTRY) onAddedToPantry()
    }
    val lines = state.lines ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = viewModel::onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().testTag("receiveList")
        ) {
            item(key = "title") {
                SectionHeading(stringResource(R.string.receive_list_title))
                Spacer(Modifier.height(8.dp))
            }
            if (lines.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.receive_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@LazyColumn
            }
            itemsIndexed(lines, key = { index, _ -> "line-$index" }) { index, line ->
                val ticked = index !in state.unticked
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { viewModel.onToggle(index) })
                        .padding(vertical = 4.dp)
                        .testTag("receiveLine-$index")
                ) {
                    Checkbox(checked = ticked, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(line, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item(key = "buttons") {
                val enabled = state.tickedCount > 0 && state.added == null
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::onAddToGroceries,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("receiveToGroceries")
                ) {
                    Text(stringResource(R.string.action_add_to_groceries))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::onAddToPantry,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("receiveToPantry")
                ) {
                    Text(stringResource(R.string.action_add_to_pantry))
                }
            }
        }
    }
}
