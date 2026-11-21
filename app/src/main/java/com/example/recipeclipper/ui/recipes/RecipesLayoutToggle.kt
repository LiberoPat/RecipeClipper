package com.example.recipeclipper.ui.recipes

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipesLayout

/**
 * The rows / photo grid toggle beside the sort (#380): one button showing the layout it
 * switches to (the grid's glyph over the rows, the rows' over the grid), named for it too.
 */
@Composable
internal fun LayoutToggle(layout: RecipesLayout, onLayout: (RecipesLayout) -> Unit) {
    val toGrid = layout == RecipesLayout.ROWS
    IconButton(
        onClick = { onLayout(if (toGrid) RecipesLayout.GRID else RecipesLayout.ROWS) },
        modifier = Modifier.testTag("recipes.layout")
    ) {
        Icon(
            painterResource(if (toGrid) R.drawable.ic_view_grid else R.drawable.ic_view_rows),
            contentDescription = stringResource(if (toGrid) R.string.cd_layout_to_grid else R.string.cd_layout_to_rows),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
