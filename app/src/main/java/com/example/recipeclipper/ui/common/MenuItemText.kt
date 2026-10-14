package com.example.recipeclipper.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A ⋮ menu item's text (#260): the label, and for an option that isn't self-explanatory a
 * short muted line under it saying what happens. The item merges its children's semantics, so
 * TalkBack reads both. The menu caps its own width, so a long line wraps.
 */
@Composable
internal fun MenuItemText(label: String, description: String? = null) {
    if (description == null) {
        Text(label)
        return
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
