package com.example.recipeclipper.ui.groceries

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.GroceryRecipeTags
import com.example.recipeclipper.data.model.RecipeTag
import com.example.recipeclipper.ui.plan.fullWeekday
import com.example.recipeclipper.ui.plan.shortWeekday

/**
 * The recipes a grocery line is for (#422), as small tags on their own line under its amount and
 * note, so the two never blur: each a rounded paprika wash with a book glyph, "Mushroom omelette
 * · Thu" when it is planned, wrapping. Up to three show; more, the first two and a quiet "+N"
 * ([GroceryRecipeTags.shown]). A tap opens the recipe ([onOpen]); [testPrefix] tags them for tests
 * ("<prefix>-<recipe id>-<day>"). Used by Groceries and Shop for this week, so they match.
 */
@Composable
internal fun RecipeTagRow(
    tags: List<RecipeTag>,
    onOpen: (RecipeTag) -> Unit,
    testPrefix: String,
    modifier: Modifier = Modifier
) {
    val (shown, more) = remember(tags) { GroceryRecipeTags.shown(tags) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        shown.forEach { tag -> RecipeTagChip(tag, { onOpen(tag) }, "$testPrefix-${tag.recipeId}-${tag.day ?: 0}") }
        if (more > 0) MoreTag(more, testPrefix)
    }
}

/**
 * One tag: a button that reads "For Mushroom omelette, Thursday". The wash is the accent slot
 * (paprika on the ground, the lighter on-ink paprika in dark), as the Home timer's tag. The touch
 * area is a little taller than the pill, so a thumb finds it without taking much of the row's own tap.
 */
@Composable
private fun RecipeTagChip(tag: RecipeTag, onClick: () -> Unit, testTag: String) {
    val accent = MaterialTheme.colorScheme.tertiary
    val visible = tag.day?.let { stringResource(R.string.what_i_need_line_source, tag.title, shortWeekday(it)) } ?: tag.title
    val spoken = tag.day?.let { stringResource(R.string.grocery_tag_for_day, tag.title, fullWeekday(it)) }
        ?: stringResource(R.string.grocery_tag_for, tag.title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = spoken
                text = AnnotatedString(visible)
            }
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = WASH_ALPHA))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(testTag)
    ) {
        Icon(painterResource(R.drawable.ic_book), contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        Text(
            visible,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

/** "+N": the tags not shown. Not a button: the recipes are all on the Recipes screen. */
@Composable
private fun MoreTag(more: Int, testPrefix: String) {
    val spoken = pluralStringResource(R.plurals.grocery_tag_more, more, more)
    Text(
        "+$more",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .testTag("$testPrefix-more")
            .padding(horizontal = 4.dp)
            .clearAndSetSemantics { contentDescription = spoken }
    )
}

/** The paprika wash behind a tag: the same as the Home timer's. */
private const val WASH_ALPHA = 0.12f
