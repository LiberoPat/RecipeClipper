package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.CookedPhoto
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.ui.plan.fullDate

/** What the full-screen photo can do; the ViewModel's events, and the share sheet. */
internal class CookedPhotoActions(
    val onClose: () -> Unit,
    val onNoteChange: (String) -> Unit,
    val onDayChange: (Long) -> Unit,
    val onDelete: () -> Unit,
    val onShare: (CookedPhoto) -> Unit
)

/** One photo, full screen: the picture, its date (tap to change) and its note, edited in place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CookedPhotoViewer(photo: CookedPhoto, noteDraft: String, actions: CookedPhotoActions) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val date = fullDate(photo.day)
    Dialog(onDismissRequest = actions.onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = actions.onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_photo))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { actions.onShare(photo) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.cd_share_photo))
                    }
                    IconButton(onClick = actions.onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete_photo))
                    }
                }
                CookedImage(photo.path, Modifier.fillMaxWidth().weight(1f), ContentScale.Fit)
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.cd_change_cooked_date, date)) {
                            picking = true
                        }.padding(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text(date, style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = actions.onNoteChange,
                        placeholder = { Text(stringResource(R.string.cooked_note_placeholder)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (picking) {
            // The picker works in UTC midnights, which is exactly how an epoch day is formatted.
            val state = rememberDatePickerState(initialSelectedDateMillis = PlanDays.utcMillis(photo.day))
            DatePickerDialog(
                onDismissRequest = { picking = false },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { actions.onDayChange(Math.floorDiv(it, PlanDays.MILLIS_PER_DAY)) }
                        picking = false
                    }) { Text(stringResource(R.string.action_done)) }
                },
                dismissButton = {
                    TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            ) { DatePicker(state = state) }
        }
    }
}
