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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.common.LibraryFullDialog
import com.example.recipeclipper.ui.common.noticeMessage
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
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
            is ClipMessage.Removed -> undoLabel
            ClipMessage.DraftRestored -> discardLabel
            ClipMessage.SaveFailed, ClipMessage.PhotoUnreadable, is ClipMessage.Unlock, is ClipMessage.Missing,
            ClipMessage.TextUnreadable -> null
        }
        // Long, not the Indefinite an action would get by default: a notice that never went
        // ("Photo added" long after the photo) read as the clip being stuck on that step.
        val result = snackbar.showSnackbar(
            noticeText,
            actionLabel = action,
            withDismissAction = false,
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) {
            if (notice.message == ClipMessage.DraftRestored) viewModel.onDiscardDraft() else viewModel.onUndo()
        }
        viewModel.onNoticeShown(notice.serial)
    }

    val resources = LocalResources.current
    val tagLabel = { field: ClipField, count: Int ->
        val label = resources.getString(field.labelRes)
        if (field.replaces) label else resources.getString(R.string.clip_tag_count, label, count)
    }
    // The new mark, and taps that select, go to the view showing: the page, or the Text view.
    val pageSync = remember(state.draft, state.newMarkId, state.showingText, state.armed, state.clearSelection) {
        syncJson(
            state.draft,
            newMarkId = state.newMarkId.takeUnless { state.showingText },
            armed = state.armedText.takeUnless { state.showingText },
            clear = state.clearSelection,
            label = tagLabel
        )
    }
    val textSync = remember(state.draft, state.newMarkId, state.showingText, state.armed, state.clearSelection) {
        syncJson(
            state.draft,
            newMarkId = state.newMarkId.takeIf { state.showingText },
            armed = state.armedText.takeIf { state.showingText },
            clear = state.clearSelection,
            label = tagLabel
        )
    }
    val textPage = state.pageText?.let { text ->
        remember(text) {
            RedditTextPage.html(
                text,
                commentsHeading = resources.getString(R.string.clip_text_comments),
                loadedNote = resources.getString(R.string.clip_text_loaded_note),
                author = { resources.getString(R.string.clip_text_author, it) }
            )
        }
    }
    val onPageEvent: (ClipPageEvent) -> Unit = { event ->
        when (event) {
            is ClipPageEvent.Selection -> viewModel.onSelectionChanged(event.text)
            is ClipPageEvent.TagTapped -> viewModel.onTagTapped(event.markId)
            is ClipPageEvent.ImageTapped -> viewModel.onImageTapped(event.src)
            ClipPageEvent.NoImage -> viewModel.onNoImageTapped()
            is ClipPageEvent.PageLoaded -> viewModel.onPageLoaded(event.html)
            is ClipPageEvent.PageText -> viewModel.onPageText(event.html)
        }
    }

    // Its own theme, like every screen: without it the clip showed Material purple.
    RecipeClipperTheme {
        if (state.libraryFull) {
            LibraryFullDialog(onUnlock = viewModel::onUnlock, onDismiss = viewModel::onLibraryFullDismiss)
        }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val waiting = state.check == ClipCheck.WAITING
                    TopBar(
                        host = SourceDomain.of(state.pageUrl).orEmpty(),
                        reviewing = state.reviewing,
                        enabled = !waiting && !state.saving,
                        textToggle = when {
                            !state.offersText || state.reviewing || waiting -> null
                            state.showingText -> TextToggle(R.string.clip_show_page, viewModel::onShowPage)
                            else -> TextToggle(R.string.clip_show_text, viewModel::onShowText)
                        },
                        onCancel = onCancel,
                        onDone = viewModel::onReview,
                        onSave = viewModel::onSave
                    )
                    if (state.readBlocked && !state.reviewing) Note(R.string.clip_reddit_blocked_note)
                    when (state.check) {
                        ClipCheck.WAITING -> Note(R.string.clip_human_check_note)
                        ClipCheck.NO_RECIPE -> if (!state.reviewing) Note(R.string.clip_human_check_no_recipe_note)
                        null -> Unit
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        ClipWebPage(
                            url = state.pageUrl,
                            syncState = pageSync,
                            pickingPhoto = state.pickingPhoto,
                            onEvent = onPageEvent,
                            loadPage = loadPage,
                            readsPage = waiting,
                            readText = state.readingText,
                            modifier = Modifier.fillMaxSize()
                        )
                        // The Text view (#213) lies over the page, which stays loaded (its scroll
                        // and marks) under it. Hidden, it keeps its own: full size, but not drawn
                        // and under the page (#237: shrunk to no size, its last frame stayed over
                        // the page, which then looked dead).
                        if (textPage != null) {
                            key(textPage) {
                                ClipWebPage(
                                    url = state.pageUrl,
                                    syncState = textSync,
                                    pickingPhoto = false,
                                    onEvent = onPageEvent,
                                    loadPage = loadTextPage(textPage),
                                    visible = state.showingText,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(if (state.showingText) 1f else -1f)
                                        .then(if (state.showingText) Modifier.testTag("clip.text") else Modifier)
                                )
                            }
                        }
                        if (state.reviewing) {
                            ReviewPane(state, viewModel, Modifier.fillMaxSize().zIndex(2f))
                        }
                    }
                    // Nothing to clip while the page is Cloudflare's check (#220).
                    if (!state.reviewing && !waiting) ClipToolbar(state, viewModel)
                }
                SnackbarHost(
                    snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 140.dp)
                )
            }
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

