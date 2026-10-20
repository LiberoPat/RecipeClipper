package com.example.recipeclipper.ui.pantry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.data.model.PantrySection
import com.example.recipeclipper.data.model.PantryStock

/**
 * The three counts under the title (#286): In stock, Running low and Run out, each a box whose
 * tap jumps to its first row ([onJump], with that row's id). A zero does nothing, and while
 * selecting (#258) the boxes stay where they are, dimmed and off, so the rows don't move from
 * under the finger. Read as one each: "2, Run out".
 */
@Composable
internal fun PantryCounts(counts: List<PantryCount>, enabled: Boolean, onJump: (Long) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).alpha(if (enabled) 1f else 0.5f)
    ) {
        counts.forEach { count ->
            val target = count.firstId.takeIf { enabled }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(enabled = target != null, role = Role.Button) { target?.let(onJump) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("pantryCount-${count.stock}")
            ) {
                Text(
                    count.count.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    // As the list shows each: Running low's Low tag, Run out's dimmed section.
                    color = when (count.stock) {
                        PantryStock.IN_STOCK -> MaterialTheme.colorScheme.onSurface
                        PantryStock.RUNNING_LOW -> MaterialTheme.colorScheme.tertiary
                        PantryStock.RUN_OUT -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    stringResource(count.stock.label()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Where a count's tap scrolls in the Pantry's LazyColumn: [id]'s row, or its section's heading
 * when the row heads the section, so the heading shows too. View geometry: it mirrors the
 * LazyColumn's items (the header, then each section's heading and rows).
 */
internal fun List<PantrySection>.scrollIndex(id: Long): Int? {
    var heading = 1
    forEach { section ->
        val at = section.items.indexOfFirst { it.id == id }
        if (at == 0) return heading
        if (at > 0) return heading + 1 + at
        heading += 1 + section.items.size
    }
    return null
}
