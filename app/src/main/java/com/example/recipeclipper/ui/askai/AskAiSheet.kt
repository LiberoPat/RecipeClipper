package com.example.recipeclipper.ui.askai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.AskAiPrompt
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.ui.edit.PasteTarget
import com.example.recipeclipper.ui.recipe.SectionHeading

/** The route's [AskAiViewModel], or null with the `askAi` flag off (#87): what every route that shows a recipe passes. */
@Composable
fun askAiViewModelIfOn(): AskAiViewModel? = if (LocalFlagValues.current.isOn(Flag.ASK_AI)) hiltViewModel() else null

/**
 * "Ask an AI" (#351, #353): the cook's own words, or a ready prompt filled from the pantry, the
 * favourites and what was cooked lately, then Copy prompt and Share (the system share sheet, so
 * any chat app). Nothing is sent from the app; the answer comes back as shared text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskAiSheet(
    onDismiss: () -> Unit,
    /** "Paste the answer" (#372): where the pasted text leads, the sheet already closed. */
    onOpenPasted: (PasteTarget) -> Unit = {},
    viewModel: AskAiViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val words = askAiWords(LocalResources.current)
    val context = LocalContext.current
    val title = stringResource(R.string.ask_ai_title)
    val dismiss = { viewModel.onReset(); onDismiss() }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .testTag("askAi")
        ) {
            SectionHeading(title)
            Text(
                stringResource(R.string.ask_ai_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.request,
                onValueChange = viewModel::onRequestChange,
                label = {
                    Text(
                        stringResource(
                            if (state.kind == AskAiKind.DESCRIBE) R.string.ask_ai_field else R.string.ask_ai_field_extra
                        )
                    )
                },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("askAiField")
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.selectableGroup()) {
                KindRow(
                    AskAiKind.DESCRIBE, state, stringResource(R.string.ask_ai_kind_describe),
                    stringResource(R.string.ask_ai_kind_describe_hint), enabled = true, viewModel::onKind
                )
                KindRow(
                    AskAiKind.WHAT_CAN_I_MAKE, state, stringResource(R.string.ask_ai_kind_make),
                    if (state.canAskWhatCanIMake) {
                        stringResource(R.string.ask_ai_kind_make_hint)
                    } else {
                        stringResource(R.string.ask_ai_kind_make_empty)
                    },
                    enabled = state.canAskWhatCanIMake, viewModel::onKind
                )
                KindRow(
                    AskAiKind.PLAN_MY_WEEK, state, stringResource(R.string.ask_ai_kind_plan),
                    stringResource(planHint(state)),
                    enabled = true, viewModel::onKind
                )
            }
            // The plan's own choices (#392, #367), under the flag, once it is the chosen prompt.
            if (state.plan && state.kind == AskAiKind.PLAN_MY_WEEK) PlanOptions(state, viewModel)
            Spacer(Modifier.height(16.dp))
            PromptButtons(
                enabled = state.canSend,
                copied = state.copied,
                onCopy = { viewModel.prompt(words)?.let { copyPrompt(context, title, it); viewModel.onCopied() } },
                onShare = { viewModel.prompt(words)?.let { sharePrompt(context, title, it) } }
            )
            // The answer comes back (#372): read from the clipboard once, on this tap, never on its own.
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    viewModel.onPasteAnswer(readClipboard(context)) { target -> dismiss(); onOpenPasted(target) }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("askAiPaste")
            ) {
                Text(stringResource(R.string.ask_ai_paste))
            }
            state.pasteProblem?.let { problem ->
                Text(
                    stringResource(
                        if (problem == PasteProblem.EMPTY) R.string.ask_ai_paste_empty else R.string.ask_ai_paste_none
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp).testTag("askAiPasteProblem")
                )
            }
        }
    }
}

/**
 * The recipe screen's "Change this recipe" (#353): a field for the request, then Copy prompt and
 * Share. The prompt carries [recipe] as schema.org JSON.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeRecipeSheet(recipe: Recipe, onDismiss: () -> Unit, viewModel: AskAiViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val words = askAiWords(LocalResources.current)
    val context = LocalContext.current
    val title = stringResource(R.string.change_recipe_title)
    val dismiss = { viewModel.onReset(); onDismiss() }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .testTag("changeRecipe")
        ) {
            SectionHeading(title)
            Text(
                stringResource(R.string.change_recipe_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.request,
                onValueChange = viewModel::onRequestChange,
                label = { Text(stringResource(R.string.change_recipe_field)) },
                placeholder = { Text(stringResource(R.string.change_recipe_placeholder)) },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("changeRecipeField")
            )
            Spacer(Modifier.height(16.dp))
            PromptButtons(
                enabled = state.canSendChange,
                copied = state.copied,
                onCopy = {
                    viewModel.changePrompt(recipe, words)?.let { copyPrompt(context, title, it); viewModel.onCopied() }
                },
                onShare = { viewModel.changePrompt(recipe, words)?.let { sharePrompt(context, recipe.name, it) } }
            )
        }
    }
}

/** The line under "Plan my week": what the week is made of, by the source chosen (#367); today's line without the flag. */
private fun planHint(state: AskAiUiState): Int = when {
    !state.plan -> R.string.ask_ai_kind_plan_hint
    state.source == AskAiPrompt.PlanSource.MINE -> R.string.ask_ai_plan_hint_mine
    state.source == AskAiPrompt.PlanSource.MIX -> R.string.ask_ai_plan_hint_mix
    else -> R.string.ask_ai_plan_hint_new
}

/** One prompt to choose: a radio row, its [hint] saying what it will carry. */
@Composable
private fun KindRow(
    kind: AskAiKind,
    state: AskAiUiState,
    name: String,
    hint: String,
    enabled: Boolean,
    onKind: (AskAiKind) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = state.kind == kind, enabled = enabled, role = Role.RadioButton, onClick = { onKind(kind) })
            .padding(vertical = 6.dp)
            .testTag("askAiKind-${kind.name}")
    ) {
        RadioButton(selected = state.kind == kind, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Copy prompt (filled, then "Copied") and Share, side by side. */
@Composable
private fun PromptButtons(enabled: Boolean, copied: Boolean, onCopy: () -> Unit, onShare: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onCopy,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).testTag("askAiCopy")
        ) {
            Text(stringResource(if (copied) R.string.ask_ai_copied else R.string.ask_ai_copy))
        }
        OutlinedButton(
            onClick = onShare,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).testTag("askAiShare")
        ) {
            Text(stringResource(R.string.ask_ai_share))
        }
    }
}

/** A platform effect, so it lives in the view layer: the clipboard's text, or null when it holds none. */
private fun readClipboard(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()

/** A platform effect, so it lives in the view layer: [text] to the clipboard. */
private fun copyPrompt(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** The system share sheet on [text], so any chat app can take it. */
private fun sharePrompt(context: Context, title: String, text: String) {
    ShareCompat.IntentBuilder(context)
        .setType("text/plain")
        .setText(text)
        .setChooserTitle(title)
        .startChooser()
}
