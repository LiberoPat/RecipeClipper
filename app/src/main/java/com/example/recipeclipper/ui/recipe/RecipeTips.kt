package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R

/**
 * The source's tips (#432), below the method: a quiet "Tips" heading (muted, like Versions), then
 * each tip as written, unnumbered, so they never read as more steps. Cook mode never shows them.
 */
@Composable
internal fun TipsSection(tips: List<String>) {
    if (tips.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("tips")) {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.heading_tips),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() }
        )
        tips.forEach { tip ->
            Text(
                tip,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
    }
}
