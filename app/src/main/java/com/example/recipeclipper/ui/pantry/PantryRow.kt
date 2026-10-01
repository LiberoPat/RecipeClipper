package com.example.recipeclipper.ui.pantry

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.ExpiryBadge
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PantryList
import com.example.recipeclipper.data.model.PantryStock
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.ui.plan.shortDate
import com.example.recipeclipper.ui.tour.tooltipAnchor
import kotlinx.coroutines.launch

/**
 * One item (#194): its name, a "Low" tag while running low, the basket "Groceries" tag while its
 * name is on the grocery list, and its quantity as written on the right. Tap the row for the edit
 * sheet (its In stock | Running low | Run out control changes the state); touch and hold for the
 * other two states; swipes are shortcuts: towards the end restocks, towards the start runs out.
 * No per-row button (owner, 2026-09-29: redundant beside tapping the item). TalkBack reads the
 * row as one ("Garlic, 1 head, Run out, On your grocery list"), with the other two states as its
 * actions.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun PantryRow(
    item: PantryItem,
    today: Long,
    onList: Boolean,
    first: Boolean,
    onSetStock: (PantryStock) -> Unit,
    onEdit: () -> Unit,
    onTakeOffList: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val stock = item.stock
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    // SwipeToDismissBox calls onDismiss from an effect keyed on the lambda, so it stays one
    // instance; the row then springs back, since it stays on screen in its new state.
    val currentOnSetStock by rememberUpdatedState(onSetStock)
    val onDismiss = remember {
        { value: SwipeToDismissBoxValue ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentOnSetStock(PantryStock.IN_STOCK)
                SwipeToDismissBoxValue.EndToStart -> currentOnSetStock(PantryStock.RUN_OUT)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { dismissState.reset() }
            Unit
        }
    }
    var menu by rememberSaveable { mutableStateOf(false) }
    // TalkBack's actions and the long-press menu: both other states.
    val otherStates = PantryStock.entries.filter { it != stock }
    val choiceLabels = otherStates.associateWith { stringResource(it.action()) }
    val alwaysHave = if (item.alwaysHave) stringResource(R.string.pantry_always_have) else null
    val badge = item.expiresDay?.let { PantryList.badge(it, today) }
    val expiry = item.expiresDay?.let { day ->
        if (badge == ExpiryBadge.EXPIRED) stringResource(R.string.pantry_expired)
        else stringResource(R.string.pantry_use_by, shortDate(day))
    }
    val description = (
        listOfNotNull(item.name, item.quantity, alwaysHave) + stringResource(stock.label()) +
            listOfNotNull(stringResource(R.string.pantry_on_list).takeIf { onList }, expiry)
        ).joinToString(", ")
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { StockSwipeBackground(dismissState.dismissDirection) },
        enableDismissFromStartToEnd = stock != PantryStock.IN_STOCK,
        enableDismissFromEndToStart = stock != PantryStock.RUN_OUT,
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onLongClick = { menu = true }, onClick = onEdit)
                        .semantics {
                            contentDescription = description
                            customActions = otherStates.map { choice ->
                                CustomAccessibilityAction(choiceLabels.getValue(choice)) {
                                    onSetStock(choice)
                                    true
                                }
                            }
                        }
                        .then(if (first) Modifier.tooltipAnchor(Tooltip.PANTRY_IN_STOCK) else Modifier)
                        .padding(vertical = 12.dp)
                        .testTag("pantry-${item.id}")
                ) {
                    Column(Modifier.weight(1f)) {
                        // The name, then its tags; they wrap under a long name or large text
                        // rather than squeezing it.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (item.inStock) MaterialTheme.colorScheme.onSurface else muted
                            )
                            if (stock == PantryStock.RUNNING_LOW) {
                                Text(
                                    stringResource(R.string.pantry_low),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                        .testTag("low-${item.id}")
                                )
                            }
                            if (onList) {
                                OnListTag(item.id, onTakeOffList)
                            }
                        }
                        alwaysHave?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
                        expiry?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (badge != null) MaterialTheme.colorScheme.tertiary else muted,
                                modifier = Modifier.testTag("expiry-${item.id}")
                            )
                        }
                    }
                    // The quantity as written, in the button's old place (#194); a long one is cut
                    // short, never more than 40% of the row.
                    item.quantity?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp).maxWidthFraction(0.4f).testTag("quantity-${item.id}")
                        )
                    }
                }
                // The row's menu: both other states.
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    otherStates.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choiceLabels.getValue(choice)) },
                            onClick = {
                                menu = false
                                onSetStock(choice)
                            },
                            modifier = Modifier.testTag("stockMenu-${choice.name}")
                        )
                    }
                }
            }
        }
    }
}

/** Caps a child's width at [fraction] of what its parent offers; it may be narrower. */
private fun Modifier.maxWidthFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val max = if (constraints.hasBoundedWidth) (constraints.maxWidth * fraction).toInt() else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = max))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * A state, not a message (#146): the item's name is on the grocery list; tapping takes it off.
 * It wears the Groceries tab's basket and label (owner, 2026-09-29: "On list" wasn't understood)
 * and reads "On your grocery list". At the largest font scales only the basket shows, so the
 * item's name keeps its room.
 */
@Composable
private fun OnListTag(itemId: Long, onTakeOffList: () -> Unit) {
    val density = LocalDensity.current
    val iconOnly = density.fontScale >= 1.5f
    val iconSize = with(density) { 16.sp.toDp() }
    val description = stringResource(R.string.pantry_on_list)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .clickable(onClickLabel = stringResource(R.string.pantry_take_off_list), role = Role.Button, onClick = onTakeOffList)
            .semantics { contentDescription = description }
            .padding(horizontal = if (iconOnly) 6.dp else 8.dp, vertical = 4.dp)
            .testTag("onList-$itemId")
    ) {
        Icon(
            painterResource(R.drawable.ic_tab_groceries),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(iconSize)
        )
        if (!iconOnly) {
            Text(
                stringResource(R.string.tab_groceries),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                // The description above says it; the label isn't read twice.
                modifier = Modifier.padding(start = 4.dp).clearAndSetSemantics {}
            )
        }
    }
}

/** Behind a swiped row: Restock towards the end, Ran out towards the start. */
@Composable
private fun StockSwipeBackground(direction: SwipeToDismissBoxValue) {
    val restock = direction == SwipeToDismissBoxValue.StartToEnd
    Box(
        Modifier
            .fillMaxSize()
            .background(if (restock) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            .padding(horizontal = 24.dp),
        contentAlignment = if (restock) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        if (direction != SwipeToDismissBoxValue.Settled) {
            Text(
                stringResource(if (restock) R.string.pantry_restock else R.string.pantry_ran_out),
                style = MaterialTheme.typography.labelLarge,
                color = if (restock) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.background
            )
        }
    }
}

/** A stock state's name, as the row reads it out. */
internal fun PantryStock.label(): Int = when (this) {
    PantryStock.IN_STOCK -> R.string.pantry_in_stock
    PantryStock.RUNNING_LOW -> R.string.pantry_running_low
    PantryStock.RUN_OUT -> R.string.pantry_out
}

/** The action that moves an item to this state. */
private fun PantryStock.action(): Int = when (this) {
    PantryStock.IN_STOCK -> R.string.pantry_restock
    PantryStock.RUNNING_LOW -> R.string.pantry_running_low
    PantryStock.RUN_OUT -> R.string.pantry_ran_out
}
