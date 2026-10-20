package com.example.recipeclipper.ui.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.ui.edit.ScanRecipeButton
import com.example.recipeclipper.ui.tour.tooltipAnchor

/**
 * "Add a recipe" (#286): Paste a link (the Recipes screen's dialog), Scan a recipe (#226; null
 * [onScan] while the photoText flag is off, leaving two tiles) and Type a recipe (#29), as equal
 * tiles, then a muted line saying a link can be shared from any app.
 */
@Composable
internal fun AddRecipeBlock(onPasteLink: () -> Unit, onScan: ((List<String>) -> Unit)?, onTypeRecipe: () -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        Text(
            stringResource(R.string.cd_add_recipe).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() }
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(IntrinsicSize.Max)
        ) {
            val tile = Modifier.weight(1f)
            HomeTile(
                R.drawable.ic_link, R.string.action_paste_link, R.string.home_paste_link_hint, onPasteLink,
                tile.tooltipAnchor(Tooltip.HOME_LINK), filled = true
            )
            if (onScan != null) {
                ScanRecipeButton(onScan, tile.fillMaxHeight()) { open ->
                    HomeTile(R.drawable.ic_camera, R.string.action_scan_recipe, R.string.home_scan_hint, open)
                }
            }
            HomeTile(R.drawable.ic_pen, R.string.action_type_recipe, R.string.home_type_hint, onTypeRecipe, tile)
        }
        Text(
            stringResource(R.string.home_share_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

/** One way in: an icon, its name and a short muted line; [filled] is the paprika one. */
@Composable
private fun HomeTile(
    @DrawableRes icon: Int, @StringRes name: Int, @StringRes hint: Int, onClick: () -> Unit,
    modifier: Modifier = Modifier, filled: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val content = if (filled) colors.onPrimary else colors.onSurface
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
            .background(if (filled) colors.primary else colors.surfaceContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(if (filled) Color.White.copy(alpha = 0.18f) else colors.tertiary.copy(alpha = 0.12f))
        ) {
            Icon(painterResource(icon), null, tint = if (filled) content else colors.tertiary, modifier = Modifier.size(20.dp))
        }
        Text(stringResource(name), style = MaterialTheme.typography.titleSmall, color = content)
        Text(
            stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = if (filled) content.copy(alpha = 0.85f) else colors.onSurfaceVariant
        )
    }
}
