package com.example.recipeclipper.ui.lists

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.savetolist.countLabel
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * Every list with its count, built-ins first. Renaming and deleting are on the list's own
 * screen, so this one only lists and creates — one place a list is managed from.
 *
 * There is no empty state: the four built-in lists are seeded when the database is created,
 * so this screen is never actually empty.
 */
@Composable
fun ListsScreen(
    onBack: () -> Unit,
    onOpenList: (Long) -> Unit,
    viewModel: ListsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    BackButton(onBack)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.lists_title),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Hairline()
                }

                items(state.lists.size, key = { state.lists[it].id }) { index ->
                    val list = state.lists[index]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onOpenList(list.id) }
                            .padding(vertical = 14.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(list.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                list.countLabel(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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

                item {
                    Spacer(Modifier.height(16.dp))
                    if (state.creatingList) {
                        Column(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = state.newListName,
                                onValueChange = viewModel::onNewListNameChange,
                                label = { Text(stringResource(R.string.label_list_name)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TextButton(onClick = viewModel::onCancelCreating) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                                Box(Modifier.width(4.dp))
                                TextButton(
                                    onClick = viewModel::onCreateList,
                                    enabled = state.newListName.isNotBlank()
                                ) {
                                    Text(stringResource(R.string.action_create))
                                }
                            }
                        }
                    } else {
                        TextButton(onClick = viewModel::onStartCreating) {
                            Text(stringResource(R.string.action_new_list))
                        }
                    }
                }
            }
        }
    }
}
