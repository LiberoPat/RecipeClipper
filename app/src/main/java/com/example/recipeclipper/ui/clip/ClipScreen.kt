package com.example.recipeclipper.ui.clip

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.ClipDraft
import com.example.recipeclipper.data.model.ClipField
import com.example.recipeclipper.data.model.SourceDomain
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Clip it yourself" (#37): the page with no recipe data, live, with a toolbar for saying where
 * the selected text goes, and a Review pane before saving. The page stays composed under Review
 * so going back to it keeps its scroll position and marks.
 */
@Composable
fun ClipScreen(
    onCancel: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: ClipViewModel = hiltViewModel(),
    loadPage: ClipPageLoader = LoadLiveUrl
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.savedRecipeId) {
        state.savedRecipeId?.let(onSaved)
    }

    BackHandler {
        if (state.reviewing) viewModel.onBackToPage() else onCancel()
    }

    val notice = state.notice
    val noticeText = notice?.let { noticeText(it.message) }
    val undoLabel = stringResource(R.string.action_undo)
    val discardLabel = stringResource(R.string.action_discard)
    LaunchedEffect(notice?.serial) {
        if (notice == null || noticeText == null) return@LaunchedEffect
        val action = when (notice.message) {
            is ClipMessage.Assigned, is ClipMessage.Cleared -> undoLabel
            ClipMessage.DraftRestored -> discardLabel
            ClipMessage.SaveFailed -> null
        }
        val result = snackbar.showSnackbar(noticeText, actionLabel = action, withDismissAction = false)
        if (result == SnackbarResult.ActionPerformed) {
            if (notice.message == ClipMessage.DraftRestored) viewModel.onDiscardDraft() else viewModel.onUndo()
        }
        viewModel.onNoticeShown(notice.serial)
    }

    val resources = LocalResources.current
    val syncState = remember(state.draft, state.newMarkId) {
        syncJson(state.draft, state.newMarkId) { field, count ->
            val label = resources.getString(field.labelRes)
            if (field == ClipField.NAME || count == 0) label
            else resources.getString(R.string.clip_tag_count, label, count)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopBar(
                    host = SourceDomain.of(state.url).orEmpty(),
                    canFinish = state.draft.canFinish,
                    onCancel = onCancel,
                    onDone = viewModel::onReview
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ClipWebPage(
                        url = state.url,
                        syncState = syncState,
                        pickingPhoto = state.pickingPhoto,
                        onEvent = { event ->
                            when (event) {
                                is ClipPageEvent.Selection -> viewModel.onSelectionChanged(event.text)
                                is ClipPageEvent.TagTapped -> viewModel.onTagTapped(event.field)
                                is ClipPageEvent.ImageTapped -> viewModel.onImageTapped(event.src)
                            }
                        },
                        loadPage = loadPage,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (state.reviewing) {
                        ReviewPane(state, viewModel, Modifier.fillMaxSize())
                    }
                }
                if (!state.reviewing) ClipToolbar(state, viewModel)
            }
            SnackbarHost(
                snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 140.dp)
            )
        }
    }
}

private val ClipField.labelRes: Int
    get() = when (this) {
        ClipField.NAME -> R.string.clip_field_name
        ClipField.INGREDIENTS -> R.string.clip_field_ingredients
        ClipField.STEPS -> R.string.clip_field_steps
        ClipField.PHOTO -> R.string.clip_field_photo
    }

/** The argument to `RC.sync`: the marks this draft holds, with their tags' labels. */
private fun syncJson(draft: ClipDraft, newMarkId: String?, label: (ClipField, Int) -> String): String {
    val marks = JSONArray()
    draft.marks.forEach { (field, id) ->
        if (field != ClipField.PHOTO) {
            marks.put(JSONObject().put("id", id).put("field", field.name).put("label", label(field, draft.count(field))))
        }
    }
    val json = JSONObject().put("marks", marks)
    newMarkId?.let { json.put("newId", it) }
    draft.photo?.let {
        json.put("photo", JSONObject().put("src", it).put("label", label(ClipField.PHOTO, 0)))
    }
    return json.toString()
}

@Composable
private fun noticeText(message: ClipMessage): String = when (message) {
    is ClipMessage.Assigned -> when (message.field) {
        ClipField.NAME -> stringResource(R.string.clip_added_name)
        ClipField.INGREDIENTS -> pluralStringResource(R.plurals.clip_added_ingredients, message.count, message.count)
        ClipField.STEPS -> pluralStringResource(R.plurals.clip_added_steps, message.count, message.count)
        ClipField.PHOTO -> stringResource(R.string.clip_added_photo)
    }
    is ClipMessage.Cleared -> stringResource(
        when (message.field) {
            ClipField.NAME -> R.string.clip_cleared_name
            ClipField.INGREDIENTS -> R.string.clip_cleared_ingredients
            ClipField.STEPS -> R.string.clip_cleared_steps
            ClipField.PHOTO -> R.string.clip_cleared_photo
        }
    )
    ClipMessage.DraftRestored -> stringResource(R.string.clip_draft_restored)
    ClipMessage.SaveFailed -> stringResource(R.string.clip_save_failed)
}

