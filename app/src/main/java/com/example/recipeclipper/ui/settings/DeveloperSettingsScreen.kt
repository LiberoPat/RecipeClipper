package com.example.recipeclipper.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * Developer settings (#87), reached only by tapping the version in Settings 7 times, in release
 * builds too. A switch per flag in `shared/flags.json`, then "Reset to defaults". A change
 * applies at once; the meal-plan flag swaps the navigation graph, so it lands on Home.
 */
@Composable
fun DeveloperSettingsScreen(onBack: () -> Unit, viewModel: DeveloperSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize().safeDrawingPadding()
            ) {
                item {
                    BackButton(onBack)
                    Text(
                        stringResource(R.string.developer_settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        stringResource(R.string.developer_settings_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                    )
                }

                items(state.flags, key = { it.flag.key }) { row ->
                    val details = listOfNotNull(
                        row.description,
                        row.issue?.let { stringResource(R.string.developer_flag_issue, it) },
                        if (row.changed) stringResource(R.string.developer_flag_changed) else null
                    ).joinToString(" · ")
                    SwitchRow(
                        title = row.flag.key,
                        description = details,
                        checked = row.on,
                        onCheckedChange = { viewModel.onFlagChange(row.flag, it) }
                    )
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    Hairline()
                    Text(
                        stringResource(R.string.developer_reset),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.anyChanged) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = state.anyChanged, role = Role.Button, onClick = viewModel::onReset)
                            .padding(vertical = 14.dp)
                    )
                }
            }
        }
    }
}
