package com.example.recipeclipper.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.common.RecipeThumbnail
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.SectionHeading
import androidx.compose.ui.platform.testTag

/**
 * Recently viewed (#286): the five before Continue cooking, as a row of photos with their names.
 * Keys carry the section's prefix: Home's keys must be unique across the screen, and Continue
 * cooking's recipe is never in this row, but the next section to hold one might be.
 */
@Composable
internal fun RecentlyViewed(
    recipes: List<RecipeSummary>,
    onOpenRecipe: (Long) -> Unit,
    /** One cookbook (#345): "See all" opens Recipes in this order; null without the flag. */
    onSeeAll: (() -> Unit)? = null
) {
    Column(Modifier.padding(top = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionHeading(stringResource(R.string.section_recently_viewed)) }
            if (onSeeAll != null) {
                TextButton(onClick = onSeeAll, modifier = Modifier.testTag("home.seeAll")) {
                    Text(
                        stringResource(R.string.home_see_all),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 10.dp),
            modifier = Modifier.fillMaxWidth().testTag("home.recent")
        ) {
            items(recipes, key = { "recent-${it.id}" }) { recipe ->
                Column(
                    Modifier.width(84.dp).clickable(role = Role.Button) { onOpenRecipe(recipe.id) }
                ) {
                    RecipeThumbnail(recipe.imageUrl, recipe.noPhoto, size = 84.dp)
                    Text(
                        recipe.title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/** One row of the nav block at the bottom of Home: a label, its count (#286), a chevron, a hairline. */
@Composable
internal fun NavRow(label: String, count: Int?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.weight(1f))
        count?.let {
            Text(
                "$it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp)
            )
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Hairline()
}
