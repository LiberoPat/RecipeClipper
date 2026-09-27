package com.example.recipeclipper.ui.groceries

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.plan.dayTitle
import com.example.recipeclipper.ui.recipe.SectionHeading

/**
 * "Add to groceries" (#50, Paprika's basket): every line to buy, grouped by recipe (with its
 * day, from the Week), each ticked to start. Untick what's already in the cupboard, then one
 * button adds the rest. Closes once they're added.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToGroceriesSheet(viewModel: AddToGroceriesViewModel, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.added) {
        if (state.added) onDismiss()
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val sources = state.sources
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().testTag("addToGroceries")
        ) {
            item(key = "title") {
                SectionHeading(stringResource(R.string.add_to_groceries_title))
                Spacer(Modifier.height(8.dp))
            }
            when {
                sources == null -> item(key = "loading") {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
                }
                sources.isEmpty() -> item(key = "empty") {
                    Text(
                        stringResource(R.string.groceries_week_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    // One recipe from its own screen needs no heading; the week's are headed.
                    val headed = sources.size > 1 || sources.single().day != null
                    sources.forEach { source ->
                        if (headed) {
                            item(key = "head-${source.key}") {
                                Column(Modifier.padding(top = 12.dp, bottom = 2.dp)) {
                                    Text(
                                        source.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                    )
                                    source.day?.let {
                                        Text(
                                            dayTitle(it),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        itemsIndexed(source.lines, key = { index, _ -> "line-${source.key}-$index" }) { index, line ->
                            val id = SourceLine(source.key, index)
                            val ticked = id !in state.unticked
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { viewModel.onToggle(id) })
                                    .padding(vertical = 4.dp)
                                    .testTag("sheetLine-${source.key}-$index")
                            ) {
                                Checkbox(checked = ticked, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                                Text(line, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    item(key = "add") {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = viewModel::onAdd,
                            enabled = state.tickedCount > 0 && !state.added,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("addToGroceriesButton")
                        ) {
                            Text(stringResource(R.string.action_add_to_groceries))
                        }
                    }
                }
            }
        }
    }
}
