package com.example.recipeclipper.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.recipeclipper.R

/**
 * The quiet "Pro" mark on a Week planning extra's entry point (#413): paprika text in a thin
 * paprika outline, no fill. The extra stays visible; a tap on it opens the Pro screen. iOS's
 * `ProBadge` is the same pill.
 */
@Composable
fun ProBadge(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.tertiary
    Text(
        stringResource(R.string.pro_badge),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.4.sp),
        color = accent,
        modifier = modifier
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 1.dp)
            .testTag("proBadge")
    )
}

/** A text button's label with the [ProBadge] after it: a locked extra's own button (#413). */
@Composable
fun ProTextButton(label: String, locked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(label)
        if (locked) {
            Spacer(Modifier.width(6.dp))
            ProBadge()
        }
    }
}

/**
 * A locked extra that has no button of its own to carry the badge (#413): one quiet line saying
 * what it would do, the badge after it, and a tap opens the Pro screen.
 */
@Composable
fun ProTeaserRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag("proTeaser")
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(8.dp))
        ProBadge()
    }
}
