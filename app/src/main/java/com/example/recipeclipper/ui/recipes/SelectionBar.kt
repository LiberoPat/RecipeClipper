package com.example.recipeclipper.ui.recipes

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.recipe.Hairline

/**
 * The bar over the rows while selecting several recipes (#377), the Pantry's pattern (#258): "N
 * selected" (announced as it changes) and Done, then Add to list, Remove from this list (a list's own
 * screen: [onRemoveFromList] null elsewhere) and Delete, the primary action. Actions need something
 * ticked. It pads nothing for the system bars: the screen puts it inside its own insets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectionBar(
    count: Int,
    onDone: () -> Unit,
    onAddToList: (() -> Unit)?,
    onRemoveFromList: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** Select all (#449, design B): ticks every row in sight; null leaves it out. */
    onSelectAll: (() -> Unit)? = null,
    /** Every row in sight is ticked already: Select all has nothing to do. */
    allSelected: Boolean = false,
    /** The hairline under the bar; design B (#449) leaves it to the first row's own. */
    hairline: Boolean = true
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.recipes_selected_count, count, count),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("selectedCount")
                )
                if (onSelectAll != null) {
                    TextButton(onClick = onSelectAll, enabled = !allSelected, modifier = Modifier.testTag("selectAllRecipes")) {
                        Text(stringResource(R.string.action_select_all), color = if (allSelected) Color.Unspecified else MaterialTheme.colorScheme.onBackground)
                    }
                }
                TextButton(onClick = onDone, modifier = Modifier.testTag("doneSelecting")) {
                    Text(stringResource(R.string.action_done))
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                if (onAddToList != null) {
                    OutlinedButton(onClick = onAddToList, enabled = count > 0, modifier = Modifier.testTag("addSelectedToList")) {
                        if (onSelectAll != null) {
                            // Design B (#449): the bookmark before the words.
                            Icon(painterResource(R.drawable.ic_bookmark_border), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(stringResource(R.string.row_action_add_to_list))
                    }
                }
                if (onRemoveFromList != null) {
                    OutlinedButton(
                        onClick = onRemoveFromList, enabled = count > 0,
                        modifier = Modifier.testTag("removeSelectedFromList")
                    ) { Text(stringResource(R.string.row_action_remove_from_list)) }
                }
                Button(onClick = onDelete, enabled = count > 0, modifier = Modifier.testTag("deleteSelected")) {
                    Text(stringResource(R.string.action_delete))
                }
            }
            if (hairline) Hairline()
        }
    }
}

/** Opens the share sheet with a recipe's text, as the reading view does (#149, #376): title and plain body. */
internal fun shareRecipeText(context: Context, share: RowShare) {
    ShareCompat.IntentBuilder(context)
        .setType("text/plain")
        .setSubject(share.title)
        .setText(share.text)
        .setChooserTitle(share.title)
        .startChooser()
}
