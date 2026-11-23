package com.example.recipeclipper.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.CookedChoice
import com.example.recipeclipper.data.model.OriginChoice
import com.example.recipeclipper.data.model.RecipeFilterChoices
import com.example.recipeclipper.data.model.RecipeFilterPill
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.settings.SwitchRow
import com.example.recipeclipper.ui.theme.LocalPaprikaTone

/*
 * Recipes' views folded into the filter sheet (#454, `recipesFilters`, over design B): the Lists pill left of
 * search, the sheet's two groups (the recipe; where it is and where it came from) and the pills of the filters
 * on. What each filter selects is `RecipeFilterChoices`' (core), the view screens' and #378's own rules.
 */

/** Search's corner radius (design B's), which the Lists pill beside it shares so the two read as one row. */
internal val SearchRowRadius = 14.dp

/**
 * Lists, left of search (#454): the bookmark, "Lists" and how many, in the paprika tone. It opens the Lists
 * screen, Back returns. It takes the width its words need and never clips; search takes the rest.
 */
@Composable
internal fun ListsPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tone = LocalPaprikaTone.current
    val name = stringResource(R.string.nav_lists)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(SearchRowRadius))
            .background(tone.fill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name, $count" }
            .padding(start = 12.dp, end = 14.dp)
            .testTag("recipesListsPill")
    ) {
        Icon(painterResource(R.drawable.ic_view_lists_b), contentDescription = null, tint = tone.onFill, modifier = Modifier.size(18.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, color = tone.onFill, maxLines = 1, softWrap = false)
        Text(
            "$count",
            style = MaterialTheme.typography.labelLarge,
            color = tone.onFill.copy(alpha = 0.75f),
            maxLines = 1,
            softWrap = false
        )
    }
}

/** The pills of the filters on (#454), each removed by a tap on it, then Clear; with the pantry on, its line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoicePills(pills: List<RecipeFilterPill>, onRemove: (RecipeFilterPill) -> Unit, onClear: () -> Unit) {
    if (pills.isEmpty()) return
    val tone = LocalPaprikaTone.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        pills.forEach { pill ->
            val name = stringResource(pillLabel(pill))
            val removeLabel = stringResource(R.string.cd_remove_filter, name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(tone.fill)
                    .clickable(role = Role.Button) { onRemove(pill) }
                    .semantics(mergeDescendants = true) { contentDescription = removeLabel }
                    .heightIn(min = 32.dp)
                    .padding(start = 12.dp, end = 6.dp)
                    .testTag("recipeFilterPill-${pill.name}")
            ) {
                Text(name, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = tone.onFill)
                Icon(Icons.Default.Close, contentDescription = null, tint = tone.onFill, modifier = Modifier.padding(start = 6.dp).size(16.dp))
            }
        }
        TextButton(onClick = onClear, modifier = Modifier.testTag("recipesFilterClear")) {
            Text(
                stringResource(R.string.action_clear),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
    if (RecipeFilterPill.USES_PANTRY in pills) {
        Text(
            stringResource(R.string.recipes_quick_pantry_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp).testTag("recipeQuickPantryNote")
        )
    }
}

/** A pill's words: the view's or #378's own name, so a filter reads the same in the sheet, the pill and before. */
internal fun pillLabel(pill: RecipeFilterPill): Int = when (pill) {
    RecipeFilterPill.THIRTY_OR_LESS -> R.string.recipes_quick_30_or_less
    RecipeFilterPill.COOKED -> R.string.recipes_view_cooked
    RecipeFilterPill.NOT_COOKED -> R.string.recipes_quick_not_cooked
    RecipeFilterPill.USES_PANTRY -> R.string.recipes_quick_pantry
    RecipeFilterPill.NOT_IN_A_LIST -> R.string.recipes_filter_not_in_list
    RecipeFilterPill.TYPED -> R.string.recipes_view_typed
    RecipeFilterPill.CHAT -> R.string.recipes_view_chat
}

