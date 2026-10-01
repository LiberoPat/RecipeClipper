package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.StepAmounts

/**
 * A step's text with the amounts inserted from its ingredient lines (#101) set apart, in
 * [accent] (none keeps the text's colour) and a heavier weight, so an insertion never reads as
 * the site's own words. [parts] null: the step as written.
 */
internal fun stepText(text: String, parts: List<StepAmounts.Part>?, accent: Color?): AnnotatedString =
    if (parts == null) AnnotatedString(text) else buildAnnotatedString {
        for (part in parts) {
            if (part.amount) {
                withStyle(SpanStyle(color = accent ?: Color.Unspecified, fontWeight = FontWeight.SemiBold)) { append(part.text) }
            } else {
                append(part.text)
            }
        }
    }

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

/**
 * A group heading among the ingredients ("For the sauce:"): a small subheading with no checkbox,
 * nothing to tick. Marked as a heading for accessibility.
 */
@Composable
internal fun IngredientHeadingRow(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp)
            .semantics { heading() }
    )
}

/** A whole-row tap target: the checkbox only draws the state, the row toggles it. */
@Composable
internal fun IngredientRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(
                textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None
            ),
            color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** The quiet "Back" at the top left of the recipe screen, and of the other screens that borrow it. */
@Composable
internal fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack) {
        Text(
            stringResource(R.string.action_back),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
