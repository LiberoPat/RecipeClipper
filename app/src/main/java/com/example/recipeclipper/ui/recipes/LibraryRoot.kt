package com.example.recipeclipper.ui.recipes

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.NoPhotoSource
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.ui.common.RecipeImage
import com.example.recipeclipper.ui.common.noPhotoImageOn
import com.example.recipeclipper.ui.edit.ScanRecipeButton
import com.example.recipeclipper.ui.home.CookResume
import com.example.recipeclipper.ui.home.HomeUiState
import com.example.recipeclipper.ui.home.HomeViewModel
import com.example.recipeclipper.ui.home.TimerTag
import com.example.recipeclipper.ui.home.TodayCloseButton
import com.example.recipeclipper.ui.home.TodayCard
import com.example.recipeclipper.ui.home.TodayMeal
import com.example.recipeclipper.ui.settings.BackupStatus
import com.example.recipeclipper.ui.settings.BackupStatusText
import com.example.recipeclipper.ui.tour.tooltipAnchor

/**
 * What makes the library the Recipes tab's first screen (#375, `libraryFirst`): it takes over
 * Home's jobs. [viewModel] is [HomeViewModel] (it already follows the cook's place, today's meals,
 * the restore and the Ask an AI sheet; Home is gone, so nothing else uses it), and the three
 * callbacks are the card's and the gear's destinations.
 */
class LibraryRoot(
    val viewModel: HomeViewModel,
    val onOpenSettings: () -> Unit,
    /** "Resume cooking": the recipe, in cook mode. */
    val onResumeCooking: (Long) -> Unit,
    /** Today's meal: the planned recipe, its planned servings, and whether to start cooking it. */
    val onOpenPlanned: (Long, Int?, Boolean) -> Unit,
    /** The Pro screen (#413): Ask an AI's locked Plan my week choices open it. */
    val onOpenPro: () -> Unit = {}
)

