package com.example.recipeclipper.ui.recipes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.PantryCoverage
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.common.RecipeThumbnail
import com.example.recipeclipper.ui.common.recipeRowClicks
import com.example.recipeclipper.ui.recipe.sourceCreditText

/** The bookmark's slot, filled or outlined: the button's 48dp target, so the glyph never shifts. */
internal val BookmarkSlot: Dp = 48.dp

/**
 * A row of the Recipes library (#286, the tidy list): photo, title, the site and total time
 * under it, and a filled bookmark, not the word "Saved", when the recipe is in any list.
 * The site is credited as the reading view credits it. Home and List detail keep `RecipeRow`.
 *
 * A long press opens the row's menu through [onLongClick] (#376); while [selecting] (#377) the row
 * leads with a checkbox, a tap toggles [selected] through [onClick], and the bookmark button is left out.
 */
@Composable
internal fun LibraryRow(
    recipe: RecipeSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** One cookbook (#345): an outlined bookmark on a recipe in no list, opening the save-to-list sheet. */
    onSaveToList: (() -> Unit)? = null,
    /** With the pantry chip on (#378): how many of its ingredients the pantry has, "You have 6 of 8". */
    pantry: PantryCoverage? = null,
    onLongClick: (() -> Unit)? = null,
    selecting: Boolean = false,
    selected: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .recipeRowClicks(selecting, selected, onClick, onLongClick)
            .padding(vertical = 10.dp)
    ) {
        if (selecting) {
            // Drawn only: the row itself is the toggle, so it reads as one checkbox.
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(end = 10.dp))
        }
        RecipeThumbnail(recipe.imageUrl, recipe.noPhoto)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                recipe.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Details(
                sourceCreditText(recipe.sourceDomain, recipe.isClipped)
                    ?: if (recipe.isFromChat) stringResource(R.string.recipes_view_chat) else null,
                recipe.totalTime
            )
            if (pantry != null) PantryLine(pantry, Modifier.testTag("pantryHave-${recipe.id}"))
        }
        if (recipe.isSaved) {
            // The same 48dp slot as the outlined button below, glyph centred, so saving a recipe
            // never moves the bookmark or the text beside it.
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(BookmarkSlot)) {
                Icon(
                    painterResource(R.drawable.ic_bookmark),
                    contentDescription = stringResource(R.string.cd_in_a_list),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else if (onSaveToList != null && !selecting) {
            // Not while ticking: no button to tap by mistake.
            IconButton(
                onClick = onSaveToList,
                modifier = Modifier.size(BookmarkSlot).testTag("saveToList-${recipe.id}")
            ) {
                Icon(
                    painterResource(R.drawable.ic_bookmark_border),
                    contentDescription = stringResource(R.string.cd_save_to_list),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
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
