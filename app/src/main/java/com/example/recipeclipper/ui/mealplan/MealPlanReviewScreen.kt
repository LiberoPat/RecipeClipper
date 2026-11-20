package com.example.recipeclipper.ui.mealplan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.ui.common.noticeMessage
import com.example.recipeclipper.ui.plan.dayTitle
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * The review of a week of recipes shared in (#352): each recipe ticked, with the day it will be
 * planned on to change. Save keeps the ticked ones; [onFinished] opens Week when any was planned
 * (`true`), else goes back. Back discards: nothing is saved before Save.
 */
@Composable
fun MealPlanReviewScreen(
    onBack: () -> Unit,
    onFinished: (planned: Boolean) -> Unit,
    onOpenWeek: () -> Unit = { onFinished(true) },
    viewModel: MealPlanReviewViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val words = cutOffWords()
    val copyLabel = stringResource(R.string.cutoff_copy_action)

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished(state.planned > 0)
    }

    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    BackButton(onBack)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = viewModel::onSave,
                        enabled = !state.loading && !state.saving && state.savingCount > 0,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("mealplan_save")
                    ) { Text(stringResource(R.string.action_save)) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.mealplan_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (state.meals) R.string.mealplan_intro_meals else R.string.mealplan_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.notFound > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mealplan_not_found, state.notFound),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("mealplan_not_found")
                    )
                }
                if (state.cutOff) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mealplan_cut_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("mealplan_cut_off")
                    )
                    CutOffCopy(state.copied) {
                        copyToClipboard(context, copyLabel, viewModel.followUp(words))
                        viewModel.onCopied()
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (state.kept > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.mealplan_kept, state.kept),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).testTag("mealplan_kept")
                        )
                        if (state.planned > 0) TextButton(onClick = onOpenWeek) { Text(stringResource(R.string.mealplan_open_week)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (state.saveFailed) Message(stringResource(R.string.edit_error_save_failed))
                if (state.overLimit) LimitNotice(state, viewModel::onUnlock)
                when {
                    state.loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    else -> {
                        state.items.forEach { item ->
                            ReviewRow(item, state.days, state.meals, state.mealTypes, viewModel::onTick, viewModel::onDay, viewModel::onMeal)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.mealplan_keeping, state.tickedCount, state.items.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("mealplan_count")
                        )
                    }
                }
            }
        }
    }
}

/** The free library has room for only some of what is ticked: how many, and Unlock (#107). Never removes anything. */
@Composable
private fun LimitNotice(state: MealPlanReviewUiState, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("mealplan_limit")) {
        Text(
            stringResource(R.string.mealplan_room, state.room ?: 0),
            style = MaterialTheme.typography.bodyMedium
        )
        state.unlockNotice?.let {
            Text(stringResource(it.noticeMessage()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = onUnlock) { Text(stringResource(R.string.unlock)) }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ReviewRow(
    item: ReviewItem,
    days: List<Long>,
    meals: Boolean,
    mealTypes: List<MealType>,
    onTick: (Int) -> Unit,
    onDay: (Int, Long?) -> Unit,
    onMeal: (Int, Long) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("mealplan_row_${item.index}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .toggleable(value = item.ticked, role = Role.Checkbox, onValueChange = { onTick(item.index) }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = item.ticked, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp, top = 8.dp, bottom = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.existing) {
                        Text(
                            stringResource(R.string.mealplan_already),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.testTag("mealplan_already_${item.index}")
                        )
                    } else if (item.preview.isNotEmpty()) {
                        Text(
                            item.preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            DayPicker(item, days, onDay)
        }
        if (meals && mealTypes.isNotEmpty()) {
            Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                MealPicker(item, mealTypes, onMeal)
            }
        }
        if (item.existing && item.ticked && item.day == null) {
            Text(
                stringResource(R.string.mealplan_pick_day),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp).testTag("mealplan_pick_day_${item.index}")
            )
        }
        if (item.passed && item.day == null) {
            Text(
                stringResource(R.string.mealplan_day_passed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp)
            )
        }
        if (item.ticked && !item.fits) {
            Text(
                stringResource(R.string.mealplan_wont_fit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 40.dp).testTag("mealplan_wont_fit_${item.index}")
            )
        }
    }
}

/** A row's day, a button over a menu: today and the next 13 days, the answer's own later day, or no day. */
@Composable
private fun DayPicker(item: ReviewItem, days: List<Long>, onDay: (Int, Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = item.day?.let(::dayTitle) ?: stringResource(R.string.mealplan_day_none)
    val description = stringResource(R.string.mealplan_day_for, item.name)
    Column {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.testTag("mealplan_day_${item.index}").semantics { contentDescription = "$description: $label" }
        ) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val options = (days + listOfNotNull(item.day)).distinct().sorted()
            options.forEach { day ->
                DropdownMenuItem(text = { Text(dayTitle(day)) }, onClick = { onDay(item.index, day); open = false })
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mealplan_day_dont_plan)) },
                onClick = { onDay(item.index, null); open = false }
            )
        }
    }
}

/** A row's meal, a button over a menu of the cook's meal types (#392): the one it is planned under. */
@Composable
private fun MealPicker(item: ReviewItem, types: List<MealType>, onMeal: (Int, Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = types.firstOrNull { it.id == item.mealTypeId }?.name ?: types.first().name
    val description = stringResource(R.string.mealplan_meal_for, item.name)
    Column {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.testTag("mealplan_meal_${item.index}").semantics { contentDescription = "$description: $label" }
        ) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            types.forEach { type ->
                DropdownMenuItem(text = { Text(type.name) }, onClick = { onMeal(item.index, type.id); open = false })
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(12.dp))
}
