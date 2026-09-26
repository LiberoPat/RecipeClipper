package com.example.recipeclipper.ui.recipe

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * The two ways a picture arrives (#116), both platform effects, so they live in the view layer:
 * the Photo Picker (no permission needed) and the camera app through `ACTION_IMAGE_CAPTURE`
 * (no permission either, because the app doesn't declare CAMERA). Each hands URIs to [onPicked].
 */
internal class PhotoSources(val pickFromLibrary: () -> Unit, val takePhoto: () -> Unit)

@Composable
internal fun rememberPhotoSources(onPicked: (List<String>) -> Unit, onNoCamera: () -> Unit): PhotoSources {
    val context = LocalContext.current
    val library = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED)) { uris ->
        onPicked(uris.map(Uri::toString))
    }
    // Where the camera writes, kept across rotation and process death while the camera is open.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pending
        pending = null
        if (saved && uri != null) onPicked(listOf(uri))
    }
    return PhotoSources(
        pickFromLibrary = {
            library.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        takePhoto = {
            val uri = captureUri(context)
            pending = uri.toString()
            try {
                camera.launch(uri)
            } catch (_: ActivityNotFoundException) {
                pending = null
                onNoCamera()
            }
        }
    )
}

/** One reused file in the cache: the store copies it (downscaled) before the next capture. */
private fun captureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(dir, "capture.jpg"))
}

/** The share sheet with the photo and the recipe's name as plain text (#116). */
internal fun sharePhoto(context: Context, path: String, recipeName: String, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, recipeName)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, chooserTitle))
    } catch (_: ActivityNotFoundException) {
        // Nothing can receive a picture: nothing to do.
    }
}

private const val MAX_PICKED = 10