/** The gear and the + beside the title (#375): Settings, and the Add a recipe sheet. */
@Composable
internal fun LibraryTopActions(onOpenSettings: () -> Unit, onAdd: () -> Unit) {
    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("librarySettings")) {
        Icon(
            Icons.Default.Settings,
            contentDescription = stringResource(R.string.nav_settings),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    LibraryAddButton(onAdd)
}

/** The paprika + (#375): the Add a recipe sheet. The tour's tooltip points at it. */
@Composable
internal fun LibraryAddButton(onAdd: () -> Unit) {
    IconButton(
        onClick = onAdd,
        modifier = Modifier.padding(start = 2.dp).tooltipAnchor(Tooltip.HOME_LINK).testTag("libraryAdd")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.cd_add_recipe),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/**
 * The compact card above search (#375, from #405's Today and Continue cooking): a thumbnail, what
 * it is ("Today · Lunch", or "Continue cooking"), the name, one line (the next meal, or the step and
 * the countdown) and the one button. Only a recipe being cooked, or today's next meal while
 * `homeTonight` is on, gets one; with neither there is nothing here, never an "Open recipe" card.
 * It is the library's first item, so it scrolls away with the list.
 */
@Composable
internal fun LibraryCard(
    state: HomeUiState,
    root: LibraryRoot,
    homeTonight: Boolean,
    onOpenRecipe: (Long) -> Unit,
    /** Design B (#449): the outlined card, the other meal beside a pill-shaped button. */
    styleB: Boolean = false
) {
    val latest = state.continueCooking
    val resume = state.resume
    val today = state.today?.takeIf { homeTonight && resume == null }
    if (styleB) {
        when {
            latest != null && resume != null -> CompactCardB(
                imageUrl = latest.imageUrl,
                noPhoto = latest.noPhoto,
                label = stringResource(R.string.section_continue_cooking),
                title = latest.title,
                button = stringResource(R.string.home_resume_cooking),
                onButton = { root.onResumeCooking(latest.id) },
                onOpen = { onOpenRecipe(latest.id) },
                under = { ResumeLine(resume) }
            )
            today != null -> {
                val lead = today.lead
                val type = lead.mealType.takeIf { it.isNotBlank() }
                CompactCardB(
                    imageUrl = lead.imageUrl,
                    noPhoto = lead.noPhoto.takeIf { lead.recipeId != null },
                    label = stringResource(R.string.home_today) + (type?.let { " · $it" } ?: ""),
                    title = lead.title,
                    button = stringResource(R.string.action_start_cooking).takeIf { lead.recipeId != null },
                    onButton = { lead.recipeId?.let { root.onOpenPlanned(it, lead.servings, true) } },
                    onOpen = lead.recipeId?.let { id -> { root.onOpenPlanned(id, lead.servings, false) } },
                    // The close button (#448, `todayCardClose`): gone until tomorrow. Never on Continue cooking.
                    onClose = if (state.todayClosable) root.viewModel::onCloseToday else null,
                    beside = { OtherMeals(today, root, styleB = true) }
                )
            }
        }
        return
    }
    when {
        latest != null && resume != null -> CompactCard(
            imageUrl = latest.imageUrl,
            noPhoto = latest.noPhoto,
            label = stringResource(R.string.section_continue_cooking),
            title = latest.title,
            button = stringResource(R.string.home_resume_cooking),
            onButton = { root.onResumeCooking(latest.id) },
            onOpen = { onOpenRecipe(latest.id) }
        ) { ResumeLine(resume) }
        today != null -> {
            val lead = today.lead
            val type = lead.mealType.takeIf { it.isNotBlank() }
            CompactCard(
                imageUrl = lead.imageUrl,
                noPhoto = lead.noPhoto.takeIf { lead.recipeId != null },
                label = stringResource(R.string.home_today) + (type?.let { " · $it" } ?: ""),
                title = lead.title,
                button = stringResource(R.string.action_start_cooking).takeIf { lead.recipeId != null },
                onButton = { lead.recipeId?.let { root.onOpenPlanned(it, lead.servings, true) } },
                onOpen = lead.recipeId?.let { id -> { root.onOpenPlanned(id, lead.servings, false) } },
                // The close button (#448, `todayCardClose`): gone until tomorrow. Never on Continue cooking.
                onClose = if (state.todayClosable) root.viewModel::onCloseToday else null
            ) { OtherMeals(today, root) }
        }
    }
}

@Composable
private fun CompactCard(
    imageUrl: String?,
    /** What the designed image (#403) is made from; null (a planned note) is the plain block. */
    noPhoto: NoPhotoSource?,
    label: String,
    title: String,
    button: String?,
    onButton: () -> Unit,
    onOpen: (() -> Unit)?,
    /** The close button at the top right (#448); null has none. */
    onClose: (() -> Unit)? = null,
    line: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().testTag("libraryCard")
            .clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainer).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().then(
                if (onOpen != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onOpen) else Modifier
            )
        ) {
            // The photo; with none, the recipe's designed image (#403, `noPhotoImage`) or a plain themed
            // block: never a stock image.
            val thumb = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp))
            val plain: @Composable (Modifier) -> Unit = { Box(it.background(colors.tertiary.copy(alpha = 0.12f))) }
            if (noPhoto != null && noPhotoImageOn()) {
                RecipeImage(model = imageUrl, recipe = noPhoto, contentDescription = null, modifier = thumb, whenOff = plain)
            } else if (imageUrl != null) {
                AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = thumb)
            } else {
                plain(thumb)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() }
                )
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                line()
            }
            if (onClose != null) TodayCloseButton(onClose, Modifier.align(Alignment.Top))
        }
        if (button != null) {
            Button(
                onClick = onButton,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            ) { Text(button) }
        }
    }
}

/**
 * The card as design B draws it (#449): outlined on the ground rather than filled, the thumbnail beside
 * a paprika eyebrow ("TODAY · DINNER") and the name, then a row with the next meal on the left and the
 * button as a paprika pill on the right. [under] goes under the name (the step and the countdown),
 * [beside] beside the button (the next meal). The close button (#448) keeps its place at the top right.
 */
