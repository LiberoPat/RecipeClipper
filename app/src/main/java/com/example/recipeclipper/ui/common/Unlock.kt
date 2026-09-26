package com.example.recipeclipper.ui.common

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.recipeclipper.R
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.model.LibraryLimit

/** The words for a purchase or restore that didn't simply unlock (#107). */
@StringRes
fun PurchaseOutcome.noticeMessage(): Int = when (this) {
    PurchaseOutcome.PENDING -> R.string.unlimited_pending
    PurchaseOutcome.NOTHING_TO_RESTORE -> R.string.unlock_nothing
    PurchaseOutcome.UNLOCKED -> R.string.unlimited_unlocked
    PurchaseOutcome.CANCELLED, PurchaseOutcome.FAILED -> R.string.unlock_failed
}

/**
 * A typed-in or clipped recipe can't be saved (#107): the free library is full and every
 * recipe is protected. The editor or clip stays open behind it, so nothing typed is lost.
 */
@Composable
fun LibraryFullDialog(onUnlock: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_full_title)) },
        text = { Text(stringResource(R.string.library_full_body, LibraryLimit.FREE_RECIPES)) },
        confirmButton = { TextButton(onClick = onUnlock) { Text(stringResource(R.string.unlock)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
