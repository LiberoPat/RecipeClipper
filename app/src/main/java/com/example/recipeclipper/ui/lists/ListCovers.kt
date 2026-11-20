package com.example.recipeclipper.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.ui.savetolist.countLabel

private val CoverShape = RoundedCornerShape(12.dp)
private val MosaicGap = 2.dp

/**
 * One list on the Lists screen (#286): its cover, then its name and count under it.
 *
 * [onMoveEarlier] and [onMoveLater] (#381) are the accessible way to reorder, offered as custom
 * actions on the tile (TalkBack's actions menu); null where the move is not possible, the first
 * list's earlier and the last one's later, or where reordering is off.
 */
@Composable
internal fun ListCoverTile(
    list: RecipeList,
    photos: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onMoveEarlier: (() -> Unit)? = null,
    onMoveLater: (() -> Unit)? = null
) {
    val earlierLabel = stringResource(R.string.lists_move_earlier)
    val laterLabel = stringResource(R.string.lists_move_later)
    Column(
        modifier
            .fillMaxWidth()
            .clip(CoverShape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("lists.cover.${list.id}")
            .semantics {
                customActions = listOfNotNull(
                    onMoveEarlier?.let { move -> CustomAccessibilityAction(earlierLabel) { move(); true } },
                    onMoveLater?.let { move -> CustomAccessibilityAction(laterLabel) { move(); true } }
                )
            }
    ) {
        CoverMosaic(photos, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        // No maxLines: a long name, or a large font, wraps rather than being cut off.
        Text(list.name, style = MaterialTheme.typography.titleSmall)
        Text(
            list.countLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Four photos make a 2×2. Fewer fill the square without empty cells: one fills it, two sit
 * side by side, three put the newest on the left half with the next two stacked on the right.
 * No photo at all gets a plain tile with an outlined bookmark: a list, not a missing image.
 */
@Composable
private fun CoverMosaic(photos: List<String>, modifier: Modifier) {
    val gap = Arrangement.spacedBy(MosaicGap)
    // The gaps show the screen's ground; each photo carries its own placeholder fill.
    val plain = if (photos.isEmpty()) {
        Modifier.background(MaterialTheme.colorScheme.outlineVariant)
    } else {
        Modifier
    }
    Box(modifier.clip(CoverShape).then(plain)) {
        when (photos.size) {
            0 -> Icon(
                painterResource(R.drawable.ic_bookmark_border),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(28.dp).testTag("lists.coverPlain")
            )
            1 -> CoverPhoto(photos[0], Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize(), gap) {
                photos.forEach { CoverPhoto(it, Modifier.weight(1f).fillMaxHeight()) }
            }
            3 -> Row(Modifier.fillMaxSize(), gap) {
                CoverPhoto(photos[0], Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), gap) {
                    photos.drop(1).forEach { CoverPhoto(it, Modifier.weight(1f).fillMaxWidth()) }
                }
            }
            else -> Column(Modifier.fillMaxSize(), gap) {
                photos.take(4).chunked(2).forEach { pair ->
                    Row(Modifier.weight(1f).fillMaxWidth(), gap) {
                        pair.forEach { CoverPhoto(it, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
    }
}

/** Decorative: the tile's name and count say what it is. Coil's cache shows it offline. */
@Composable
private fun CoverPhoto(url: String, modifier: Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .background(MaterialTheme.colorScheme.outlineVariant)
            .testTag("lists.coverPhoto")
    )
}
