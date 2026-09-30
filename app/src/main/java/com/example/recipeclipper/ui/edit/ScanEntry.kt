package com.example.recipeclipper.ui.edit

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.recipe.PhotoSources
import com.example.recipeclipper.ui.recipe.rememberPhotoSources

/** A scan's pages (#226): enough for a card's front and back or a two-page recipe. */
internal const val MAX_SCAN_PAGES = 6

/**
 * "Scan a recipe" (#226): #116's camera (the camera app, no permission) and Photo Picker (no
 * permission, up to [MAX_SCAN_PAGES] pages in the order picked). Hands the pages' URIs to [onScan];
 * with no camera app, says so.
 */
@Composable
internal fun rememberScanSources(onScan: (List<String>) -> Unit): PhotoSources {
    val context = LocalContext.current
    val noCamera = stringResource(R.string.camera_unavailable)
    return rememberPhotoSources(
        onPicked = { pages -> if (pages.isNotEmpty()) onScan(pages) },
        onNoCamera = { Toast.makeText(context, noCamera, Toast.LENGTH_SHORT).show() },
        maxPicked = MAX_SCAN_PAGES,
        freshCapture = true
    )
}

/** The scan's two choices, as menu rows; [close] shuts the menu first. */
@Composable
internal fun ScanChoices(sources: PhotoSources, close: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_take_photo)) },
        onClick = { close(); sources.takePhoto() }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_choose_photos)) },
        onClick = { close(); sources.pickFromLibrary() }
    )
}

/** Home's "Scan a recipe" (#226), beside "+ New recipe": opens the camera-or-library choice. */
@Composable
fun ScanRecipeButton(onScan: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    val sources = rememberScanSources(onScan)
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }) {
            Text(
                stringResource(R.string.action_scan_recipe),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ScanChoices(sources) { expanded = false }
        }
    }
}
