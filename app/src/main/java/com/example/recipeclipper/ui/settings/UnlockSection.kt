package com.example.recipeclipper.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.ui.common.noticeMessage
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.SectionHeading

/**
 * "Unlimited recipes" (#107), only with the `freeTier` flag. Actions, not choices, so plain
 * rows; the unlocked state is a sentence, never a bare checkmark.
 */
@Composable
internal fun UnlockSection(
    row: UnlockRow,
    notice: PurchaseOutcome?,
    onUnlock: () -> Unit,
    onRestore: () -> Unit
) {
    Spacer(Modifier.height(16.dp))
    Hairline()
    Spacer(Modifier.height(16.dp))
    SectionHeading(stringResource(R.string.unlimited_title))
    Spacer(Modifier.height(4.dp))
    if (row.unlocked) {
        Text(
            stringResource(R.string.unlimited_unlocked),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 10.dp)
        )
        return
    }
    val body = stringResource(R.string.unlimited_body, LibraryLimit.FREE_RECIPES)
    ActionRow(
        title = row.price?.let { stringResource(R.string.unlock_price, it) } ?: stringResource(R.string.unlock),
        description = body,
        enabled = !row.busy,
        onClick = onUnlock
    )
    ActionRow(title = stringResource(R.string.unlimited_restore), description = null, enabled = !row.busy, onClick = onRestore)
    val status = when {
        row.pending -> R.string.unlimited_pending
        notice != null -> notice.noticeMessage()
        else -> null
    } ?: return
    Text(
        stringResource(status),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}
