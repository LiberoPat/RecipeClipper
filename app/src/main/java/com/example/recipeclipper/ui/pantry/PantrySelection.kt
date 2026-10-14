package com.example.recipeclipper.ui.pantry

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.recipe.Hairline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The bar that replaces the Pantry's title while selecting (#258): ✕, "N selected" (announced as
 * it changes), Select all, then Mark run out and Remove, the primary action.
 */
@Composable
internal fun PantrySelectionBar(
    count: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onMarkRunOut: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Inside the screen's safe-drawing padding already, over the top of the list.
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit, modifier = Modifier.testTag("exitSelection")) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_exit_selection))
                }
                Text(
                    pluralStringResource(R.plurals.pantry_selected_count, count, count),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("selectedCount")
                )
                TextButton(onClick = onSelectAll, enabled = !allSelected, modifier = Modifier.testTag("selectAll")) {
                    Text(stringResource(R.string.action_select_all))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                OutlinedButton(onClick = onMarkRunOut, enabled = count > 0, modifier = Modifier.testTag("markRunOutSelected")) {
                    Text(stringResource(R.string.pantry_mark_run_out))
                }
                Button(onClick = onRemove, enabled = count > 0, modifier = Modifier.testTag("removeSelected")) {
                    Text(stringResource(R.string.action_remove))
                }
            }
            Hairline()
        }
    }
}

/** The item under [y] (in the list's own coordinates), from the rows' "item-<id>" keys; null over anything else. */
internal fun LazyListState.itemIdAt(y: Float): Long? {
    val info = layoutInfo
    // Item offsets start after the content padding, which viewportStartOffset (negative) gives.
    val position = y + info.viewportStartOffset
    val row = info.visibleItemsInfo.firstOrNull { position >= it.offset && position < it.offset + it.size } ?: return null
    val key = row.key as? String ?: return null
    return if (key.startsWith("item-")) key.removePrefix("item-").toLongOrNull() else null
}

/**
 * Drag to select (#258), over the Pantry's list: a touch and hold on a row starts selecting
 * ([onLongPress]); while [isSelecting], a press on the checkbox column (up to [checkboxEdge] in)
 * that moves starts a drag ([onDragStart]). Either way, moving then reports each row the finger
 * reaches ([onDragTo]), and near the top (under [topCover], the selection bar laid over the
 * list) or bottom edge the list scrolls on its own. Only the
 * maths of where the finger is lives here; the selection is the ViewModel's.
 *
 * It watches the Initial pass, so once a drag has begun it takes the moves before the list can
 * scroll and the release before the row can count a tap. A plain drag elsewhere, or a tap, is
 * left alone.
 */
internal fun Modifier.dragToSelect(
    listState: LazyListState,
    scope: CoroutineScope,
    checkboxEdge: Dp,
    topCover: () -> Float,
    isSelecting: () -> Boolean,
    onLongPress: (Long) -> Unit,
    onDragStart: (Long) -> Unit,
    onDragTo: (Long) -> Unit,
    onDragEnd: () -> Unit
): Modifier = pointerInput(listState) {
    val edge = 64.dp.toPx()
    val maxStep = 24.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = listState.itemIdAt(down.position.y) ?: return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val began = if (isSelecting() && down.position.x <= checkboxEdge.toPx()) {
            // On a checkbox: a move past the slop is a drag; a release first is the row's tap.
            var moved = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || change.isConsumed) break
                if (abs(change.position.y - down.position.y) > slop || abs(change.position.x - down.position.x) > slop) {
                    change.consume()
                    moved = true
                    break
                }
            }
            if (moved) onDragStart(start)
            moved
        } else {
            // Elsewhere: only a touch and hold that stays put; a release or a move first is left to the row or the list.
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull false
                    if (!change.pressed || change.isConsumed) return@withTimeoutOrNull false
                    if ((change.position - down.position).getDistance() > slop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } == null
            if (held) onLongPress(start)
            held
        }
        if (!began) return@awaitEachGesture

        var y = down.position.y
        // How far to scroll each frame: faster the nearer the edge.
        fun speed(): Float = when {
            y < topCover() + edge -> -maxStep * minOf(1f, (topCover() + edge - y) / edge)
            y > size.height - edge -> maxStep * minOf(1f, (y - (size.height - edge)) / edge)
            else -> 0f
        }
        var autoScroll: Job? = null
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                y = change.position.y
                listState.itemIdAt(y)?.let(onDragTo)
                if (speed() != 0f && autoScroll?.isActive != true) {
                    autoScroll = scope.launch {
                        while (isActive) {
                            val step = speed()
                            if (step == 0f) break
                            listState.scrollBy(step)
                            listState.itemIdAt(y)?.let(onDragTo)
                            delay(16)
                        }
                    }
                }
            }
        } finally {
            autoScroll?.cancel()
            onDragEnd()
        }
    }
}

/** The selection bar's Select all is done when every row shown is checked. */
internal fun PantryUiState.allSelected(): Boolean {
    val ids = selection?.ids ?: return false
    return sections.orEmpty().all { section -> section.items.all { it.id in ids } }
}
