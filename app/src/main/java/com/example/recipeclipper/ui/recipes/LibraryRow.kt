package com.example.recipeclipper.ui.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.common.RecipeThumbnail
import com.example.recipeclipper.ui.recipe.sourceCreditText

/**
 * A row of the Recipes library (#286, the tidy list): photo, title, the site and total time
 * under it, and a filled bookmark, not the word "Saved", when the recipe is in any list.
 * The site is credited as the reading view credits it. Home and List detail keep `RecipeRow`.
 */
@Composable
internal fun LibraryRow(recipe: RecipeSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        RecipeThumbnail(recipe.imageUrl)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                recipe.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Details(sourceCreditText(recipe.sourceDomain, recipe.isClipped), recipe.totalTime)
        }
        if (recipe.isSaved) {
            Icon(
                painterResource(R.drawable.ic_bookmark),
                contentDescription = stringResource(R.string.cd_in_a_list),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 12.dp).size(20.dp)
            )
        }
    }
}

/** "site · 1h 15m" on one line: a long site is cut short, never the time after it. */
@Composable
private fun Details(credit: String?, totalTime: String?) {
    if (credit == null && totalTime == null) return
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row {
        if (credit != null) {
            Text(
                credit, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        if (totalTime != null) {
            Text(
                if (credit != null) " · $totalTime" else totalTime,
                style = style, color = color, maxLines = 1
            )
        }
    }
}
