package com.example.recipeclipper.ui.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.ClipField

/**
 * The Text view (#213), line by line since #286: the post's lines as rows, each with a checkbox.
 * A ticked line is washed in paprika; the ticked lines are the selection, so the dock counts them
 * and a field takes them (armed, through the confirm; or a field's button straight away). A line
 * an add took keeps a paprika bar, and the add's first line its tag, which takes the add back,
 * as on the page. The rows and ticks are the ViewModel's, so they survive rotation.
 */
@Composable
internal fun ClipTextView(
    state: ClipUiState,
    listState: LazyListState,
    tagLabel: (ClipField, Int) -> String,
    onLine: (Int) -> Unit,
    onTag: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val marks = state.textMarks
    LazyColumn(
        state = listState,
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        items(state.textRows, key = { it.key }) { row ->
            when (row) {
                is ClipTextRow.Line -> LineRow(row, row.index in state.ticked, marks[row.index], tagLabel, onLine, onTag)
                ClipTextRow.Comments -> CommentsHeading()
                is ClipTextRow.Author -> Text(
                    stringResource(R.string.clip_text_author, row.name),
                    modifier = Modifier.thread(row.depth).padding(top = 14.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A comment's rows: indented by its depth, with the thread's hairline down their left. */
@Composable
private fun Modifier.thread(depth: Int?): Modifier {
    if (depth == null) return this
    val hairline = MaterialTheme.colorScheme.outlineVariant
    return padding(start = (depth * INDENT_DP).dp)
        .drawBehind { drawRect(hairline, size = Size(2.dp.toPx(), size.height)) }
        .padding(start = 10.dp)
}

@Composable
private fun CommentsHeading() {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 2.dp)) {
        Text(
            stringResource(R.string.clip_text_comments),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            stringResource(R.string.clip_text_loaded_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A line: the whole row ticks its checkbox (a checkbox to tests and screen readers); ticked, it
 * is washed in paprika. A line an add took has a paprika bar, and its add's first line the tag.
 */
@Composable
private fun LineRow(
    row: ClipTextRow.Line,
    ticked: Boolean,
    mark: ClipTextMark?,
    tagLabel: (ClipField, Int) -> String,
    onLine: (Int) -> Unit,
    onTag: (String) -> Unit
) {
    val paprika = MaterialTheme.colorScheme.primary
    val wash = MaterialTheme.colorScheme.tertiary.copy(alpha = WASH_ALPHA)
    Column(Modifier.thread(row.depth)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (ticked) wash else Color.Transparent)
                .drawBehind { if (mark != null) drawRect(paprika, size = Size(3.dp.toPx(), size.height)) }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onLine(row.index) })
                    .padding(vertical = 9.dp)
                    .testTag("clip.line.${row.index}"),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Checkbox(checked = ticked, onCheckedChange = null)
                Text(
                    row.text,
                    modifier = Modifier.padding(top = 1.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (row.title) FontWeight.SemiBold else null
                )
            }
            if (mark?.first == true) {
                Text(
                    tagLabel(mark.mark.field, mark.mark.lines.size),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(paprika)
                        .clickable(role = Role.Button) { onTag(mark.mark.id) }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                        .testTag("clip.tag.${mark.mark.id}"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The ticked lines' wash: the app's paprika wash (as a step's ingredient link, #254). */
private const val WASH_ALPHA = 0.14f

/** How far each level of reply is indented, as the page did. */
private const val INDENT_DP = 12
