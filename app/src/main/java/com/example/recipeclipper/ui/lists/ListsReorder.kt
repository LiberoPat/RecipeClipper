package com.example.recipeclipper.ui.lists

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The prefix of a list tile's key in the grid; the rest is the list's id. */
internal const val LIST_KEY_PREFIX = "list-"

internal fun listKey(id: Long): String = "$LIST_KEY_PREFIX$id"

/** The list id a grid item key names, or null for the header and the new-list tile. */
internal fun listIdOfKey(key: Any): Long? =
    (key as? String)?.takeIf { it.startsWith(LIST_KEY_PREFIX) }?.removePrefix(LIST_KEY_PREFIX)?.toLongOrNull()

/**
 * Where the held cover is while the cook drags it (#381): only what drawing needs. The order the
 * drag has made so far is the ViewModel's (`ListsUiState.lists`); this is the finger.
 *
 * [pointer] is the finger in the grid's own coordinates, [grab] where on the cover it took hold
 * (the finger minus the cover's top-left at the lift), so the cover stays under the finger as
 * its place in the grid changes beneath it.
 */
@Stable
internal class GridReorderState {
    var draggedKey: Any? by mutableStateOf(null)
    var pointer: Offset by mutableStateOf(Offset.Zero)
    var grab: Offset by mutableStateOf(Offset.Zero)
}

/**
 * Press and hold a list's cover to lift it, drag it over the others to move it, let go to keep
 * it there. No library: the lazy grid already says where every item is
 * (`layoutInfo`), so this only finds the cover under the finger and the one it is over, and
 * scrolls when the finger nears an edge. Taps and scrolling are untouched until the hold.
 */
internal fun Modifier.gridReorderGestures(
    gridState: LazyGridState,
    reorder: GridReorderState,
    enabled: Boolean,
    onLift: (id: Long) -> Unit,
    onOver: (id: Long) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit
): Modifier = if (!enabled) this else pointerInput(gridState, reorder) {
    val edge = 72.dp.toPx()
    val step = 24.dp.toPx()
    coroutineScope {
        fun itemAt(point: Offset, except: Any? = null): LazyGridItemInfo? =
            gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key != except && listIdOfKey(item.key) != null &&
                    point.x >= item.offset.x && point.x < item.offset.x + item.size.width &&
                    point.y >= item.offset.y && point.y < item.offset.y + item.size.height
            }

        detectDragGesturesAfterLongPress(
            onDragStart = lift@{ start ->
                val item = itemAt(start) ?: return@lift
                reorder.draggedKey = item.key
                reorder.pointer = start
                reorder.grab = start - Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
                onLift(listIdOfKey(item.key)!!)
            },
            onDrag = drag@{ change, _ ->
                val key = reorder.draggedKey ?: return@drag
                change.consume()
                reorder.pointer = change.position
                val held = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return@drag
                val center = change.position - reorder.grab +
                    Offset(held.size.width / 2f, held.size.height / 2f)
                itemAt(center, except = key)?.let { onOver(listIdOfKey(it.key)!!) }
                val height = gridState.layoutInfo.viewportSize.height
                when {
                    change.position.y < edge -> launch { gridState.scrollBy(-step) }
                    change.position.y > height - edge -> launch { gridState.scrollBy(step) }
                }
            },
            onDragEnd = {
                reorder.draggedKey = null
                onDrop()
            },
            onDragCancel = {
                reorder.draggedKey = null
                onCancel()
            }
        )
    }
}

/**
 * The held cover rides the finger, a little larger and lifted off the page, above the others.
 * Every other cover is returned unchanged, so it can animate to its new place.
 */
internal fun Modifier.heldCover(
    key: Any,
    gridState: LazyGridState,
    reorder: GridReorderState
): Modifier = if (reorder.draggedKey != key) this else zIndex(1f).graphicsLayer {
    val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
    if (item != null) {
        translationX = reorder.pointer.x - reorder.grab.x - item.offset.x
        translationY = reorder.pointer.y - reorder.grab.y - item.offset.y
    }
    scaleX = 1.05f
    scaleY = 1.05f
    alpha = 0.92f
    shadowElevation = 16.dp.toPx()
}
