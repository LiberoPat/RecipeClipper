package com.example.recipeclipper.ui.tour

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * The first-run welcome (#151): one card at a time, Skip at the top, Back and Next at the foot,
 * and on the last card "Try it with a sample recipe" or "Start". Full screen, with no tab bar.
 * The card scrolls, so the largest font sizes still reach every word.
 */
@Composable
fun WelcomeScreen(
    onDone: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    viewModel: WelcomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.exit) {
        when (val exit = state.exit) {
            null -> Unit
            WelcomeExit.Done -> {
                viewModel.onExitHandled()
                onDone()
            }
            is WelcomeExit.OpenRecipe -> {
                viewModel.onExitHandled()
                onOpenRecipe(exit.id)
            }
        }
    }
    // Back steps through the cards, and from the first one leaves, as Skip does.
    BackHandler { if (state.page > 0) viewModel.onPrevious() else viewModel.onDone() }

    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp)
                    .testTag("welcome")
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
                    if (!state.isLast) {
                        TextButton(onClick = viewModel::onDone) {
                            Text(
                                stringResource(R.string.welcome_skip),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 24.dp, bottom = 16.dp)
                ) {
                    Text(
                        stringResource(state.card.title()),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(Modifier.height(20.dp))
                    state.card.lines(state.chefMode).forEach { line ->
                        Text(stringResource(line), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(14.dp))
                    }
                }
                if (state.isLast) {
                    Button(
                        onClick = viewModel::onTrySample,
                        enabled = !state.opening,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.welcome_try_sample)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = viewModel::onDone,
                        enabled = !state.opening,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.welcome_start)) }
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 8.dp)
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (state.page > 0) {
                            TextButton(onClick = viewModel::onPrevious) {
                                Text(stringResource(R.string.welcome_back), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    PageDots(state.page, state.cards.size)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (!state.isLast) {
                            Button(onClick = viewModel::onNext, shape = RoundedCornerShape(12.dp)) {
                                Text(stringResource(R.string.welcome_next))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Where the cards stand: read as "Card 2 of 4", drawn as dots. */
@Composable
private fun PageDots(page: Int, count: Int) {
    val description = stringResource(R.string.welcome_page, page + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description }
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (index == page) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape
                    )
            )
        }
    }
}

private fun WelcomeCard.title(): Int = when (this) {
    WelcomeCard.APP -> R.string.welcome_app_title
    WelcomeCard.CLIP -> R.string.welcome_clip_title
    WelcomeCard.DAILY -> R.string.welcome_daily_title
    WelcomeCard.WEEKLY -> R.string.welcome_weekly_title
}

private fun WelcomeCard.lines(chefMode: Boolean): List<Int> = when (this) {
    WelcomeCard.APP -> listOf(R.string.welcome_app_body, R.string.welcome_app_offline)
    WelcomeCard.CLIP -> listOf(R.string.welcome_clip_share, R.string.welcome_clip_paste, R.string.welcome_clip_type)
    WelcomeCard.DAILY -> listOfNotNull(
        R.string.welcome_daily_servings, R.string.welcome_daily_lists, R.string.welcome_daily_cook,
        R.string.welcome_daily_chef.takeIf { chefMode }
    )
    WelcomeCard.WEEKLY -> listOf(
        R.string.welcome_weekly_plan, R.string.welcome_weekly_need,
        R.string.welcome_weekly_groceries, R.string.welcome_weekly_pantry
    )
}
