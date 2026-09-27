package com.example.recipeclipper.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.example.recipeclipper.ui.common.LibraryFullDialog
import com.example.recipeclipper.ui.common.noticeMessage
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * Edit a recipe's text, or type one in (#29): name, yield, times, ingredients and steps one
 * per line, and an optional photo link. Saving opens the recipe via [onSaved]; Back discards.
 */
@Composable
fun EditRecipeScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: EditRecipeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedId) {
        state.savedId?.let(onSaved)
    }
    if (state.libraryFull) {
        LibraryFullDialog(onUnlock = viewModel::onUnlock, onDismiss = viewModel::onLibraryFullDismiss)
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
                        enabled = !state.loading && !state.saving && !state.missing,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.action_save)) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(if (state.isNew) R.string.edit_title_new else R.string.edit_title_edit),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(16.dp))
                when {
                    state.loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    state.missing -> Text(
                        stringResource(R.string.error_not_saved),
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> EditFields(state, viewModel::onDraftChange)
                }
            }
        }
    }
}

@Composable
private fun EditFields(state: EditRecipeUiState, onChange: (RecipeDraft) -> Unit) {
    val draft = state.draft
    if (state.showInvalid && !draft.isValid) {
        Message(stringResource(R.string.edit_error_invalid))
    }
    if (state.saveFailed) {
        Message(stringResource(R.string.edit_error_save_failed))
    }
    state.unlockNotice?.let { Message(stringResource(it.noticeMessage())) }
    Field(stringResource(R.string.edit_label_name), draft.name, { onChange(draft.copy(name = it)) })
    Field(stringResource(R.string.edit_label_yield), draft.yield, { onChange(draft.copy(yield = it)) })
    Field(stringResource(R.string.edit_label_prep), draft.prepTime, { onChange(draft.copy(prepTime = it)) })
    Field(stringResource(R.string.edit_label_cook), draft.cookTime, { onChange(draft.copy(cookTime = it)) })
    Field(stringResource(R.string.edit_label_total), draft.totalTime, { onChange(draft.copy(totalTime = it)) })
    Field(
        stringResource(R.string.edit_label_ingredients),
        draft.ingredientsText,
        { onChange(draft.copy(ingredientsText = it)) },
        multiLine = true
    )
    Field(
        stringResource(R.string.edit_label_steps),
        draft.instructionsText,
        { onChange(draft.copy(instructionsText = it)) },
        multiLine = true
    )
    Field(
        stringResource(R.string.edit_label_photo),
        draft.image,
        { onChange(draft.copy(image = it)) },
        keyboardType = KeyboardType.Uri
    )
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    multiLine: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = !multiLine,
        minLines = if (multiLine) 5 else 1,
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboardType == KeyboardType.Uri) KeyboardCapitalization.None
            else KeyboardCapitalization.Sentences,
            keyboardType = keyboardType,
            imeAction = if (multiLine) ImeAction.Default else ImeAction.Next
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(bottom = 12.dp)
    )
}
