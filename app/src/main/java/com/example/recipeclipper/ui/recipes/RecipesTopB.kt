package com.example.recipeclipper.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeCounts
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.model.RecipeQuickFilter
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.settings.SwitchRow
import com.example.recipeclipper.ui.theme.LocalPaprikaTone

/*
 * The top of Recipes, design B (#449, `recipesTopB`, with the library as the first screen): the title
 * row with the + and a ⋮ menu (Select, Settings), the views as one segmented shelf above search, the
 * quick filters (#378) in a sheet behind a button at the end of the search field, and those on as
 * pills under it. The filters behave exactly as the chips did; only where they live changed.
 */

/** "Recipes", the paprika + and the ⋮ menu, last on the right: Select (null: nothing to select) and Settings. */
@Composable
internal fun LibraryTitleRowB(onAdd: () -> Unit, onSelect: (() -> Unit)?, onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)) {
        Text(
            stringResource(R.string.recipes_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f).semantics { heading() }
        )
        LibraryAddButton(onAdd)
        LibraryMoreMenu(onSelect, onOpenSettings)
    }
}

@Composable
private fun LibraryMoreMenu(onSelect: (() -> Unit)?, onOpenSettings: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("libraryMore")) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.cd_more_options),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onSelect != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_select)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_select), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = { open = false; onSelect() },
                    modifier = Modifier.testTag("librarySelect")
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_settings)) },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; onOpenSettings() },
                modifier = Modifier.testTag("librarySettings")
            )
        }
    }
}

/**
 * The views as one segmented shelf: Lists (in the paprika tone), then Not in a list, Cooked, Typed by you
 * and From a chat, each its icon, its count in Fraunces and its name. Each opens its own screen, as the
 * strip's cards did. The five share the width (Lists a little more); when a name's longest word would
 * not fit (German, or a large font), every cell takes the width it needs and the shelf scrolls sideways,
 * so nothing is ever clipped.
 */
@Composable
internal fun ViewsShelf(
    counts: RecipeCounts?,
    listCount: Int,
    onOpenView: (RecipeFilter) -> Unit,
    onOpenLists: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth().testTag("recipesViewsShelf")) {
        val available = constraints.maxWidth
        Box(
            Modifier.horizontalScroll(rememberScrollState())
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            ShelfLayout(available) {
                ShelfCell(
                    painterResource(R.drawable.ic_view_lists_b), listCount, stringResource(R.string.nav_lists),
                    lead = true, onClick = onOpenLists, modifier = Modifier.testTag("recipesLists")
                )
                RecipeFilter.views.forEach { view ->
                    ShelfCell(
                        shelfIcon(view), counts?.of(view), stringResource(filterLabel(view)),
                        lead = false, onClick = { onOpenView(view) }, modifier = Modifier.testTag("recipesView-${view.name}")
                    )
                }
            }
        }
    }
}

/** Shares [available] among the cells (the first 1.1 shares), unless a cell needs more: then each takes what it needs. */
@Composable
private fun ShelfLayout(available: Int, content: @Composable () -> Unit) {
    Layout(content) { measurables, _ ->
        val weights = measurables.indices.map { if (it == 0) 1.1f else 1f }
        val shares = weights.map { (available * it / weights.sum()).toInt() }.toMutableList()
        if (shares.isNotEmpty()) shares[shares.lastIndex] += available - shares.sum()
        val needs = measurables.map { it.minIntrinsicWidth(Constraints.Infinity) }
        val widths = if (needs.indices.all { needs[it] <= shares[it] }) shares else needs.indices.map { maxOf(needs[it], shares[it]) }
        val height = measurables.indices.maxOfOrNull { measurables[it].maxIntrinsicHeight(widths[it]) } ?: 0
        val placeables = measurables.indices.map { measurables[it].measure(Constraints.fixed(widths[it], height)) }
        layout(widths.sum(), height) {
            var x = 0
            placeables.forEach { it.placeRelative(x, 0); x += it.width }
        }
    }
}

@Composable
private fun shelfIcon(view: RecipeFilter): Painter = when (view) {
    RecipeFilter.COOKED -> painterResource(R.drawable.ic_view_cooked)
    RecipeFilter.TYPED -> painterResource(R.drawable.ic_pen)
    RecipeFilter.CHAT -> painterResource(R.drawable.ic_view_chat)
    else -> painterResource(R.drawable.ic_bookmark_border)
}

