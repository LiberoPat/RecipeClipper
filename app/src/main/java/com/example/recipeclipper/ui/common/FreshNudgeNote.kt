package com.example.recipeclipper.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.FreshNudge
import com.example.recipeclipper.ui.plan.dayTitle

/**
 * The plan's quiet note about a perishable meal planned later than its group keeps (#396): "Salmon
 * fillets: keeps about 1-2 days once bought. Cook it earlier?", with the one swap that fixes it.
 * It says only what the table says (a range, never a date) and moves nothing until [onSwap] is tapped.
 * [locked] (#413, Pro): the swap carries the Pro badge and the caller's [onSwap] opens the Pro screen.
 */
@Composable
fun FreshNudgeNote(nudge: FreshNudge, onSwap: () -> Unit, modifier: Modifier = Modifier, locked: Boolean = false) {
    Column(modifier) {
        Text(
            stringResource(R.string.fresh_nudge, nudge.name, nudge.keeps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ProTextButton(
            label = stringResource(R.string.fresh_nudge_swap, dayTitle(nudge.swapDay)),
            locked = locked,
            onClick = onSwap,
            modifier = Modifier.testTag("freshSwap")
        )
    }
}
