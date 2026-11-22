package com.example.recipeclipper.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.PantryCoverage
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.common.RecipeImage
import com.example.recipeclipper.ui.common.recipeRowClicks
import com.example.recipeclipper.ui.recipe.sourceCreditText

/**
 * One recipe of the Recipes photo grid (#380): the photo, square, with the bookmark on its corner
 * (the same save control as a row's: filled for a recipe in a list, outlined to open the
 * save-to-list sheet), the title under it, then the site and total time as a row has them. With
 * the pantry chip on (#378), "You have 6 of 8" under that. No swipe to delete here: a grid has no
 * row to swipe, so Delete is in the long-press menu (#376, through [onLongClick]) and a selection
 * (#377). While [selecting] the cell leads with a checkbox on its photo, a tap toggles [selected]
 * through [onClick], and the bookmark button is left out, as in a row.
 */
@Composable
internal fun LibraryGridCell(
    recipe: RecipeSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pantry: PantryCoverage? = null,
    /** One cookbook (#345): an outlined bookmark on a recipe in no list, opening the save-to-list sheet. */
    onSaveToList: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selecting: Boolean = false,
    selected: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .recipeRowClicks(selecting, selected, onClick, onLongClick)
    ) {
        Box {
            val shape = RoundedCornerShape(10.dp)
            // The photo, or the recipe's designed image when it has none or the photo fails (#403).
            RecipeImage(
                model = recipe.imageUrl,
                recipe = recipe.noPhoto,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)
            )
            if (selecting) {
                // Drawn only: the cell itself is the toggle, so it reads as one checkbox.
                Badge(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                    Checkbox(checked = selected, onCheckedChange = null)
                }
            }
            if (recipe.isSaved) {
                // Inset 8dp: where the 48dp button's centred badge sits below, so saving doesn't move it.
                Badge(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_bookmark),
                        contentDescription = stringResource(R.string.cd_in_a_list),
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else if (onSaveToList != null && !selecting) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    // The 48dp target is the button; the round badge inside it is what shows.
                    IconButton(onClick = onSaveToList, modifier = Modifier.testTag("saveToList-${recipe.id}")) {
                        Badge {
                            Icon(
                                painterResource(R.drawable.ic_bookmark_border),
                                contentDescription = stringResource(R.string.cd_save_to_list),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
        Text(
            recipe.title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        val credit = sourceCreditText(recipe.sourceDomain, recipe.isClipped)
            ?: if (recipe.isFromChat) stringResource(R.string.recipes_view_chat) else null
        val detail = listOfNotNull(credit, recipe.totalTime).joinToString(" · ")
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (pantry != null) PantryLine(pantry, Modifier.testTag("pantryHave-${recipe.id}"))
    }
}

/** A round, ground-coloured disc for a glyph that sits on a photo, so it reads on any picture. */
@Composable
private fun Badge(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f))
    ) { content() }
}

/** "You have 6 of 8": how many of the recipe's nameable ingredients the pantry has, never a verdict. */
@Composable
internal fun PantryLine(pantry: PantryCoverage, modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.recipes_pantry_have, pantry.have, pantry.total),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary,
        maxLines = 1,
        modifier = modifier
    )
}
