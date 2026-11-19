package com.example.recipeclipper.ui.recipes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeCounts
import com.example.recipeclipper.data.model.RecipeFilter

/**
 * The Recipes filter (#345, V1 "Say what it is"): All, In a list and Not in a list as plain text
 * pills with their counts, the chosen one filled in ink. An exclusive choice, so each pill is a
 * radio (selectable) for TalkBack. The pills pick recipes, never ingredients, so the reading
 * view's no-chips rule is untouched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipeFilterPills(
    selected: RecipeFilter,
    counts: RecipeCounts?,
    onSelect: (RecipeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        RecipeFilter.entries.forEach { filter ->
            FilterPill(
                label = stringResource(filterLabel(filter)) + counts?.let { " ${it.of(filter)}" }.orEmpty(),
                selected = filter == selected,
                onClick = { onSelect(filter) },
                modifier = Modifier.testTag("recipeFilter-${filter.name}")
            )
        }
    }
}

private fun filterLabel(filter: RecipeFilter): Int = when (filter) {
    RecipeFilter.ALL -> R.string.recipes_filter_all
    RecipeFilter.IN_A_LIST -> R.string.recipes_filter_in_list
    RecipeFilter.NOT_IN_A_LIST -> R.string.recipes_filter_not_in_list
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    // The 48dp touch target is the outer box; the pill itself is the smaller shape inside it.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .clip(shape)
                .then(
                    if (selected) {
                        Modifier.background(MaterialTheme.colorScheme.onBackground)
                    } else {
                        Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape)
                    }
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * The one muted line under the filter that says what the clean-up does to recipes in no list
 * (#345), with the cap [keep] (the old history's 50): the mockup's info glyph and a quiet panel.
 */
@Composable
internal fun CullNote(keep: Int, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("recipesCullNote")
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp).size(18.dp)
        )
        Text(
            stringResource(R.string.recipes_cull_note, keep),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
