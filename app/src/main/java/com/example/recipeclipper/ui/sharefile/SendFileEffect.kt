package com.example.recipeclipper.ui.sharefile

import android.content.Context
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
    // The line stays up for its whole time: the flag is lowered only once it has gone (#331).
    LaunchedEffect(state.failed) {
        if (!state.failed) return@LaunchedEffect
        onFailed()
        viewModel.onFailureShown()
    }
}

private fun shareFile(context: Context, file: SentFile) =
    shareFileUri(context, file.uri.toUri(), ShareFile.MIME_TYPE, file.name)
