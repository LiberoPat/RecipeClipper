package com.example.recipeclipper.ui.common

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * An undo snackbar for [key] (whatever the screen just removed; null shows nothing).
 *
 * It lasts [SnackbarDuration.Long], never Indefinite: with an action label and no duration,
 * Material keeps a snackbar up until it's tapped, and the cook saw "Checked items removed" stay on
 * screen. Leaving the screen (not a rotation) settles it as if it had timed out, so the
 * ViewModel's pending removal is cleared and the snackbar doesn't come back on the next visit.
 *
 * Only a new [key] shows a new snackbar; [message] is read live, so a key that stays the same
 * while the message changes (Recipes' burst of swipes, "3 recipes deleted") updates the one on
 * screen in place rather than taking it down and showing another, which flashed (#268).
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
    val liveMessage = rememberUpdatedState(message)
    LaunchedEffect(key) {
        if (key == null || message == null) return@LaunchedEffect
        val result = hostState.showSnackbar(LiveVisuals(liveMessage, message, undoLabel))
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

/**
 * Visuals whose message follows [live], keeping the last one while [live] is null (the snackbar
 * fading out after Undo, when the screen has nothing left to say).
 */
private class LiveVisuals(
    private val live: State<String?>,
    private var last: String,
    override val actionLabel: String
) : SnackbarVisuals {
    override val message: String
        get() = live.value?.also { last = it } ?: last
    override val withDismissAction: Boolean get() = false
    override val duration: SnackbarDuration get() = SnackbarDuration.Long
}
