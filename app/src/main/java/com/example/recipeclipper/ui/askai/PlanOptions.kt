package com.example.recipeclipper.ui.askai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.AskAiPrompt
import com.example.recipeclipper.ui.plan.DayStrip
import com.example.recipeclipper.ui.theme.Corners

/**
 * What "Plan my week" asks, once it is the chosen prompt (#392, #367): the meals as chips (the
 * cook's own types, in their order, Dinner at first, the last choice remembered), the day the
 * plan starts on (the same strip as Add to plan), where the recipes come from, and which
 * context goes in. Under the `planMyWeekPlus` flag; the ViewModel decides, this only shows it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanOptions(state: AskAiUiState, viewModel: AskAiViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("askAiPlan")) {
        Heading(stringResource(R.string.ask_ai_plan_meals))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.mealTypes.forEach { type ->
                ChoiceChip(
                    label = type.name,
                    selected = type.key in state.chosenMeals,
                    tag = "askAiMeal-${type.key}",
                    onClick = { viewModel.onMeal(type.key) }
                )
            }
        }
        if (state.chosenMeals.isEmpty()) {
            Note(stringResource(R.string.ask_ai_plan_meals_none), error = true, tag = "askAiMealsNone")
        }

        Heading(stringResource(R.string.ask_ai_plan_start))
        DayStrip(state.days, state.today, state.startDay, viewModel::onStartDay)

        Heading(stringResource(R.string.ask_ai_plan_source))
        Column(Modifier.selectableGroup()) {
            SourceRow(AskAiPrompt.PlanSource.NEW, R.string.ask_ai_plan_source_new, state, viewModel)
            SourceRow(AskAiPrompt.PlanSource.MINE, R.string.ask_ai_plan_source_mine, state, viewModel)
            SourceRow(AskAiPrompt.PlanSource.MIX, R.string.ask_ai_plan_source_mix, state, viewModel)
        }
        if (state.source == AskAiPrompt.PlanSource.MIX) {
            MixStepper(state.mix, state.planSlots, viewModel::onMix)
        }
        if (state.namesRecipes) {
            Heading(stringResource(R.string.ask_ai_plan_from))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(
                    label = stringResource(R.string.ask_ai_plan_all_recipes),
                    selected = state.listId == null,
                    tag = "askAiFromAll",
                    onClick = { viewModel.onList(null) }
                )
                state.planLists.forEach { list ->
                    ChoiceChip(
                        label = list.name,
                        selected = state.listId == list.id,
                        tag = "askAiFromList-${list.id}",
                        onClick = { viewModel.onList(list.id) }
                    )
                }
            }
            if (state.recipeCount > 0) {
                Note(
                    stringResource(R.string.ask_ai_plan_sent, state.recipesSent, state.recipeCount),
                    tag = "askAiPlanSent"
                )
            } else {
                Note(stringResource(R.string.ask_ai_plan_no_recipes), error = true, tag = "askAiPlanNoRecipes")
            }
        }
        if (state.source != AskAiPrompt.PlanSource.MINE) {
            Spacer(Modifier.height(4.dp))
            ToggleRow(stringResource(R.string.ask_ai_plan_use_favourites), state.useFavourites, "askAiUseFavourites", viewModel::onUseFavourites)
            ToggleRow(stringResource(R.string.ask_ai_plan_skip_cooked), state.skipCooked, "askAiSkipCooked", viewModel::onSkipCooked)
        }
        if (state.longAnswer) {
            Note(stringResource(R.string.ask_ai_plan_long), tag = "askAiPlanLong")
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun Note(text: String, tag: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp).testTag(tag)
    )
}

/** A multi-select chip: outlined, ringed in basil when chosen, read as a checkbox. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Surface(
        shape = Corners.Pill,
        color = Color.Transparent,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
            .testTag(tag)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun SourceRow(source: AskAiPrompt.PlanSource, name: Int, state: AskAiUiState, viewModel: AskAiViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = state.source == source, role = Role.RadioButton, onClick = { viewModel.onSource(source) })
            .padding(vertical = 4.dp)
            .testTag("askAiSource-${source.name}")
    ) {
        RadioButton(selected = state.source == source, onClick = null)
        Text(stringResource(name), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

/** "Up to 3 of mine, the rest new" with − and +, between 1 and the week's meals. */
@Composable
private fun MixStepper(mix: Int, slots: Int, onMix: (Int) -> Unit) {
    val less = stringResource(R.string.ask_ai_plan_mix_less)
    val more = stringResource(R.string.ask_ai_plan_mix_more)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 36.dp).testTag("askAiMix")) {
        FilledTonalIconButton(
            onClick = { onMix(mix - 1) },
            enabled = mix > 1,
            modifier = Modifier.semantics { contentDescription = less }.testTag("askAiMixLess")
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            stringResource(R.string.ask_ai_plan_mix_count, mix),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 120.dp).padding(horizontal = 8.dp).testTag("askAiMixCount")
        )
        FilledTonalIconButton(
            onClick = { onMix(mix + 1) },
            enabled = mix < slots,
            modifier = Modifier.semantics { contentDescription = more }.testTag("askAiMixMore")
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/** An independent choice: a switch, never a bare tick. */
@Composable
private fun ToggleRow(title: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp)
            .testTag(tag)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
