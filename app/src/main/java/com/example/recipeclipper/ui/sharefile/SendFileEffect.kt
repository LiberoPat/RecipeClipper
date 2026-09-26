package com.example.recipeclipper.ui.sharefile

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.data.backup.ShareFile

/**
 * The platform half of "Send as file" (#149): opens the share sheet on the written file, letting
 * only the chosen app read it, and calls [onFailed] (a snackbar) when it couldn't be written.
 */
@Composable
fun SendFileEffect(viewModel: SendFileViewModel, onFailed: suspend () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.file) {
        val file = state.file ?: return@LaunchedEffect
        shareFile(context, file)
        viewModel.onSent()
    }
    LaunchedEffect(state.failed) {
        if (!state.failed) return@LaunchedEffect
        viewModel.onFailureShown()
        onFailed()
    }
}

private fun shareFile(context: Context, file: SentFile) {
    val uri = file.uri.toUri()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = ShareFile.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, file.name))
    } catch (e: ActivityNotFoundException) {
        // Nothing can receive a file: nothing to do.
    }
}
