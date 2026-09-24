package com.example.recipeclipper.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.common.RecipeRow
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.SectionHeading
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * Home: the link field (kept so the app can be tried without the share sheet), then
 * whatever there is to pick up again. Sections with nothing in them don't appear, but the
 * History / Lists block at the bottom always does. Settings is the gear beside the title.
 */
@Composable
fun HomeScreen(
    onOpenUrl: (String) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLists: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now = remember { System.currentTimeMillis() }
    // Computed here, not inside the LazyColumn content lambda: that lambda isn't itself a
    // composable context (only the item {} blocks nested in it are), so stringResource can't
    // be called directly from it.
    val recentlyViewedTitle = stringResource(R.string.section_recently_viewed)

    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.home_title),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f)
                        )
                        // Settings' only entry point today (see the note by the nav block
                        // below). The gear sits up here because it is a destination you visit
                        // rarely and on purpose, unlike History and Lists, which are part of
                        // the daily path and stay as named rows.
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.offset(x = 8.dp) // optical edge, past the icon's padding
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.nav_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        OutlinedTextField(
                            value = state.urlInput,
                            onValueChange = viewModel::onUrlChange,
                            label = { Text(stringResource(R.string.label_recipe_url)) },
                            singleLine = true,
                            isError = state.urlError,
                            supportingText = if (state.urlError) {
                                { Text(stringResource(R.string.error_invalid_url)) }
                            } else null,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.onGo()?.let(onOpenUrl) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(top = 6.dp)
                        ) { Text(stringResource(R.string.action_go)) }
                    }
                }

                state.continueCooking?.let { latest ->
                    item {
                        Spacer(Modifier.height(24.dp))
                        SectionHeading(stringResource(R.string.section_continue_cooking))
                        RecipeRow(latest, now, onClick = { onOpenRecipe(latest.id) })
                    }
                }

                section("recent", recentlyViewedTitle, state.recent, now, onOpenRecipe)

                if (state.loaded && state.continueCooking == null) {
                    item {
                        Spacer(Modifier.height(28.dp))
                        Text(
                            stringResource(R.string.home_empty_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Both entries are unconditional. History used to appear only once there was a
                // "continue cooking" recipe, so the block changed shape depending on what was
                // in the database; a fixed block is easier to aim at than one that moves.
                //
                // Settings is not here — it's the gear beside the title, and today that gear
                // is its only entry point. It could open from elsewhere too: RecipeViewModel
                // collects AppPreferences.settings, so a recipe left underneath Settings
                // follows a change as it is made (#24). Whether the recipe screen offers it
                // is a product call, not a technical constraint.
                item {
                    Spacer(Modifier.height(20.dp))
                    Hairline()
                    NavRow(stringResource(R.string.nav_history), onOpenHistory)
                    NavRow(stringResource(R.string.nav_lists), onOpenLists)
                }
            }
        }
    }
}

/** One row of the nav block at the bottom of Home: a label, a chevron, and a hairline under it. */
@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.weight(1f))
        Text(
            "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Hairline()
}

/**
 * One titled run of recipe rows.
 *
 * [sectionKey] prefixes the item keys, and is not optional even though there is only one
 * section today. A LazyColumn's keys must be unique across the *whole list*, not within a
 * section, so two sections that can hold the same recipe crash with
 * `IllegalArgumentException: Key "3" was already used` if they key on the recipe id alone.
 * That is exactly what "Recently viewed" and "Saved" did — saving a recipe usually means you
 * just opened it. Saved has since been removed (see [HomeUiState]), so keep the prefix: it is
 * what stops the next section from bringing the crash back.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.section(
    sectionKey: String,
    title: String,
    recipes: List<RecipeSummary>,
    now: Long,
    onOpenRecipe: (Long) -> Unit
) {
    if (recipes.isEmpty()) return
    item {
        Spacer(Modifier.height(24.dp))
        SectionHeading(title)
    }
    items(recipes.size, key = { "$sectionKey-${recipes[it].id}" }) { index ->
        val recipe = recipes[index]
        RecipeRow(recipe, now, onClick = { onOpenRecipe(recipe.id) })
    }
}
