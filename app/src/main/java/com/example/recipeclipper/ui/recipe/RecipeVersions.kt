package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeLink

/**
 * "Based on <original>" (#354), quietly under a version's title, in the same muted line as "From a
 * chat": a tap opens the original. Only while the original is still here (deleting it clears the
 * link, so a version never points at nothing).
 */
@Composable
internal fun BasedOnLine(original: RecipeLink, onOpen: (recipeId: Long) -> Unit) {
    Text(
        stringResource(R.string.recipe_based_on, original.title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.tertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(
                onClickLabel = stringResource(R.string.cd_open_recipe_named, original.title),
                role = Role.Button,
                onClick = { onOpen(original.id) }
            )
            .testTag("basedOn")
    )
}

/**
 * The versions saved from a recipe (#354), newest first, as a quiet list after its steps: one
 * line each, a tap opens it. Absent when there are none.
 */
@Composable
internal fun VersionsSection(versions: List<RecipeLink>, onOpen: (recipeId: Long) -> Unit) {
    if (versions.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("versions")) {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.recipe_versions_heading),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        versions.forEach { version ->
            Text(
                version.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .padding(vertical = 2.dp)
                    .clickable(
                        onClickLabel = stringResource(R.string.cd_open_recipe_named, version.title),
                        role = Role.Button,
                        onClick = { onOpen(version.id) }
                    )
            )
        }
    }
}
