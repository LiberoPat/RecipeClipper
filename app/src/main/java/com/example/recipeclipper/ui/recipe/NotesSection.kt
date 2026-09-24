package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R

/**
 * The user's own note, edited in place at the foot of the reading view. No Save button and
 * no dialog: every keystroke goes to the ViewModel, which writes it once typing pauses. An
 * empty note is just the quiet "Add a note" placeholder, so a recipe without one carries no
 * extra chrome.
 */
@Composable
internal fun NotesSection(
    notes: String,
    onNotesChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeading(stringResource(R.string.heading_notes))
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = notes,
            onValueChange = onNotesChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .onFocusChanged { onFocusChange(it.isFocused) },
            decorationBox = { field ->
                Column {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        if (notes.isEmpty()) {
                            Text(
                                stringResource(R.string.notes_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        field()
                    }
                    Hairline()
                }
            }
        )
    }
}
