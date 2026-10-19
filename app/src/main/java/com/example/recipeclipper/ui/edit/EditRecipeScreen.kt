package com.example.recipeclipper.ui.edit

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.recipeclipper.data.PhotoPost
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.remote.LineFlag
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

    RecipeClipperTheme {
        if (state.libraryFull) {
            LibraryFullDialog(onUnlock = viewModel::onUnlock, onDismiss = viewModel::onLibraryFullDismiss)
        }
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
                        enabled = !state.loading && !state.saving && !state.missing && !state.reading,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.action_save)) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(
                        when {
                            state.photo != null -> R.string.photo_title
                            state.isNew -> R.string.edit_title_new
                            else -> R.string.edit_title_edit
                        }
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(Modifier.height(16.dp))
                state.photo?.let { PhotoReview(it, state, viewModel::onReadAgain, viewModel::onFraction) }
                when {
                    state.loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    state.missing -> Text(
                        stringResource(R.string.error_not_saved),
                        color = MaterialTheme.colorScheme.error
                    )
                    state.reading -> Unit // PhotoReview shows the progress
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

/**
 * "Read the photo" (#198), above the fields: the post's pictures to check the lines against,
 * how the reading went, and the lines the recogniser was unsure of ("Check these lines"). A scan
 * (#226) shows the cook's pages the same way, each named "Page 1 of 2" for TalkBack, and read
 * from where they are, never cached (a reused camera file must not show an earlier picture).
 */
@Composable
private fun PhotoReview(
    post: PhotoPost,
    state: EditRecipeUiState,
    onReadAgain: () -> Unit,
    onFraction: (flag: LineFlag, fraction: String) -> Unit
) {
    val context = LocalContext.current
    post.imageUrls.forEachIndexed { index, image ->
        AsyncImage(
            model = if (state.scan) {
                remember(image) {
                    ImageRequest.Builder(context).data(image)
                        .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
                }
            } else image,
            contentDescription = if (state.scan) {
                stringResource(R.string.scan_page_description, index + 1, post.imageUrls.size)
            } else stringResource(R.string.photo_image_description, post.title),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.height(12.dp))
    }
    when (state.photoOutcome) {
        null -> if (state.reading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.photo_reading), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
        PhotoOutcome.READ -> Note(stringResource(R.string.photo_read))
        PhotoOutcome.NOT_SORTED -> Note(stringResource(R.string.photo_not_sorted))
        PhotoOutcome.FAILED, PhotoOutcome.NOT_READY -> {
            Message(
                stringResource(
                    when {
                        state.photoOutcome == PhotoOutcome.NOT_READY -> R.string.photo_not_ready
                        state.scan -> R.string.scan_failed
                        else -> R.string.photo_failed
                    }
                )
            )
            OutlinedButton(onClick = onReadAgain, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.action_try_again))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (state.flags.isNotEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                .padding(14.dp)
                .semantics(mergeDescendants = true) {}
        ) {
            if (state.checkHeading) {
                Text(
                    stringResource(R.string.photo_check_heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
            }
            state.flags.forEachIndexed { index, flag ->
                Text("• ${flag.shown}", style = MaterialTheme.typography.bodyMedium)
                if (flag.largeOnly) LineNote(stringResource(R.string.photo_check_large), bottom = 4.dp)
                if (flag.chips) FractionChips(index, flag, state.fractions, onFraction)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * A line whose amount holds a fraction glyph's look-alike (#245), or glyphs run together (#276,
 * "½⅓"): the fractions as one-tap
 * replacements, since "%" can't say whether it was ½, ¼ or ¾. The cook can still type instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FractionChips(index: Int, flag: LineFlag, fractions: List<String>, onFraction: (LineFlag, String) -> Unit) {
    LineNote(stringResource(R.string.photo_fraction_prompt))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
    ) {
        fractions.forEach { fraction ->
            val spoken = stringResource(R.string.photo_fraction_chip, fraction, flag.shown)
            OutlinedButton(
                onClick = { onFraction(flag, fraction) },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier
                    .semantics { contentDescription = spoken }
                    .testTag("edit.fraction.$index.$fraction")
            ) {
                Text(fraction, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
}

/** A muted line under a line to check: why it's listed (#281), or what its chips are for (#245). */
@Composable
private fun LineNote(text: String, bottom: Dp = 0.dp) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = bottom)
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
