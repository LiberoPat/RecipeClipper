package com.example.recipeclipper.ui.common

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * An undo snackbar for [key] (whatever the screen just removed; null shows nothing).
 *
 * It lasts [SnackbarDuration.Long], never Indefinite: with an action label and no duration,
 * Material keeps a snackbar up until it's tapped, and the cook saw "Checked items removed" stay on
 * screen. Leaving the screen (not a rotation) settles it as if it had timed out, so the
 * ViewModel's pending removal is cleared and the snackbar doesn't come back on the next visit.
 */
@Composable
fun UndoSnackbarEffect(
    key: Any?,
    message: String?,
    undoLabel: String,
    hostState: SnackbarHostState,
    onUndo: () -> Unit,
    onDismissed: () -> Unit
) {
    val dismissed by rememberUpdatedState(onDismissed)
    val pending by rememberUpdatedState(key)
    LaunchedEffect(key) {
        if (key == null || message == null) return@LaunchedEffect
        val result = hostState.showSnackbar(
            message,
            actionLabel = undoLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) onUndo() else onDismissed()
    }
    val activity = LocalActivity.current
    // Keyed on nothing: this runs when the screen goes, not when a new removal replaces the last
    // (that one's Undo still covers the earlier capture, as before).
    DisposableEffect(Unit) {
        onDispose {
            // A pending removal whose snackbar is still up when the screen goes: settle it.
            if (pending != null && activity?.isChangingConfigurations != true) {
                hostState.currentSnackbarData?.dismiss()
                dismissed()
            }
        }
    }
}
