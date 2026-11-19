package com.example.recipeclipper.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeCounts
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.model.RecipeList

/**
 * One cookbook (#345, V4): under search, a strip of the automatic views (Not in a list, Cooked,
 * Typed by you, From a chat) followed by the lists, each a small card with its count. A view opens as its own
 * screen (Back goes to Recipes); a list opens its own screen. The views never ask the cook to
 * file anything: they are questions about what is already stored.
 */
@Composable
internal fun RecipesViewsStrip(
    counts: RecipeCounts?,
    lists: List<RecipeList>,
    onOpenView: (RecipeFilter) -> Unit,
    onOpenList: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        modifier = modifier.fillMaxWidth().testTag("recipesViewsStrip")
    ) {
        items(RecipeFilter.views, key = { "view-${it.name}" }) { view ->
            ViewCard(
                icon = viewIcon(view),
                name = stringResource(filterLabel(view)),
                count = counts?.of(view),
                onClick = { onOpenView(view) },
                modifier = Modifier.testTag("recipesView-${view.name}")
            )
        }
        items(lists, key = { "list-${it.id}" }) { list ->
            ViewCard(
                icon = rememberVectorPainter(if (list.isFavorites) Icons.Default.Favorite else Icons.AutoMirrored.Filled.List),
                name = list.name,
                count = list.recipeCount,
                onClick = { onOpenList(list.id) },
                modifier = Modifier.testTag("recipesList-${list.id}")
            )
        }
    }
}

@Composable
private fun viewIcon(view: RecipeFilter): Painter = when (view) {
    RecipeFilter.COOKED -> rememberVectorPainter(Icons.Default.Check)
    RecipeFilter.TYPED -> rememberVectorPainter(Icons.Default.Edit)
    RecipeFilter.CHAT -> painterResource(R.drawable.ic_view_chat)
    else -> painterResource(R.drawable.ic_bookmark_border)
}

@Composable
private fun ViewCard(
    icon: Painter,
    name: String,
    count: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = if (count != null) "$name, $count" else name
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .width(104.dp)
            // The same height whether a name takes one line or two, so the strip reads as a row.
            .heightIn(min = 112.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(10.dp)
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp)
        )
        Text(
            name,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2
        )
        Text(
            count?.toString().orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The one line under a view's title (V4), saying what it holds. */
@Composable
internal fun ViewNote(view: RecipeFilter, modifier: Modifier = Modifier) {
    val note = when (view) {
        RecipeFilter.NOT_IN_A_LIST -> R.string.recipes_view_not_in_list_note
        RecipeFilter.COOKED -> R.string.recipes_view_cooked_note
        RecipeFilter.TYPED -> R.string.recipes_view_typed_note
        RecipeFilter.CHAT -> R.string.recipes_view_chat_note
        else -> return
    }
    Text(
        stringResource(note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().testTag("recipesViewNote")
    )
}