/**
 * The argument to `RC.sync`: the marks this draft holds, each add with its own tag ("Ingredients
 * · 3"), the field the page's taps select for ([armed], #237) and the clear count.
 */
private fun syncJson(
    draft: ClipDraft,
    newMarkId: String?,
    armed: ClipField?,
    clear: Int,
    label: (ClipField, Int) -> String
): String {
    val marks = JSONArray()
    draft.marks.forEach { mark ->
        if (mark.field != ClipField.PHOTO) {
            marks.put(
                JSONObject().put("id", mark.id).put("field", mark.field.name)
                    .put("label", label(mark.field, mark.lines.size))
            )
        }
    }
    val json = JSONObject().put("marks", marks).put("armed", armed?.name ?: JSONObject.NULL).put("clear", clear)
    newMarkId?.let { json.put("newId", it) }
    draft.photo?.let { src ->
        val id = draft.marks.lastOrNull { it.field == ClipField.PHOTO }?.id.orEmpty()
        json.put("photo", JSONObject().put("src", src).put("id", id).put("label", label(ClipField.PHOTO, 1)))
    }
    return json.toString()
}

@Composable
private fun noticeText(message: ClipMessage): String = when (message) {
    is ClipMessage.Removed -> when (message.field) {
        ClipField.NAME -> stringResource(R.string.clip_cleared_name)
        ClipField.INGREDIENTS -> pluralStringResource(R.plurals.clip_removed_ingredients, message.count, message.count)
        ClipField.STEPS -> pluralStringResource(R.plurals.clip_removed_steps, message.count, message.count)
        ClipField.PHOTO -> stringResource(R.string.clip_cleared_photo)
    }
    ClipMessage.DraftRestored -> stringResource(R.string.clip_draft_restored)
    ClipMessage.SaveFailed -> stringResource(R.string.clip_save_failed)
    ClipMessage.PhotoUnreadable -> stringResource(R.string.clip_photo_unreadable)
    is ClipMessage.Unlock -> stringResource(message.outcome.noticeMessage())
    is ClipMessage.Missing -> stringResource(
        when {
            message.name && message.lines -> R.string.clip_needs_name_and_lines
            message.name -> R.string.clip_needs_name
            else -> R.string.clip_needs_lines
        }
    )
    ClipMessage.TextUnreadable -> stringResource(R.string.clip_text_unreadable)
}

/**
 * Why the clip opened by itself: Reddit wouldn't let the app read the post (#213), or the site
 * wants the cook to pass Cloudflare's check (#220), or did and has no recipe data. Announced
 * politely, since the cook asked for the recipe, not for this screen.
 */
