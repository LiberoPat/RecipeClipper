package com.example.recipeclipper.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R

/**
 * The "Select all" / "Clear all" row at the top of a pantry checklist (#406): a tri-state
 * checkbox (checked when every row is ticked, dashed when some are) whose label says what a tap
 * does. It only changes the sheet's ticks; the sheet's own button still does all the writing.
 */
@Composable
fun SelectAllRow(state: SelectAllState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val toggleable = when (state) {
        SelectAllState.ALL -> ToggleableState.On
        SelectAllState.SOME -> ToggleableState.Indeterminate
        SelectAllState.NONE -> ToggleableState.Off
    }
    val label = stringResource(if (state == SelectAllState.ALL) R.string.action_clear_all else R.string.action_select_all)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .triStateToggleable(state = toggleable, role = Role.Checkbox, onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        TriStateCheckbox(state = toggleable, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
