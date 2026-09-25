package com.example.recipeclipper.ui.week

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.NeedRow
import com.example.recipeclipper.data.model.NeedStatus
import com.example.recipeclipper.ui.plan.shortWeekday
import com.example.recipeclipper.ui.plan.weekRange
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.SectionHeading
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * "What I need" for the week shown (#51): To buy, then In your pantry. Each ingredient shows
 * every planned line naming it, with its recipe and day. Presence only: the pantry says you
 * have flour, never that there's enough, and the screen says so.
 */
@Composable
fun WhatINeedScreen(onBack: () -> Unit, viewModel: WhatINeedViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val needs = state.needs

    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag("whatINeed")
            ) {
                item(key = "header") {
                    BackButton(onBack)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.what_i_need_title), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        remember(state.weekStart) { weekRange(state.weekStart) },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Hairline()
                }
                when {
                    needs == null -> item(key = "loading") {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
                    }
                    needs.isEmpty -> item(key = "empty") {
                        Muted(stringResource(R.string.groceries_week_empty), Modifier.padding(top = 16.dp))
                    }
                    else -> {
                        item(key = "buyHeading") {
                            Column(Modifier.padding(top = 18.dp)) {
                                SectionHeading(stringResource(R.string.what_i_need_buy))
                                Spacer(Modifier.height(8.dp))
                                if (needs.buy.isEmpty()) {
                                    Muted(stringResource(R.string.what_i_need_nothing_to_buy))
                                } else {
                                    Button(
                                        onClick = viewModel::onAddBuyToGroceries,
                                        enabled = !state.added,
                                        modifier = Modifier.testTag("addBuyToGroceries")
                                    ) {
                                        Text(
                                            stringResource(
                                                if (state.added) R.string.what_i_need_added else R.string.action_add_to_groceries
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        itemsIndexed(needs.buy, key = { i, _ -> "buy-$i" }) { _, row -> NeedRowView(row) }
                        if (needs.have.isNotEmpty()) {
                            item(key = "haveHeading") {
                                Column(Modifier.padding(top = 24.dp)) {
                                    SectionHeading(stringResource(R.string.what_i_need_have))
                                    Muted(stringResource(R.string.what_i_need_note), Modifier.padding(top = 4.dp))
                                }
                            }
                            itemsIndexed(needs.have, key = { i, _ -> "have-$i" }) { _, row -> NeedRowView(row) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NeedRowView(row: NeedRow) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            row.name ?: row.lines.first().text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
        )
        when (row.status) {
            NeedStatus.HAVE -> Muted(stringResource(R.string.what_i_need_you_have, row.pantryName.orEmpty()))
            NeedStatus.STAPLE -> Muted(stringResource(R.string.pantry_always_have))
            NeedStatus.BUY -> Unit
        }
        row.lines.forEach { line ->
            val source = line.day?.let { stringResource(R.string.what_i_need_line_source, line.title, shortWeekday(it)) } ?: line.title
            Text(line.text, style = MaterialTheme.typography.bodyMedium)
            Muted(source)
        }
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
