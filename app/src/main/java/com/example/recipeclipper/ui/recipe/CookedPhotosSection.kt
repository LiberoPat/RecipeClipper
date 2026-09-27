package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.CookedPhoto
import com.example.recipeclipper.ui.plan.fullDate
import com.example.recipeclipper.ui.plan.shortDate
import java.io.File

/**
 * "Your cooks" (#116): at the foot of the reading view, after the steps and the note, so the
 * recipe still opens on the recipe. Empty, it is one quiet line and "I made this"; with photos,
 * a row of thumbnails (newest cook first, each with its date) and a + at the end. Both open the
 * camera, the library, or "Mark as cooked" (#173), whose entry shows as a dated tile, no picture.
 */
@Composable
internal fun CookedPhotosSection(
    photos: List<CookedPhoto>,
    sources: PhotoSources,
    onOpen: (CookedPhoto) -> Unit,
    onMarkCooked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeading(stringResource(R.string.heading_your_cooks))
        Spacer(Modifier.height(8.dp))
        if (photos.isEmpty()) {
            Text(
                stringResource(R.string.cooked_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            AddPhotoMenu(sources, onMarkCooked) { open ->
                OutlinedButton(onClick = open, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.action_i_made_this))
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(photos, key = { "cooked-${it.id}" }) { photo ->
                    val label = stringResource(
                        if (photo.hasPhoto) R.string.cd_cooked_photo else R.string.cd_cooked_mark,
                        fullDate(photo.day)
                    )
                    Column(
                        Modifier.width(THUMB).clickable { onOpen(photo) }.semantics { contentDescription = label }
                    ) {
                        CookedImage(photo, Modifier.size(THUMB).clip(RoundedCornerShape(10.dp)), ContentScale.Crop)
                        Text(
                            shortDate(photo.day),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                item(key = "cooked-add") {
                    AddPhotoMenu(sources, onMarkCooked) { open ->
                        Box(
                            Modifier.size(THUMB).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = open),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add_photo))
                        }
                    }
                }
            }
        }
    }
}

/** Camera, library or "Mark as cooked" (#173), from whatever [anchor] draws; the menu opens under it. */
@Composable
private fun AddPhotoMenu(sources: PhotoSources, onMarkCooked: () -> Unit, anchor: @Composable (open: () -> Unit) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        anchor { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_take_photo)) },
                onClick = { expanded = false; sources.takePhoto() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_choose_photos)) },
                onClick = { expanded = false; sources.pickFromLibrary() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_mark_cooked)) },
                onClick = { expanded = false; onMarkCooked() }
            )
        }
    }
}

/**
 * A stored photo; one whose file isn't here (a phone restored from Android's backup, which leaves
 * photos out) or can't be read says so instead, and a cooking marked with no photo (#173) says
 * "Cooked".
 */
@Composable
internal fun CookedImage(photo: CookedPhoto, modifier: Modifier, scale: ContentScale) {
    val path = photo.path
    if (!photo.hasPhoto || path == null) {
        CookedWithoutPhoto(modifier)
        return
    }
    if (!photo.hasPicture) {
        PhotoMissing(modifier)
        return
    }
    SubcomposeAsyncImage(
        model = File(path),
        contentDescription = null,
        contentScale = scale,
        modifier = modifier,
        error = { PhotoMissing(Modifier.fillMaxSize()) }
    )
}

@Composable
private fun PhotoMissing(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
        Text(
            stringResource(R.string.cooked_photo_missing),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(6.dp)
        )
    }
}

/** In place of a picture, for a cooking marked with no photo (#173): a tick and "Cooked". */
@Composable
private fun CookedWithoutPhoto(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Text(
                stringResource(R.string.cooked_mark),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private val THUMB = 96.dp