/**
 * The filter sheet with the views folded in (#454): "The recipe" (30 min or less, Cooked: Any / Cooked / Not
 * yet, Uses what's in my pantry) and "Where it is, where it came from" (Not in a list, Came from: Anywhere /
 * Typed by you / From a chat), and Clear. Each control answers at once; the list behind follows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeFiltersSheet(
    choices: RecipeFilterChoices,
    onChange: (RecipeFilterChoices) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)
                .testTag("recipesFilterSheet")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.recipes_filter_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                TextButton(onClick = onClear, enabled = !choices.isEmpty, modifier = Modifier.testTag("recipesFilterSheetClear")) {
                    Text(stringResource(R.string.action_clear))
                }
            }
            GroupLabel(stringResource(R.string.recipes_filter_group_recipe))
            SwitchRow(
                title = stringResource(R.string.recipes_quick_30_or_less),
                description = stringResource(R.string.recipes_quick_30_or_less_hint),
                checked = choices.thirtyOrLess,
                onCheckedChange = { onChange(choices.copy(thirtyOrLess = it)) },
                modifier = Modifier.testTag("recipeFilterSwitch-THIRTY_OR_LESS")
            )
            Hairline()
            ChoiceRow(
                title = stringResource(R.string.recipes_view_cooked),
                options = CookedChoice.entries,
                selected = choices.cooked,
                label = { stringResource(cookedLabel(it)) },
                onSelect = { onChange(choices.copy(cooked = it)) },
                tag = "recipeFilterCooked"
            )
            Hairline()
            SwitchRow(
                title = stringResource(R.string.recipes_quick_pantry),
                description = stringResource(R.string.recipes_quick_pantry_hint),
                checked = choices.usesPantry,
                onCheckedChange = { onChange(choices.copy(usesPantry = it)) },
                modifier = Modifier.testTag("recipeFilterSwitch-USES_PANTRY")
            )
            GroupLabel(stringResource(R.string.recipes_filter_group_where))
            SwitchRow(
                title = stringResource(R.string.recipes_filter_not_in_list),
                description = stringResource(R.string.recipes_filter_not_in_list_hint),
                checked = choices.notInAList,
                onCheckedChange = { onChange(choices.copy(notInAList = it)) },
                modifier = Modifier.testTag("recipeFilterSwitch-NOT_IN_A_LIST")
            )
            Hairline()
            ChoiceRow(
                title = stringResource(R.string.recipes_filter_came_from),
                options = OriginChoice.entries,
                selected = choices.origin,
                label = { stringResource(originLabel(it)) },
                onSelect = { onChange(choices.copy(origin = it)) },
                tag = "recipeFilterOrigin"
            )
        }
    }
}

private fun cookedLabel(choice: CookedChoice): Int = when (choice) {
    CookedChoice.ANY -> R.string.recipes_filter_cooked_any
    CookedChoice.COOKED -> R.string.recipes_view_cooked
    CookedChoice.NOT_YET -> R.string.recipes_filter_not_yet
}

private fun originLabel(choice: OriginChoice): Int = when (choice) {
    OriginChoice.ANYWHERE -> R.string.recipes_filter_anywhere
    OriginChoice.TYPED -> R.string.recipes_view_typed
    OriginChoice.CHAT -> R.string.recipes_view_chat
}

/** A group's small-caps heading in the sheet, as the Add a recipe block's. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp).semantics { heading() }
    )
}

/** One exclusive choice in the sheet: its title, then the options as segments. */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    tag: String
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        ChoiceSegments(options, selected, label, onSelect, tag)
    }
}

/**
 * A segmented choice (the Pantry edit sheet's control, drawn as the mockup's track): the options share the
 * width, the chosen one a raised pill. When one option's words don't fit their share on one line (German, a
 * large font), the options stack, each the full width, so nothing is cut. Radio buttons for TalkBack.
 */
@Composable
private fun <T> ChoiceSegments(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    tag: String
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerHigh)
            .padding(3.dp)
            .selectableGroup()
            .testTag(tag)
    ) {
        Layout(content = {
            options.forEach { option ->
                val on = option == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) colors.background else colors.surfaceContainerHigh)
                        .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("$tag-$option")
                ) {
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                        color = if (on) colors.onBackground else colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }) { measurables, constraints ->
            val width = constraints.maxWidth
            val share = if (measurables.isEmpty()) 0 else width / measurables.size
            val fits = measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= share }
            if (fits) {
                val height = measurables.maxOfOrNull { it.minIntrinsicHeight(share) } ?: 0
                val placeables = measurables.mapIndexed { i, m ->
                    val w = if (i == measurables.lastIndex) width - share * i else share
                    m.measure(Constraints.fixed(w, height))
                }
                layout(width, height) {
                    var x = 0
                    placeables.forEach { it.placeRelative(x, 0); x += it.width }
                }
            } else {
                val placeables = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
                layout(width, placeables.sumOf { it.height }) {
                    var y = 0
                    placeables.forEach { it.placeRelative(0, y); y += it.height }
                }
            }
        }
    }
}