@Composable
private fun CompactCardB(
    imageUrl: String?,
    noPhoto: NoPhotoSource?,
    label: String,
    title: String,
    button: String?,
    onButton: () -> Unit,
    onOpen: (() -> Unit)?,
    onClose: (() -> Unit)? = null,
    under: @Composable () -> Unit = {},
    beside: @Composable () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().testTag("libraryCard")
            .clip(shape).background(colors.background).border(1.dp, colors.outlineVariant, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().then(
                if (onOpen != null) Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpen) else Modifier
            )
        ) {
            val thumb = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
            val plain: @Composable (Modifier) -> Unit = { Box(it.background(colors.tertiary.copy(alpha = 0.12f))) }
            if (noPhoto != null && noPhotoImageOn()) {
                RecipeImage(model = imageUrl, recipe = noPhoto, contentDescription = null, modifier = thumb, whenOff = plain)
            } else if (imageUrl != null) {
                AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = thumb)
            } else {
                plain(thumb)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp
                    ),
                    color = colors.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 22.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                under()
            }
            if (onClose != null) TodayCloseButton(onClose, Modifier.align(Alignment.Top))
        }
        if (button != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { beside() }
                Button(
                    onClick = onButton,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(button, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp))
                }
            }
        } else {
            beside()
        }
    }
}

