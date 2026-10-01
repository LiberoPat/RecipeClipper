package com.example.recipeclipper.ui.pantry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.PantryStock
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.ui.plan.shortDate
import com.example.recipeclipper.ui.recipe.SectionHeading

/** An item's edit sheet (#194): its stock, name, quantity, staple switch and use-by date, and Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditSheet(editing: PantryEditing, viewModel: PantryViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picking by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = viewModel::onEditDismissed, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .testTag("pantryEdit")
        ) {
            SectionHeading(stringResource(R.string.pantry_edit_title))
            Spacer(Modifier.height(12.dp))
            // Every state, visibly (#194): the row's menu and swipes are shortcuts. Applied at
            // once, as they are.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("pantryEditStock")) {
                PantryStock.entries.forEachIndexed { index, choice ->
                    SegmentedButton(
                        selected = editing.stock == choice,
                        onClick = { viewModel.onEditStock(choice) },
                        shape = SegmentedButtonDefaults.itemShape(index, PantryStock.entries.size),
                        modifier = Modifier.testTag("pantryEditStock-${choice.name}")
                    ) {
                        Text(stringResource(choice.label()), textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = editing.name,
                onValueChange = viewModel::onEditName,
                label = { Text(stringResource(R.string.pantry_label_name)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("pantryEditName")
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = editing.quantity,
                onValueChange = viewModel::onEditQuantity,
                label = { Text(stringResource(R.string.pantry_label_quantity)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("pantryEditQuantity")
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = editing.alwaysHave, role = Role.Switch, onValueChange = viewModel::onEditAlwaysHave)
                    .padding(vertical = 4.dp)
                    .testTag("pantryEditAlwaysHave")
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pantry_always_have), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.pantry_always_have_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = editing.alwaysHave, onCheckedChange = null)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pantry_label_expiry), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        editing.expiresDay?.let { shortDate(it) } ?: stringResource(R.string.pantry_no_date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (editing.expiresDay != null) {
                    TextButton(onClick = { viewModel.onEditExpiry(null) }) { Text(stringResource(R.string.action_clear_date)) }
                }
                TextButton(onClick = { picking = true }) { Text(stringResource(R.string.action_set_date)) }
            }
            editing.purchasedDay?.let {
                Text(
                    stringResource(R.string.pantry_bought, shortDate(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = viewModel::onEditDelete, modifier = Modifier.testTag("pantryEditDelete")) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.tertiary)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = viewModel::onEditSave,
                    enabled = editing.name.isNotBlank(),
                    modifier = Modifier.testTag("pantryEditSave")
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    if (picking) {
        // The picker works in UTC midnights, which is exactly how an epoch day is formatted.
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = editing.expiresDay?.let(PlanDays::utcMillis))
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { viewModel.onEditExpiry(Math.floorDiv(it, PlanDays.MILLIS_PER_DAY)) }
                    picking = false
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}
