package com.example.recipeclipper.ui.week

import androidx.annotation.StringRes
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
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.LaterRow
import com.example.recipeclipper.data.model.NeedRow
import com.example.recipeclipper.data.model.NeedStatus
import com.example.recipeclipper.data.model.RecipeTag
import com.example.recipeclipper.ui.groceries.RecipeTagRow
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.ui.common.ProTeaserRow
import com.example.recipeclipper.ui.plan.dayTitle
import com.example.recipeclipper.ui.plan.shortWeekday
import com.example.recipeclipper.ui.plan.weekRange
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.SectionHeading
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * "What I need" for the week shown (#51): To buy, then In your pantry. Each ingredient shows
 * every planned line naming it, with its recipe and day. Presence only: the pantry says you
 * have flour, never that there's enough, and the screen says so. An ingredient in the pantry
 * with an amount shows it beside what the week's recipes use, when that adds up exactly (#302):
 * the two side by side, never compared, so the cook judges.
 *
 * With [recipeTags] (#422, `groceryRecipeTags`) each line's recipe is a small tag under it, as in
 * Groceries, and a tap opens the recipe through [onOpenRecipe] at the servings the week plans it at.
 */
@Composable
fun WhatINeedScreen(
    onBack: () -> Unit,
    viewModel: WhatINeedViewModel = hiltViewModel(),
    shopForWeek: Boolean = LocalFlagValues.current.isOn(Flag.SHOP_FOR_WEEK),
    onOpenRecipe: (recipeId: Long, servings: Int?) -> Unit = { _, _ -> },
    recipeTags: Boolean = LocalFlagValues.current.isOn(Flag.GROCERY_RECIPE_TAGS),
    /** The Pro screen (#413): without Pro, the locked Buy later line opens it. */
    onOpenPro: () -> Unit = {}
) {
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
                    Text(stringResource(if (shopForWeek) R.string.action_shop_for_week else R.string.what_i_need_title), style = MaterialTheme.typography.headlineMedium)
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
                        // Fresh food (#396): with perishables for meals 4+ days away, To buy is two trips.
                        val trips = state.shownTrips
                        val buyRows = if (trips != null) trips.now else needs.buy
                        item(key = "buyHeading") {
                            Column(Modifier.padding(top = 18.dp)) {
                                SectionHeading(stringResource(if (trips != null) R.string.what_i_need_buy_now else R.string.what_i_need_buy))
                                Spacer(Modifier.height(8.dp))
                                if (buyRows.isEmpty()) {
                                    Muted(stringResource(if (trips != null) R.string.what_i_need_nothing_to_buy_now else R.string.what_i_need_nothing_to_buy))
                                } else {
                                    AddButton(added = state.added, tag = "addBuyToGroceries", addLabel = R.string.action_add_to_groceries, onClick = viewModel::onAddBuyToGroceries)
                                }
                                // Without Pro (#413): one list, all bought now, and the second trip as one locked line.
                                if (state.showsSplitTeaser) {
                                    ProTeaserRow(stringResource(R.string.pro_teaser_fresh), onClick = onOpenPro, Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                        itemsIndexed(buyRows, key = { i, _ -> "buy-$i" }) { _, row -> NeedRowView(row, recipeTags = recipeTags, onOpenRecipe = onOpenRecipe) }
                        if (trips != null) {
                            item(key = "laterHeading") {
                                Column(Modifier.padding(top = 24.dp).testTag("buyLater")) {
                                    SectionHeading(stringResource(R.string.what_i_need_buy_later))
                                    Muted(stringResource(R.string.what_i_need_buy_later_note), Modifier.padding(top = 4.dp, bottom = 8.dp))
                                    AddButton(added = state.addedLater, tag = "addLaterToGroceries", addLabel = R.string.what_i_need_add_later, onClick = viewModel::onAddLaterToGroceries)
                                }
                            }
                            itemsIndexed(trips.later, key = { i, _ -> "later-$i" }) { _, later -> NeedRowView(later.row, later, recipeTags, onOpenRecipe) }
                        }
                        if (needs.have.isNotEmpty()) {
                            item(key = "haveHeading") {
                                Column(Modifier.padding(top = 24.dp)) {
                                    SectionHeading(stringResource(R.string.what_i_need_have))
                                    Muted(stringResource(R.string.what_i_need_note), Modifier.padding(top = 4.dp))
                                }
                            }
                            itemsIndexed(needs.have, key = { i, _ -> "have-$i" }) { _, row -> NeedRowView(row, recipeTags = recipeTags, onOpenRecipe = onOpenRecipe) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddButton(added: Boolean, tag: String, @StringRes addLabel: Int, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !added, modifier = Modifier.testTag(tag)) {
        Text(stringResource(if (added) R.string.what_i_need_added else addLabel))
    }
}

/** One ingredient of the week; [later] (#396) adds the day it is first needed and what its group keeps. */
@Composable
private fun NeedRowView(
    row: NeedRow,
    later: LaterRow? = null,
    recipeTags: Boolean = false,
    onOpenRecipe: (recipeId: Long, servings: Int?) -> Unit = { _, _ -> }
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            row.name ?: row.lines.first().text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
        )
        if (later != null) {
            Muted(stringResource(R.string.what_i_need_later_for, dayTitle(later.firstDay)), Modifier.testTag("laterFor"))
            Muted(stringResource(R.string.fresh_keeps, later.group.keeps))
        }
        when (row.status) {
            NeedStatus.HAVE -> {
                Muted(stringResource(R.string.what_i_need_you_have, row.pantryName.orEmpty()))
                // #302: the pantry's amount as written, beside the week's exact total; never compared.
                row.pantryQuantity?.let { have ->
                    Muted(
                        row.weekTotal?.let { stringResource(R.string.what_i_need_pantry_and_week, have, it) }
                            ?: stringResource(R.string.what_i_need_pantry_amount, have)
                    )
                }
            }
            NeedStatus.STAPLE -> Muted(stringResource(R.string.pantry_always_have))
            NeedStatus.BUY -> Unit
        }
        row.lines.forEachIndexed { i, line ->
            Text(line.text, style = MaterialTheme.typography.bodyMedium)
            if (recipeTags) {
                // The recipe it is for (#422) as a tag, as in Groceries; a tap opens it at the planned servings.
                RecipeTagRow(
                    tags = listOf(RecipeTag(line.recipeId, line.title, line.day)),
                    onOpen = { onOpenRecipe(line.recipeId, line.servings) },
                    testPrefix = "needTag-${row.name ?: i}-$i"
                )
            } else {
                val source = line.day?.let { stringResource(R.string.what_i_need_line_source, line.title, shortWeekday(it)) } ?: line.title
                Muted(source)
            }
        }
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