@Composable
private fun TopBar(host: String, canFinish: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        Text(
            host,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        TextButton(onClick = onDone, enabled = canFinish) { Text(stringResource(R.string.action_done)) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ClipToolbar(state: ClipUiState, viewModel: ClipViewModel) {
    val draft = state.draft
    val selecting = state.selection.isNotEmpty()
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        when {
            selecting -> {
                Text(
                    pluralStringResource(R.plurals.clip_lines_selected, state.selection.size, state.selection.size) +
                        stringResource(R.string.clip_summary_separator) + stringResource(R.string.clip_lines_selected_hint),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.selection.take(2).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            state.pickingPhoto -> Text(
                stringResource(R.string.clip_picking_photo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        summary(draft),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (draft.canFinish) {
                        TextButton(onClick = viewModel::onReview) { Text(stringResource(R.string.clip_review)) }
                    }
                }
                Text(
                    stringResource(R.string.clip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            // With a selection, a count shows what the field would hold: assigning replaces.
            val lines = state.selection.size
            FieldButton(stringResource(R.string.clip_field_name), selecting) { viewModel.onAssign(ClipField.NAME) }
            FieldButton(
                stringResource(R.string.clip_field_ingredients) + if (selecting) " $lines" else "",
                selecting
            ) { viewModel.onAssign(ClipField.INGREDIENTS) }
            FieldButton(
                stringResource(R.string.clip_field_steps) + if (selecting) " $lines" else "",
                selecting
            ) { viewModel.onAssign(ClipField.STEPS) }
            FieldButton(
                stringResource(R.string.clip_field_photo),
                enabled = true,
                selected = state.pickingPhoto,
                onClick = viewModel::onPhotoButton
            )
        }
    }
}

@Composable
private fun summary(draft: ClipDraft): String {
    val separator = stringResource(R.string.clip_summary_separator)
    val ingredients = draft.count(ClipField.INGREDIENTS)
    val steps = draft.count(ClipField.STEPS)
    return listOf(
        stringResource(if (draft.count(ClipField.NAME) > 0) R.string.clip_summary_name else R.string.clip_summary_no_name),
        pluralStringResource(R.plurals.clip_summary_ingredients, ingredients, ingredients),
        pluralStringResource(R.plurals.clip_summary_steps, steps, steps),
        stringResource(if (draft.photo != null) R.string.clip_summary_photo else R.string.clip_summary_no_photo)
    ).joinToString(separator)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.FieldButton(
    label: String,
    enabled: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val modifier = Modifier.weight(1f)
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    if (selected) {
        Button(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(10.dp), contentPadding = padding) {
            Text(label, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = RoundedCornerShape(10.dp),
            contentPadding = padding
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ReviewPane(state: ClipUiState, viewModel: ClipViewModel, modifier: Modifier) {
    val draft = state.draft
    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item("back") {
            TextButton(onClick = viewModel::onBackToPage) { Text(stringResource(R.string.clip_back_to_page)) }
        }
        item("title") {
            Text(stringResource(R.string.clip_review), style = MaterialTheme.typography.headlineMedium)
        }
        item("photo") {
            if (draft.photo != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = draft.photo, contentDescription = null, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.clip_photo_from_page), modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::onRemovePhoto) { Text(stringResource(R.string.action_remove)) }
                }
            } else {
                Text(
                    stringResource(R.string.clip_no_photo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item("name") {
            OutlinedTextField(
                value = draft.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.clip_field_name)) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item("serves") {
            OutlinedTextField(
                value = draft.serves,
                onValueChange = viewModel::onServesChange,
                label = { Text(stringResource(R.string.label_serves)) },
                placeholder = { Text(stringResource(R.string.clip_optional)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item("time") {
            OutlinedTextField(
                value = draft.totalTime,
                onValueChange = viewModel::onTotalTimeChange,
                label = { Text(stringResource(R.string.edit_label_total)) },
                placeholder = { Text(stringResource(R.string.clip_optional)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        lineSection(
            key = "ingredients",
            heading = { stringResource(R.string.clip_ingredients_heading, draft.count(ClipField.INGREDIENTS)) },
            lines = draft.ingredients,
            addLabel = { stringResource(R.string.clip_add_line) },
            onChange = { i, text -> viewModel.onLineChange(ClipField.INGREDIENTS, i, text) },
            onRemove = { viewModel.onLineRemove(ClipField.INGREDIENTS, it) },
            onAdd = { viewModel.onLineAdd(ClipField.INGREDIENTS) }
        )
        lineSection(
            key = "steps",
            heading = { stringResource(R.string.clip_steps_heading, draft.count(ClipField.STEPS)) },
            lines = draft.steps,
            addLabel = { stringResource(R.string.clip_add_step) },
            onChange = { i, text -> viewModel.onLineChange(ClipField.STEPS, i, text) },
            onRemove = { viewModel.onLineRemove(ClipField.STEPS, it) },
            onAdd = { viewModel.onLineAdd(ClipField.STEPS) }
        )
        item("save") {
            Button(
                onClick = viewModel::onSave,
                enabled = draft.canFinish && !state.saving,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors()
            ) { Text(stringResource(R.string.clip_save)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.lineSection(
    key: String,
    heading: @Composable () -> String,
    lines: List<String>,
    addLabel: @Composable () -> String,
    onChange: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit
) {
    item("$key-heading") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            Text(heading(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) { Text(addLabel()) }
        }
    }
    // Keyed by position: lines are edited in place, and a stable key per line would need ids
    // the draft doesn't have. Prefixed, so the two sections' keys never collide.
    itemsIndexed(lines, key = { index, _ -> "$key-$index" }) { index, line ->
        val removeDescription = stringResource(R.string.cd_clip_remove_line, index + 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = line,
                onValueChange = { onChange(index, it) },
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { onRemove(index) },
                modifier = Modifier.semantics { contentDescription = removeDescription }
            ) { Text(stringResource(R.string.clip_remove_line)) }
        }
    }
}
