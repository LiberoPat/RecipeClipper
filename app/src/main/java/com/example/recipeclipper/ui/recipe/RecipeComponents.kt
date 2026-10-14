package com.example.recipeclipper.ui.recipe

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
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
 * the site's own words. [parts] null: the step as written. A mention naming several lines with
 * different amounts (#254) is a link in [accent] that calls [onLink] with them; with no
 * [onLink] (a done step) it reads as plain text.
 */
internal fun stepText(
    text: String,
    parts: List<StepAmounts.Part>?,
    accent: Color?,
    onLink: ((List<Int>) -> Unit)? = null
): AnnotatedString =
    if (parts == null) AnnotatedString(text) else buildAnnotatedString {
        for (part in parts) {
            when {
                part.amount -> withStyle(SpanStyle(color = accent ?: Color.Unspecified, fontWeight = FontWeight.SemiBold)) { append(part.text) }
                part.lines.isNotEmpty() && onLink != null -> withLink(
                    LinkAnnotation.Clickable(
                        tag = part.lines.joinToString(","),
                        styles = TextLinkStyles(
                            SpanStyle(color = accent ?: Color.Unspecified, textDecoration = TextDecoration.Underline)
                        )
                    ) { onLink(part.lines) }
                ) { append(part.text) }
                else -> append(part.text)
            }
        }
    }

/**
 * Each ingredient link in [parts] (#254) as an accessibility action that reads as a button,
 * "butter, 2 matching ingredients, show", for TalkBack's actions menu.
 */
@Composable
internal fun Modifier.stepLinkActions(parts: List<StepAmounts.Part>?, onLink: ((List<Int>) -> Unit)?): Modifier {
    val links = parts.orEmpty().filter { it.lines.isNotEmpty() }
    if (links.isEmpty() || onLink == null) return this
    val show = stringResource(R.string.step_link_show)
    val actions = links.map { link ->
        val matches = pluralStringResource(R.plurals.step_link_matches, link.lines.size, link.lines.size)
        CustomAccessibilityAction("${link.text}, $matches, $show") { onLink(link.lines); true }
    }
    return semantics { customActions = actions }
}

/** "Back to step N" (#254), after a step's ingredient link: the way back to the step. */
@Composable
internal fun BackToStepChip(step: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Text(
            stringResource(R.string.step_back_to, step + 1),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
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
    modifier: Modifier = Modifier,
    // A step's ingredient link points here (#254): a paprika wash that fades out.
    highlighted: Boolean = false
) {
    val wash by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(if (highlighted) 150 else 900),
        label = "highlight"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(wash)
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