@Composable
private fun ShelfCell(icon: Painter, count: Int?, name: String, lead: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val tone = LocalPaprikaTone.current
    val hairline = colors.outlineVariant
    val label = if (count != null) "$name, $count" else name
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .then(if (lead) Modifier.background(tone.fill) else Modifier.drawBehind {
                drawLine(hairline, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
            })
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 9.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (lead) tone.onFill else colors.tertiary, modifier = Modifier.size(18.dp))
        Text(
            count?.toString().orEmpty(),
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 22.sp),
            color = if (lead) tone.onFill else colors.onBackground,
            maxLines = 1
        )
        Text(
            name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.5.sp, lineHeight = 13.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Bold
            ),
            color = if (lead) tone.onFill else colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Search, with the filter button at its end ([onOpenFilters]; null with the chips' flag off): the
 * paprika tone and a badge counting the filters on, once one is.
 */
@Composable
internal fun SearchFieldB(query: String, onQueryChange: (String) -> Unit, filtersOn: Int, onOpenFilters: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.label_search_recipes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.onSurfaceVariant) },
        trailingIcon = if (query.isNotEmpty() || onOpenFilters != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_clear_search))
                        }
                    }
                    if (onOpenFilters != null) FilterButton(filtersOn, onOpenFilters)
                }
            }
        } else null,
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = colors.onSurfaceVariant),
        modifier = Modifier.fillMaxWidth().testTag("recipesSearch")
    )
}

@Composable
private fun FilterButton(on: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tone = LocalPaprikaTone.current
    val title = stringResource(R.string.recipes_filter_title)
    val state = if (on > 0) pluralStringResource(R.plurals.recipes_filters_on, on, on) else null
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (on > 0) tone.fill else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = title
                if (state != null) stateDescription = state
            }
            .testTag("recipesFilterButton")
    ) {
        Icon(
            painterResource(R.drawable.ic_filter), contentDescription = null,
            tint = if (on > 0) tone.onFill else colors.onBackground, modifier = Modifier.size(22.dp)
        )
        if (on > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-5).dp, y = 5.dp)
                    .defaultMinSize(16.dp, 16.dp).clip(CircleShape).background(colors.primary)
                    .clearAndSetSemantics { }
            ) {
                Text(
                    "$on",
                    color = colors.onPrimary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

/**
 * The filters that are on, as pills under search, each removed by a tap (its ×), then Clear; with the
 * pantry filter on, the line saying what it keeps. Nothing when no filter is on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActiveFilterPills(active: List<RecipeQuickFilter>, onRemove: (RecipeQuickFilter) -> Unit, onClear: () -> Unit) {
    if (active.isEmpty()) return
    val tone = LocalPaprikaTone.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        active.forEach { filter ->
            val name = stringResource(quickFilterLabel(filter))
            val removeLabel = stringResource(R.string.cd_remove_filter, name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(tone.fill)
                    .clickable(role = Role.Button) { onRemove(filter) }
                    .semantics(mergeDescendants = true) { contentDescription = removeLabel }
                    .heightIn(min = 32.dp)
                    .padding(start = 12.dp, end = 6.dp)
                    .testTag("recipeFilterPill-${filter.name}")
            ) {
                Text(name, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = tone.onFill)
                Icon(
                    Icons.Default.Close, contentDescription = null, tint = tone.onFill,
                    modifier = Modifier.padding(start = 6.dp).size(16.dp)
                )
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
    if (RecipeQuickFilter.USES_PANTRY in active) {
        Text(
            stringResource(R.string.recipes_quick_pantry_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp).testTag("recipeQuickPantryNote")
        )
    }
}

/** The filter sheet: the three filters as switches (Not cooked yet left out inside the Cooked view), and Clear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeFilterSheet(
    offered: List<RecipeQuickFilter>,
    on: Set<RecipeQuickFilter>,
    onToggle: (RecipeQuickFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("recipesFilterSheet")) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.recipes_filter_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                TextButton(onClick = onClear, enabled = on.isNotEmpty(), modifier = Modifier.testTag("recipesFilterSheetClear")) {
                    Text(stringResource(R.string.action_clear))
                }
            }
            offered.forEachIndexed { i, filter ->
                SwitchRow(
                    title = stringResource(quickFilterLabel(filter)),
                    description = stringResource(quickFilterHint(filter)),
                    checked = filter in on,
                    onCheckedChange = { onToggle(filter) },
                    modifier = Modifier.testTag("recipeFilterSwitch-${filter.name}")
                )
                if (i < offered.lastIndex) Hairline()
            }
        }
    }
}

private fun quickFilterHint(filter: RecipeQuickFilter): Int = when (filter) {
    RecipeQuickFilter.THIRTY_OR_LESS -> R.string.recipes_quick_30_or_less_hint
    RecipeQuickFilter.NOT_COOKED -> R.string.recipes_quick_not_cooked_hint
    RecipeQuickFilter.USES_PANTRY -> R.string.recipes_quick_pantry_hint
}
