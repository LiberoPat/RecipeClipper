package com.example.recipeclipper.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.NoPhotoSource
import com.example.recipeclipper.data.model.RecipeSummary

/** A recipe in a list: thumbnail, title, and when it was last viewed. */
@Composable
fun RecipeRow(recipe: RecipeSummary, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        RecipeThumbnail(recipe.imageUrl, recipe.noPhoto)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                recipe.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val details = listOfNotNull(
                if (recipe.isClipped) stringResource(R.string.clipped_by_you) else null,
                if (recipe.isFromChat) stringResource(R.string.recipes_view_chat) else null,
                recipe.totalTime,
                TimeAgo.since(recipe.lastViewedAt, now).text()
            ).joinToString("  ·  ")
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (recipe.isSaved) {
                Text(
                    stringResource(R.string.tag_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

/**
 * A row's photo, or the recipe's designed image when it has none or the photo fails to load
 * (#403: the title's first letter at 64 dp; a plain tile with `noPhotoImage` off). Shared with
 * the Recipes library and Home's Recently viewed (#286).
 */
@Composable
internal fun RecipeThumbnail(imageUrl: String?, recipe: NoPhotoSource, size: Dp = 64.dp) {
    RecipeImage(
        model = imageUrl,
        recipe = recipe,
        contentDescription = null,
        modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp))
    )
}
