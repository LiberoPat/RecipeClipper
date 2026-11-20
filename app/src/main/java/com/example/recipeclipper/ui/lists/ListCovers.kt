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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.CoverPhoto
import com.example.recipeclipper.data.model.NoPhotoSource
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.ui.common.RecipeImage
import com.example.recipeclipper.ui.common.RecipeImagePlaceholder
import com.example.recipeclipper.ui.common.noPhotoImageOn
import com.example.recipeclipper.ui.savetolist.countLabel

private val CoverShape = RoundedCornerShape(12.dp)
private val MosaicGap = 2.dp

/** One list on the Lists screen (#286): its cover, then its name and count under it. */
@Composable
internal fun ListCoverTile(
    list: RecipeList,
    photos: List<CoverPhoto>,
    onClick: () -> Unit,
    /** The list's recipes with no photo, for a list with no photo at all (#403). */
    placeholders: List<NoPhotoSource> = emptyList()
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CoverShape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("lists.cover.${list.id}")
    ) {
        CoverMosaic(photos, placeholders, Modifier.fillMaxWidth().aspectRatio(1f))
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
 * A list with recipes but no photo shows those recipes' designed images the same way (#403;
 * a list with any photo shows photos alone). An empty list gets a plain tile with an outlined
 * bookmark: nothing saved here yet. With `noPhotoImage` off, no photo at all is that tile too.
 */
@Composable
private fun CoverMosaic(photos: List<CoverPhoto>, placeholders: List<NoPhotoSource>, modifier: Modifier) {
    val gap = Arrangement.spacedBy(MosaicGap)
    val designed = if (photos.isEmpty() && noPhotoImageOn()) placeholders else emptyList()
    // The gaps show the screen's ground; each photo carries its own placeholder fill.
    val plain = if (photos.isEmpty() && designed.isEmpty()) {
        Modifier.background(MaterialTheme.colorScheme.outlineVariant)
    } else {
        Modifier
    }
    Box(modifier.clip(CoverShape).then(plain)) {
        if (designed.isNotEmpty()) {
            Cells(designed, gap) { source, cell -> RecipeImagePlaceholder(source, cell.testTag("lists.coverPlaceholder")) }
        } else if (photos.isEmpty()) {
            Icon(
                painterResource(R.drawable.ic_bookmark_border),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(28.dp).testTag("lists.coverPlain")
            )
        } else {
            Cells(photos, gap) { photo, cell -> CoverPhotoCell(photo, cell) }
        }
    }
}

/** One to four cells in the cover's layout, each drawn by [cell] into the room it is given. */
@Composable
private fun <T> Cells(items: List<T>, gap: Arrangement.HorizontalOrVertical, cell: @Composable (T, Modifier) -> Unit) {
    when (items.size) {
        1 -> cell(items[0], Modifier.fillMaxSize())
        2 -> Row(Modifier.fillMaxSize(), gap) {
            items.forEach { cell(it, Modifier.weight(1f).fillMaxHeight()) }
        }
        3 -> Row(Modifier.fillMaxSize(), gap) {
            cell(items[0], Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), gap) {
                items.drop(1).forEach { cell(it, Modifier.weight(1f).fillMaxWidth()) }
            }
        }
        else -> Column(Modifier.fillMaxSize(), gap) {
            items.take(4).chunked(2).forEach { pair ->
                Row(Modifier.weight(1f).fillMaxWidth(), gap) {
                    pair.forEach { cell(it, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

/**
 * Decorative: the tile's name and count say what it is. Coil's cache shows it offline. A photo
 * that fails to load shows its recipe's designed image instead of a blank tile (#403).
 */
@Composable
private fun CoverPhotoCell(photo: CoverPhoto, modifier: Modifier) {
    RecipeImage(
        model = photo.url,
        recipe = photo.source,
        contentDescription = null,
        modifier = modifier.background(MaterialTheme.colorScheme.outlineVariant).testTag("lists.coverPhoto")
    )
}

/** The last tile: a dashed square with a plus, "New list" under it, opening the inline field. */
@Composable
internal fun NewListTile(onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CoverShape)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .drawBehind {
                    val width = 1.5.dp.toPx()
                    drawRoundRect(
                        color = outline,
                        topLeft = Offset(width / 2, width / 2),
                        size = Size(size.width - width, size.height - width),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                        style = Stroke(
                            width = width,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                        )
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.lists_new_list_tile),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}
