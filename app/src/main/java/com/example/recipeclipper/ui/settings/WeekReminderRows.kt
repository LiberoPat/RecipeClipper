package com.example.recipeclipper.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.MealReminderKind
import java.util.Calendar

/**
 * The Week section's rows (#397): the evening-before reminder and the optional Tonight reminder,
 * each a switch and, while it is on, its time; a line saying notifications are off when they are.
 * Turning one on is [onSwitch]'s: the screen asks for notification permission first (never on
 * launch, as for the expiry reminders, #52).
 */
@Composable
internal fun WeekReminderRows(
    state: SettingsUiState,
    onSwitch: (MealReminderKind, Boolean) -> Unit,
    onTime: (MealReminderKind, Int) -> Unit
) {
    Column {
        ReminderRows(
            kind = MealReminderKind.EVENING,
            title = stringResource(R.string.evening_reminder_title),
            description = stringResource(R.string.evening_reminder_description),
            on = state.eveningReminder,
            minute = state.eveningReminderMinute,
            onSwitch = onSwitch,
            onTime = onTime
        )
        ReminderRows(
            kind = MealReminderKind.TONIGHT,
            title = stringResource(R.string.tonight_reminder_title),
            description = stringResource(R.string.tonight_reminder_description),
            on = state.tonightReminder,
            minute = state.tonightReminderMinute,
            onSwitch = onSwitch,
            onTime = onTime
        )
        if (state.weekRemindersBlocked) {
            Text(
                stringResource(R.string.expiry_reminders_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("weekRemindersDenied")
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

@Composable
private fun ReminderRows(
    kind: MealReminderKind,
    title: String,
    description: String,
    on: Boolean,
    minute: Int,
    onSwitch: (MealReminderKind, Boolean) -> Unit,
    onTime: (MealReminderKind, Int) -> Unit
) {
    var picking by rememberSaveable(kind) { mutableStateOf(false) }
    SwitchRow(
        title = title,
        description = description,
        checked = on,
        onCheckedChange = { onSwitch(kind, it) }
    )
    if (on) {
        ActionRow(
            title = stringResource(R.string.reminder_time_title),
            description = timeLabel(minute),
            enabled = true,
            onClick = { picking = true },
            modifier = Modifier.testTag("reminderTime-${kind.name}")
        )
    }
    if (picking) {
        ReminderTimeDialog(
            minute = minute,
            onConfirm = {
                picking = false
                onTime(kind, it)
            },
            onDismiss = { picking = false }
        )
    }
    Spacer(Modifier.height(4.dp))
}

/** [minute] minutes after midnight as the phone writes a time ("7:00 PM", "19:00"). */
@Composable
internal fun timeLabel(minute: Int): String {
    val context = LocalContext.current
    val time = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minute / 60)
        set(Calendar.MINUTE, minute % 60)
    }.time
    return android.text.format.DateFormat.getTimeFormat(context).format(time)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeDialog(minute: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val picker = rememberTimePickerState(
        initialHour = minute / 60,
        initialMinute = minute % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    )
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminder_time_dialog_title)) },
        confirmButton = {
            TextButton(onClick = { onConfirm(picker.hour * 60 + picker.minute) }) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    ) {
        // The period selector's default (tertiaryContainer) is Material's baseline pink: the app's
        // tokens, as everywhere (no Material purple).
        TimePicker(
            state = picker,
            colors = TimePickerDefaults.colors(
                periodSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                periodSelectorSelectedContentColor = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}
