package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.Recipe

/**
 * The reading view's top row: Back, then the bookmark (filled once the recipe is in a list),
 * share, a spinner while "Update from source" runs, and the overflow menu.
 */
@Composable
internal fun ReadingTopBar(
    recipe: Recipe,
    state: RecipeUiState,
    actions: RecipeActions,
    isSaved: Boolean,
    photoCount: Int
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        BackButton(actions.onBack)
        Spacer(Modifier.weight(1f))
        // Filled once the recipe is in at least one list — "saved" is derived from
        // membership, so the icon is reading the same thing the database is.
        // Lists and the overflow's actions need a saved recipe: not one shown but not
        // kept (#107).
        if (!state.notKept) IconButton(onClick = actions.onSaveToList) {
            Icon(
                painter = painterResource(
                    if (isSaved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border
                ),
                contentDescription = stringResource(
                    if (isSaved) R.string.cd_in_a_list else R.string.cd_save_to_list
                ),
                tint = if (isSaved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    LocalContentColor.current
                }
            )
        }
        IconButton(onClick = actions.onShare) {
            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.cd_share_recipe))
        }
        if (state.updatingFromSource) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(12.dp).size(20.dp)
            )
        }
        if (!state.notKept) RecipeOverflowMenu(
            recipeName = recipe.name,
            canUpdateFromSource = recipe.canUpdateFromSource && !state.updatingFromSource,
            clipped = recipe.origin == ContentOrigin.CLIPPED,
            onEdit = actions.onEdit,
            onUpdateFromSource = actions.onUpdateFromSource,
            onDelete = actions.onDelete,
            photoCount = photoCount,
            onAddToPlan = actions.onAddToPlan,
            onAddToGroceries = actions.onAddToGroceries,
            onSendFile = actions.onSendFile
        )
    }
}

/**
 * Credits the site under the title: its domain in muted text, then "Open original" as a quiet
 * paprika link to the page in the browser. Reading view only; cook mode has no room for it.
 */
@Composable
internal fun SourceCredit(domain: String, clipped: Boolean, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A clip (#37) says whose selection it is, so a difference from the page, and a
        // re-share that doesn't refresh it, both make sense.
        Text(
            if (clipped) stringResource(R.string.clipped_by_you_on, domain) else domain,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            stringResource(R.string.action_open_original),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clickable(
                    onClickLabel = stringResource(R.string.cd_open_original, domain),
                    role = Role.Button,
                    onClick = onOpen
                )
        )
    }
}

/**
 * Plain labeled numbers on one line (#286), "Prep 35m · Cook 40m · Total 1h 15m": labels muted,
 * values bold. Deliberately not chips: chips imply tappable, these aren't.
 *
 * One text, so it wraps in a long language (German's "Vorbereitung"), but only between
 * entries: each entry's spaces are non-breaking, and so is the one before its "·", which
 * stays at the end of a line rather than starting the next.
 */
@Composable
internal fun Times(prep: String?, cook: String?, total: String?) {
    val entries = listOfNotNull(
        prep?.let { stringResource(R.string.label_prep) to it },
        cook?.let { stringResource(R.string.label_cook) to it },
        total?.let { stringResource(R.string.label_total) to it }
    )
    if (entries.isEmpty()) return
    val muted = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val value = SpanStyle(fontWeight = FontWeight.Bold)
    val line = buildAnnotatedString {
        entries.forEachIndexed { i, (label, time) ->
            if (i > 0) withStyle(muted) { append("$NBSP· ") }
            withStyle(muted) { append(label.glued()) }
            append(NBSP)
            withStyle(value) { append(time.glued()) }
        }
    }
    Text(line, style = MaterialTheme.typography.bodyMedium)
}

private const val NBSP = ' '

/**
 * "1h 15m" kept whole. A long phrase as written ("Overnight, plus 2 hours to rest") keeps its
 * spaces: unbroken, it would be cut mid-word once it outgrew the line.
 */
private fun String.glued(): String = if (length <= 20) replace(' ', NBSP) else this