@Composable
private fun Note(text: Int) {
    Text(
        stringResource(text),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The top bar's switch between the page and the Text view (#213). */
private class TextToggle(val label: Int, val onClick: () -> Unit)

/**
 * Done opens Review; in Review it is Save. Either is always tappable (bar Cloudflare's check and
 * a save under way): what's missing is said, never shown only as a greyed-out button.
 */
@Composable
private fun TopBar(
    host: String,
    reviewing: Boolean,
    enabled: Boolean,
    textToggle: TextToggle?,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onSave: () -> Unit
) {
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
        textToggle?.let { TextButton(onClick = it.onClick) { Text(stringResource(it.label)) } }
        if (reviewing) {
            TextButton(onClick = onSave, enabled = enabled) { Text(stringResource(R.string.action_save)) }
        } else {
            TextButton(onClick = onDone, enabled = enabled) { Text(stringResource(R.string.action_done)) }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * Field first (#237): the hint bar (what to do now) over one button per field. A field button
 * arms its field (filled while armed) and shows what it holds (a check and a count).
 */
@Composable
private fun ClipToolbar(state: ClipUiState, viewModel: ClipViewModel) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        HintBar(state, viewModel)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            ClipField.entries.forEach { field ->
                FieldButton(field, armed = state.armed == field, count = state.draft.count(field)) {
                    viewModel.onFieldButton(field)
                }
            }
        }
    }
}

@Composable
private fun HintBar(state: ClipUiState, viewModel: ClipViewModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.Center
    ) {
        when (val hint = state.hint) {
            // The photo is optional: Skip leaves this step without tapping the page.
            ClipHint.PickPhoto -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.clip_picking_photo),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = viewModel::onSkipPhoto) { Text(stringResource(R.string.clip_skip_photo)) }
            }
            is ClipHint.Select -> Text(
                stringResource(
                    when (hint.field) {
                        ClipField.NAME -> R.string.clip_select_name
                        ClipField.INGREDIENTS -> R.string.clip_select_ingredients
                        else -> R.string.clip_select_steps
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            is ClipHint.Confirm -> {
                state.selection.firstOrNull()?.let { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = viewModel::onConfirm,
                        modifier = Modifier.weight(1f).testTag("clip.confirm"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            when (hint.field) {
                                ClipField.NAME -> stringResource(R.string.clip_confirm_name)
                                ClipField.INGREDIENTS ->
                                    pluralStringResource(R.plurals.clip_confirm_ingredients, hint.lines, hint.lines)
                                else -> pluralStringResource(R.plurals.clip_confirm_steps, hint.lines, hint.lines)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = viewModel::onClearSelection) { Text(stringResource(R.string.action_clear)) }
                }
            }
            is ClipHint.Selected -> {
                Text(
                    pluralStringResource(R.plurals.clip_lines_selected, hint.lines, hint.lines) +
                        stringResource(R.string.clip_summary_separator) + stringResource(R.string.clip_selected_hint),
                    style = MaterialTheme.typography.labelLarge,
                    color = muted
                )
                state.selection.firstOrNull()?.let { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            is ClipHint.Next -> Row(verticalAlignment = Alignment.CenterVertically) {
                val photo = state.draft.photo
                if (hint.added?.field == ClipField.PHOTO && photo != null) {
                    AsyncImage(
                        model = photo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    listOfNotNull(hint.added?.let { addedText(it) }, nextText(hint.next, state.draft.isEmpty))
                        .joinToString(" "),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (hint.added != null) {
                    TextButton(onClick = viewModel::onUndo) { Text(stringResource(R.string.action_undo)) }
                }
            }
        }
    }
}

@Composable
private fun addedText(added: ClipAdded): String = when (added.field) {
    ClipField.NAME -> stringResource(R.string.clip_added_name)
    ClipField.INGREDIENTS -> pluralStringResource(R.plurals.clip_added_ingredients, added.count, added.count)
    ClipField.STEPS -> pluralStringResource(R.plurals.clip_added_steps, added.count, added.count)
    ClipField.PHOTO -> stringResource(R.string.clip_added_photo)
} + "."

@Composable
private fun nextText(next: ClipField?, empty: Boolean): String = stringResource(
    when (next) {
        ClipField.NAME -> if (empty) R.string.clip_hint_start else R.string.clip_next_name
        ClipField.INGREDIENTS -> R.string.clip_next_ingredients
        ClipField.STEPS -> R.string.clip_next_steps
        ClipField.PHOTO -> R.string.clip_next_photo
        null -> R.string.clip_ready
    }
)

@Composable
private fun androidx.compose.foundation.layout.RowScope.FieldButton(
    field: ClipField,
    armed: Boolean,
    count: Int,
    onClick: () -> Unit
) {
    val modifier = Modifier
        .weight(1f)
        .testTag("clip.field.${field.name}")
        .semantics { selected = armed }
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 6.dp)
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(field.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // What the field holds: a check, with the count for ingredients and steps.
            Text(
                when {
                    count == 0 -> ""
                    field.replaces -> stringResource(R.string.clip_filled)
                    else -> stringResource(R.string.clip_filled_count, count)
                },
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
    if (armed) {
        Button(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(10.dp), contentPadding = padding) {
            content()
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(10.dp), contentPadding = padding) {
            content()
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
                enabled = !state.saving,
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