/** Where the cook stopped: the running timer's time left, then "Step 2 of 4". */
@Composable
private fun ResumeLine(resume: CookResume) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        resume.timerSecondsLeft?.let { TimerTag(it) }
        Text(
            stringResource(R.string.cook_position, resume.step, resume.stepCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The next meal still to cook today, on one line ("Dinner: Lemon Garlic Salmon"); it opens its recipe. */
@Composable
private fun OtherMeals(card: TodayCard, root: LibraryRoot, styleB: Boolean = false) {
    val next: TodayMeal = card.others.firstOrNull() ?: return
    val line = stringResource(R.string.home_today_other, next.mealType, next.title)
    // Design B (#449): the meal's name in ink, its type muted ("Snack: Banana Bread").
    val at = line.lastIndexOf(next.title)
    val ink = MaterialTheme.colorScheme.onBackground
    val text = if (styleB && at >= 0) {
        buildAnnotatedString {
            append(line)
            addStyle(SpanStyle(color = ink, fontWeight = FontWeight.SemiBold), at, at + next.title.length)
        }
    } else {
        AnnotatedString(line)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (styleB) 2 else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp).fillMaxWidth().then(
            next.recipeId?.let { id ->
                Modifier.clickable(role = Role.Button) { root.onOpenPlanned(id, next.servings, false) }
            } ?: Modifier
        )
    )
}

/** One way to add a recipe: its icon, name and the one-line hint Home's tiles had. */
private class WayIn(
    @DrawableRes val icon: Int,
    @StringRes val name: Int,
    @StringRes val hint: Int,
    val tag: String,
    val onClick: () -> Unit
)

/**
 * "Add a recipe" (#375): the + 's sheet, replacing #102's two-item menu. Paste a link, Scan a recipe
 * (null [onScan]: the `photoText` flag is off), Type a recipe and Ask an AI (null [onAskAi]: `askAi`
 * is off), each with its line, then the line about sharing from any app. A choice closes the sheet and
 * goes where it leads; the scan's camera-or-library choice opens under its row and closes it once
 * the pictures are in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddRecipeSheet(
    onDismiss: () -> Unit,
    onPasteLink: () -> Unit,
    onScan: ((List<String>) -> Unit)?,
    onTypeRecipe: () -> Unit,
    onAskAi: (() -> Unit)?
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("addRecipeSheet")) {
            Text(
                stringResource(R.string.cd_add_recipe),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 6.dp).semantics { heading() }
            )
            WayInRow(
                WayIn(R.drawable.ic_link, R.string.action_paste_link, R.string.home_paste_link_hint, "addPasteLink") {
                    onDismiss(); onPasteLink()
                }
            )
            if (onScan != null) {
                ScanRecipeButton({ pages -> onDismiss(); onScan(pages) }, Modifier.fillMaxWidth()) { open ->
                    WayInRow(WayIn(R.drawable.ic_camera, R.string.action_scan_recipe, R.string.home_scan_hint, "addScan", open))
                }
            }
            WayInRow(
                WayIn(R.drawable.ic_pen, R.string.action_type_recipe, R.string.home_type_hint, "addType") {
                    onDismiss(); onTypeRecipe()
                }
            )
            if (onAskAi != null) {
                WayInRow(
                    WayIn(R.drawable.ic_sparkle, R.string.action_ask_ai, R.string.home_ask_ai_hint, "addAskAi") {
                        onDismiss(); onAskAi()
                    }
                )
            }
            Text(
                stringResource(R.string.home_share_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun WayInRow(way: WayIn) {
    val colors = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = way.onClick)
            .padding(vertical = 8.dp).testTag(way.tag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.tertiary.copy(alpha = 0.12f))
        ) {
            Icon(painterResource(way.icon), null, tint = colors.tertiary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(way.name), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(way.hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
    }
}

/**
 * The empty library (#375), in the list's place: the four ways in as tiles, the line about sharing,
 * and "Restore from a backup file" (#150) with how the restore went. The sample recipe (#151), when
 * that is all there is, is the one row under it.
 */
@Composable
internal fun EmptyLibrary(
    onPasteLink: () -> Unit,
    onScan: ((List<String>) -> Unit)?,
    onTypeRecipe: () -> Unit,
    onAskAi: (() -> Unit)?,
    restore: BackupStatus,
    onRestore: (() -> Unit)?
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 10.dp).testTag("emptyLibrary"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        val tiles = buildList<WayIn> {
            add(WayIn(R.drawable.ic_link, R.string.action_paste_link, R.string.home_paste_link_hint, "emptyPasteLink", onPasteLink))
            if (onScan != null) add(WayIn(R.drawable.ic_camera, R.string.action_scan_recipe, R.string.home_scan_hint, "emptyScan") {})
            add(WayIn(R.drawable.ic_pen, R.string.action_type_recipe, R.string.home_type_hint, "emptyType", onTypeRecipe))
            if (onAskAi != null) add(WayIn(R.drawable.ic_sparkle, R.string.action_ask_ai, R.string.home_ask_ai_hint, "emptyAskAi", onAskAi))
        }
        tiles.chunked(2).forEach { pair ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)
            ) {
                pair.forEach { way ->
                    val tile = Modifier.weight(1f).fillMaxHeight()
                    if (way.tag == "emptyScan" && onScan != null) {
                        ScanRecipeButton(onScan, tile) { open -> WayTile(WayIn(way.icon, way.name, way.hint, way.tag, open), Modifier.fillMaxSize()) }
                    } else {
                        WayTile(way, tile)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(stringResource(R.string.home_share_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (onRestore != null) {
            TextButton(
                onClick = onRestore,
                enabled = restore != BackupStatus.Importing,
                modifier = Modifier.offset(x = (-12).dp).testTag("libraryRestore")
            ) {
                Text(stringResource(R.string.home_restore_backup), style = MaterialTheme.typography.labelLarge, color = colors.tertiary)
            }
        }
        BackupStatusText(restore)
    }
}

/** One of the empty library's tiles: the icon beside the name, and the hint under it. */
@Composable
private fun WayTile(way: WayIn, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainer)
            .clickable(role = Role.Button, onClick = way.onClick).padding(12.dp).testTag(way.tag)
    ) {
        Icon(painterResource(way.icon), null, tint = colors.tertiary, modifier = Modifier.size(20.dp))
        Text(stringResource(way.name), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(way.hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}
