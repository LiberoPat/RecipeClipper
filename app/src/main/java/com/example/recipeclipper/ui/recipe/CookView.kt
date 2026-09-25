package com.example.recipeclipper.ui.recipe

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.StepAmounts
import com.example.recipeclipper.data.model.StepTimers

private enum class StepStatus { DONE, CURRENT, UPCOMING }

/**
 * A highlighted scroll, not a pager: cooking isn't linear, so the next step has to be
 * glanceable before it is current, and a scroll still works when a source gives three
 * paragraph-blobs instead of twelve clean steps.
 */
@Composable
internal fun CookView(
    content: RecipeContent.Success,
    state: RecipeUiState,
    actions: RecipeActions
) {
    val cook = state.cook
    val steps = content.instructions
    val listState = rememberLazyListState()

    KeepScreenOn()
    BackHandler(onBack = actions.onCookExit)
    LaunchedEffect(cook.currentStep) {
        if (steps.isNotEmpty()) listState.animateScrollToItem(cook.currentStep.coerceIn(0, steps.lastIndex))
    }

    Column(Modifier.fillMaxSize()) {
        CookTopBar(
            title = content.recipe.name,
            position = stringResource(R.string.cook_position, cook.currentStep + 1, steps.size),
            onExit = actions.onCookExit
        )
        IngredientsBar(content, state, actions)

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            itemsIndexed(steps) { index, text ->
                val status = when {
                    index == cook.currentStep -> StepStatus.CURRENT
                    index in cook.doneSteps -> StepStatus.DONE
                    else -> StepStatus.UPCOMING
                }
                CookStep(
                    index = index,
                    text = text,
                    amounts = content.stepAmounts?.getOrNull(index),
                    status = status,
                    timerSeconds = content.stepTimerSeconds.getOrNull(index),
                    // A step has a timer only when the app has the recipe's words.
                    timerWords = content.words ?: LanguageWords.ENGLISH,
                    timer = cook.timers[index],
                    isLast = index == steps.lastIndex,
                    actions = actions
                )
            }
        }
    }
}

@Composable
private fun CookTopBar(title: String, position: String, onExit: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp)
    ) {
        TextButton(onClick = onExit) {
            Text(
                stringResource(R.string.action_exit),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Text(
            position,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Ingredients stay one tap away, folded up so the steps own the screen. */
@Composable
private fun IngredientsBar(
    content: RecipeContent.Success,
    state: RecipeUiState,
    actions: RecipeActions
) {
    val expanded = state.cook.ingredientsExpanded
    Column {
        Hairline()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(
                        if (expanded) R.string.cd_hide_ingredients else R.string.cd_show_ingredients
                    ),
                    onClick = actions.onIngredientsToggle
                )
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(stringResource(R.string.heading_ingredients), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.cook_ingredients_count, content.ingredients.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "▴" else "▾",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                content.ingredients.forEachIndexed { index, ingredient ->
                    IngredientRow(
                        text = ingredient,
                        checked = index in state.checkedIngredients,
                        onCheckedChange = { actions.onIngredientChecked(index, it) }
                    )
                }
            }
        }
        Hairline()
    }
}

@Composable
private fun CookStep(
    index: Int,
    text: String,
    amounts: List<StepAmounts.Part>?,
    status: StepStatus,
    timerSeconds: Int?,
    timerWords: LanguageWords,
    timer: StepTimer?,
    isLast: Boolean,
    actions: RecipeActions
) {
    val colors = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyLarge

    if (status == StepStatus.CURRENT) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(BorderStroke(2.dp, colors.primary), RoundedCornerShape(18.dp))
                .padding(20.dp)
        ) {
            Text(
                stringResource(R.string.cook_step_label, index + 1),
                style = MaterialTheme.typography.labelSmall,
                color = colors.tertiary
            )
            Spacer(Modifier.height(8.dp))
            Text(stepText(text, amounts, colors.tertiary), style = body.copy(fontSize = 21.sp, lineHeight = 30.sp))
            if (timerSeconds != null || timer != null) {
                Spacer(Modifier.height(16.dp))
                CurrentTimer(index, timerSeconds, timerWords, timer, actions)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = actions.onStepDone,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(
                    stringResource(if (isLast) R.string.cook_done_finish else R.string.cook_done_next),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        return
    }

    val done = status == StepStatus.DONE
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_go_to_step, index + 1)) {
                actions.onStepSelected(index)
            }
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .alpha(if (done) 0.45f else 1f)
    ) {
        Row {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.titleMedium,
                color = if (done) colors.onSurfaceVariant else colors.tertiary,
                modifier = Modifier.width(32.dp)
            )
            Text(
                // A done step is dimmed as a whole; its amounts keep only their weight.
                stepText(text, amounts, if (done) null else colors.tertiary),
                style = body.copy(
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None
                ),
                color = colors.onSurfaceVariant
            )
        }
        // A timer still running on a step you've moved on from stays visible: steps overlap.
        if (timer != null) {
            val clockOrDone = if (timer.finished) stringResource(R.string.timers_up)
            else StepTimers.clock(timer.remainingSeconds)
            Text(
                stringResource(R.string.timer_running, clockOrDone),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = colors.tertiary,
                modifier = Modifier.padding(start = 32.dp, top = 4.dp)
            )
        }
    }
}

@Composable
private fun CurrentTimer(
    step: Int,
    timerSeconds: Int?,
    timerWords: LanguageWords,
    timer: StepTimer?,
    actions: RecipeActions
) {
    val colors = MaterialTheme.colorScheme
    if (timer == null) {
        if (timerSeconds == null) return
        OutlinedButton(
            onClick = { actions.onTimerStart(step) },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, colors.outline)
        ) {
            Text(
                stringResource(R.string.timer_start, StepTimers.label(timerSeconds, timerWords)),
                style = MaterialTheme.typography.labelLarge
            )
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                StepTimers.clock(timer.remainingSeconds),
                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                color = if (timer.finished) colors.tertiary else colors.onSurface
            )
            if (timer.finished) {
                Text(
                    stringResource(R.string.timers_up),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.tertiary
                )
            }
        }
        if (!timer.finished) {
            TextButton(onClick = { actions.onTimerToggle(step) }) {
                Text(
                    stringResource(if (timer.running) R.string.action_pause else R.string.action_resume),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.tertiary
                )
            }
        }
        TextButton(onClick = { actions.onTimerReset(step) }) {
            Text(
                stringResource(R.string.action_reset),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = colors.onSurfaceVariant
            )
        }
    }
}

/** Cook mode holds the screen awake: nobody wants it dimming with flour on their hands. */
@Composable
private fun KeepScreenOn() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
