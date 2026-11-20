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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.recipeclipper.data.model.RecipeQuickFilter

/**
 * The quick filters under search (#378): plain text pills like the old filter pills, each its own
 * on/off switch (a checkbox for TalkBack, since they combine; the pills' radios are exclusive).
 * They pick recipes, never ingredients, so the reading view's no-chips rule is untouched. With the
 * pantry chip on, one muted line says what it keeps, so the rule is never a hidden verdict.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickFilterChips(
    offered: List<RecipeQuickFilter>,
    on: Set<RecipeQuickFilter>,
    onToggle: (RecipeQuickFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        offered.forEach { filter ->
            QuickChip(
                label = stringResource(quickFilterLabel(filter)),
                on = filter in on,
                onToggle = { onToggle(filter) },
                modifier = Modifier.testTag("recipeQuick-${filter.name}")
            )
        }
    }
    if (RecipeQuickFilter.USES_PANTRY in on) {
        Text(
            stringResource(R.string.recipes_quick_pantry_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp).testTag("recipeQuickPantryNote")
        )
    }
}

internal fun quickFilterLabel(filter: RecipeQuickFilter): Int = when (filter) {
    RecipeQuickFilter.UNDER_30 -> R.string.recipes_quick_under_30
    RecipeQuickFilter.NOT_COOKED -> R.string.recipes_quick_not_cooked
    RecipeQuickFilter.USES_PANTRY -> R.string.recipes_quick_pantry
}

@Composable
private fun QuickChip(label: String, on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    // The 48dp touch target is the outer box; the pill itself is the smaller shape inside it.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle() })
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (on) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .clip(shape)
                .then(
                    if (on) {
                        Modifier.background(MaterialTheme.colorScheme.onBackground)
                    } else {
                        Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape)
                    }
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
