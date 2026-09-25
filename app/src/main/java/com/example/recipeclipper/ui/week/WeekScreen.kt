package com.example.recipeclipper.ui.week

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.core.content.FileProvider
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.MealPlanIcs
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.PlannedMeal
import com.example.recipeclipper.ui.groceries.AddToGroceriesSheet
import com.example.recipeclipper.ui.groceries.AddToGroceriesViewModel
import com.example.recipeclipper.ui.plan.MealTypeChoices
import com.example.recipeclipper.ui.plan.PlanSheetContent
import com.example.recipeclipper.ui.plan.dayOfMonth
import com.example.recipeclipper.ui.plan.dayTitle
import com.example.recipeclipper.ui.plan.monthTitle
import com.example.recipeclipper.ui.plan.rememberDayTitle
import com.example.recipeclipper.ui.plan.shortWeekday
import com.example.recipeclipper.ui.plan.weekRange
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.SectionHeading
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import java.io.File
import java.io.IOException

/**
 * The Week tab (#49): ‹ week › with "This week", then the seven days from the locale's first
 * day of the week, each with its meals and a "+ Add". Tapping a recipe opens it at the planned
 * servings; long-pressing a meal offers Move and Remove (Remove can be undone). The menu's "Add
 * this week's ingredients" (#50) opens the grocery sheet over every recipe planned in the week
 * shown; [groceriesViewModel] null leaves it out. "What I need" (#51) opens the week against the
 * pantry; [onOpenWhatINeed] null leaves it out.
 */
@Composable
fun WeekScreen(
    onOpenRecipe: (recipeId: Long, servings: Int?) -> Unit,
    onOpenMealTypes: () -> Unit,
    onOpenWhatINeed: ((weekStart: Long) -> Unit)? = null,
    viewModel: WeekViewModel = hiltViewModel(),
    groceriesViewModel: AddToGroceriesViewModel? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var groceriesSheetOpen by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val removed = state.removed
    val removedMessage = removed?.let { stringResource(R.string.snackbar_removed_from_plan, it.label) }
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(removed) {
        val message = removedMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(message, actionLabel = undoLabel, withDismissAction = false)
        if (result == SnackbarResult.ActionPerformed) viewModel.onUndoRemove() else viewModel.onSnackbarDismissed()
    }

    // What a menu action did (#52), once.
    val resources = LocalResources.current
    val menuMessage = state.menus.message
    LaunchedEffect(menuMessage) {
        if (menuMessage == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(menuMessageText(resources, menuMessage))
        viewModel.onMenuMessageShown()
    }

    // The week as an .ics file (#52): written to the cache and handed to the share sheet here,
    // in the view layer; the ViewModel only makes the text.
    val context = LocalContext.current
    val calendarFile = state.calendarFile
    val shareTitle = stringResource(R.string.action_share_calendar)
    LaunchedEffect(calendarFile) {
        if (calendarFile == null) return@LaunchedEffect
        shareCalendarFile(context, calendarFile, shareTitle)
        viewModel.onCalendarShared()
    }

    RecipeClipperTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing
        ) { padding ->
            val typeNames = state.mealTypes.associate { it.id to it.name }
            val listState = rememberLazyListState()
            // After a tap in the month view: scroll to that day once its week has loaded. The
            // header is item 0; each day is its heading, its meals, then its "+ Add".
            val focusDay = state.focusDay
            LaunchedEffect(focusDay, state.days) {
                if (focusDay == null || state.days.none { it.day == focusDay }) return@LaunchedEffect
                val index = 1 + state.days.takeWhile { it.day != focusDay }.sumOf { it.meals.size + 2 }
                listState.scrollToItem(index)
                viewModel.onFocusHandled()
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize().padding(padding).testTag("weekList")
            ) {
                item(key = "header") {
                    val month = state.month
                    Column(Modifier.fillMaxWidth()) {
                        TitleRow(
                            showingMonth = month != null,
                            onToggleMonth = if (month == null) viewModel::onShowMonth else viewModel::onShowWeek,
                            onOpenMealTypes = onOpenMealTypes,
                            // The week's own actions act on the week shown, so the month view
                            // leaves them out.
                            onOpenWhatINeed = onOpenWhatINeed?.takeIf { month == null }?.let { open -> { open(state.weekStart) } },
                            onAddToGroceries = groceriesViewModel?.takeIf { month == null }?.let { sheet ->
                                {
                                    sheet.loadWeek(state.weekStart)
                                    groceriesSheetOpen = true
                                }
                            },
                            onShareCalendar = viewModel::onShareCalendar.takeIf { month == null },
                            canShareCalendar = state.hasMeals,
                            onSaveMenu = viewModel::onSaveMenuStart.takeIf { month == null },
                            onApplyMenu = viewModel::onPickMenuStart.takeIf { month == null }
                        )
                        Spacer(Modifier.height(8.dp))
                        if (month == null) {
                            PeriodNavigation(
                                label = remember(state.weekStart) { weekRange(state.weekStart) },
                                labelTag = "weekRange",
                                previousDescription = stringResource(R.string.cd_previous_week),
                                nextDescription = stringResource(R.string.cd_next_week),
                                backLabel = stringResource(R.string.action_this_week).takeUnless { state.isThisWeek },
                                onPrevious = viewModel::onPreviousWeek,
                                onNext = viewModel::onNextWeek,
                                onBack = viewModel::onThisWeek
                            )
                            Hairline()
                        } else {
                            PeriodNavigation(
                                label = remember(month.monthStart) { monthTitle(month.monthStart) },
                                labelTag = "monthTitle",
                                previousDescription = stringResource(R.string.cd_previous_month),
                                nextDescription = stringResource(R.string.cd_next_month),
                                backLabel = stringResource(R.string.action_this_month).takeUnless { month.isThisMonth },
                                onPrevious = viewModel::onPreviousMonth,
                                onNext = viewModel::onNextMonth,
                                onBack = viewModel::onThisMonth
                            )
                            Hairline()
                            MonthGrid(month = month, today = state.today, onSelect = viewModel::onMonthDaySelected)
                        }
                    }
                }
                if (state.month != null) return@LazyColumn
                state.days.forEach { weekDay ->
                    item(key = "day-${weekDay.day}") {
                        DayHeader(day = weekDay.day, isToday = weekDay.day == state.today)
                    }
                    items(weekDay.meals, key = { "meal-${it.id}" }) { meal ->
                        MealRow(
                            meal = meal,
                            mealTypeName = typeNames[meal.mealTypeId].orEmpty(),
                            onOpen = { meal.recipeId?.let { onOpenRecipe(it, meal.servings) } },
                            onMove = { viewModel.onMoveStart(meal) },
                            onRemove = { viewModel.onRemove(meal) }
                        )
                    }
                    item(key = "add-${weekDay.day}") {
                        TextButton(
                            onClick = { viewModel.onAddToDay(weekDay.day) },
                            modifier = Modifier.testTag("addToDay-${weekDay.day}")
                        ) {
                            Text(stringResource(R.string.action_add_meal))
                        }
                        Hairline()
                    }
                }
            }
        }

        state.adding?.let { adding ->
            AddToDaySheet(
                adding = adding,
                mealTypes = state.mealTypes,
                onMealTypeSelected = viewModel::onAddMealTypeSelected,
                onQueryChange = viewModel::onAddQueryChange,
                onPickRecipe = viewModel::onAddRecipe,
                onAddNote = viewModel::onAddNote,
                onDismiss = viewModel::onAddDismissed
            )
        }
        if (groceriesSheetOpen && groceriesViewModel != null) {
            AddToGroceriesSheet(groceriesViewModel, onDismiss = { groceriesSheetOpen = false })
        }
        WeekMenus(state.menus, viewModel)
        state.moving?.let { moving ->
            MoveSheet(
                moving = moving,
                today = state.today,
                mealTypes = state.mealTypes,
                onDaySelected = viewModel::onMoveDaySelected,
                onMealTypeSelected = viewModel::onMoveMealTypeSelected,
                onConfirm = viewModel::onMoveConfirm,
                onDismiss = viewModel::onMoveDismissed
            )
        }
    }
}

/** "Week", then the Week ↔ Month switch and the menu. */
@Composable
private fun TitleRow(
    showingMonth: Boolean,
    onToggleMonth: () -> Unit,
    onOpenMealTypes: () -> Unit,
    onOpenWhatINeed: (() -> Unit)?,
    onAddToGroceries: (() -> Unit)?,
    onShareCalendar: (() -> Unit)?,
    canShareCalendar: Boolean,
    onSaveMenu: (() -> Unit)? = null,
    onApplyMenu: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            stringResource(R.string.tab_week),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onToggleMonth, modifier = Modifier.testTag("toggleMonth")) {
            Text(stringResource(if (showingMonth) R.string.action_week_view else R.string.action_month_view))
        }
        WeekMenu(onOpenMealTypes, onOpenWhatINeed, onAddToGroceries, onShareCalendar, canShareCalendar, onSaveMenu, onApplyMenu)
    }
}

/** ‹ label › and, away from the current week or month, a way back to it. */
@Composable
private fun PeriodNavigation(
    label: String,
    labelTag: String,
    previousDescription: String,
    nextDescription: String,
    backLabel: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onPrevious, modifier = Modifier.semantics { contentDescription = previousDescription }) {
            Text("‹", style = MaterialTheme.typography.headlineSmall)
        }
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(labelTag))
        IconButton(onClick = onNext, modifier = Modifier.semantics { contentDescription = nextDescription }) {
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.weight(1f))
        if (backLabel != null) {
            TextButton(onClick = onBack) { Text(backLabel) }
        }
    }
}

/**
 * The month view (#52): the locale's weekdays over whole weeks. A day with meals has a paprika
 * dot; today's date is paprika text; the days before and after the month are muted. Tapping any
 * day opens its week.
 */
@Composable
private fun MonthGrid(month: MonthUiState, today: Long, onSelect: (Long) -> Unit) {
    if (month.days.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("monthGrid")) {
        Row(Modifier.fillMaxWidth()) {
            month.days.take(7).forEach { day ->
                Text(
                    remember(day.day) { shortWeekday(day.day) },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clearAndSetSemantics {}
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        month.days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    val title = rememberDayTitle(cell.day)
                    val description = if (cell.mealCount > 0) stringResource(R.string.cd_month_day_planned, title) else title
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onSelect(cell.day) }
                            .testTag("monthDay-${cell.day}")
                            .clearAndSetSemantics { contentDescription = description }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            remember(cell.day) { dayOfMonth(cell.day) },
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                cell.day == today -> MaterialTheme.colorScheme.tertiary
                                cell.inMonth -> MaterialTheme.colorScheme.onBackground
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (cell.mealCount > 0) MaterialTheme.colorScheme.primary else Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekMenu(
    onOpenMealTypes: () -> Unit,
    onOpenWhatINeed: (() -> Unit)?,
    onAddToGroceries: (() -> Unit)?,
    onShareCalendar: (() -> Unit)?,
    canShareCalendar: Boolean,
    onSaveMenu: (() -> Unit)?,
    onApplyMenu: (() -> Unit)?
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onOpenWhatINeed != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.what_i_need_title)) },
                    onClick = {
                        expanded = false
                        onOpenWhatINeed()
                    }
                )
            }
            if (onAddToGroceries != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_add_week_to_groceries)) },
                    onClick = {
                        expanded = false
                        onAddToGroceries()
                    }
                )
            }
            if (onShareCalendar != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_share_calendar)) },
                    enabled = canShareCalendar,
                    onClick = {
                        expanded = false
                        onShareCalendar()
                    }
                )
            }
            if (onSaveMenu != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_save_week_as_menu)) },
                    enabled = canShareCalendar,
                    modifier = Modifier.testTag("saveWeekAsMenu"),
                    onClick = {
                        expanded = false
                        onSaveMenu()
                    }
                )
            }
            if (onApplyMenu != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_apply_menu)) },
                    modifier = Modifier.testTag("applyMenu"),
                    onClick = {
                        expanded = false
                        onApplyMenu()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.meal_types_title)) },
                onClick = {
                    expanded = false
                    onOpenMealTypes()
                }
            )
        }
    }
}

/**
 * Writes [file] into `cacheDir/exports/` (the FileProvider's one shared folder, as the backup
 * export uses) and opens the share sheet on it as `text/calendar`, letting only the chosen app
 * read it. A write failure or no app to take it just does nothing.
 */
private fun shareCalendarFile(context: Context, file: CalendarFile, title: String) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, file.fileName).apply { writeText(file.text, Charsets.UTF_8) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MealPlanIcs.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: IOException) {
        // Couldn't write the file: nothing to share.
    } catch (e: ActivityNotFoundException) {
        // Nothing can receive a file.
    }
}

@Composable
private fun DayHeader(day: Long, isToday: Boolean) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp)) {
        Text(rememberDayTitle(day), style = MaterialTheme.typography.titleMedium)
        if (isToday) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.label_today),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

/**
 * A planned meal: its meal type above the recipe (thumbnail, title, servings) or the note.
 * Tap opens a recipe; long-press opens Move and Remove.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MealRow(
    meal: PlannedMeal,
    mealTypeName: String,
    onOpen: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    role = Role.Button,
                    onClick = { if (meal.recipeId != null) onOpen() else menuOpen = true },
                    onLongClick = { menuOpen = true }
                )
                .padding(vertical = 8.dp)
                .testTag("meal-${meal.id}")
        ) {
            if (meal.recipeId != null) {
                val shape = RoundedCornerShape(8.dp)
                if (meal.imageUrl != null) {
                    AsyncImage(
                        model = meal.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(shape)
                    )
                } else {
                    Box(Modifier.size(44.dp).clip(shape).background(MaterialTheme.colorScheme.outlineVariant))
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    mealTypeName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (meal.recipeId != null) {
                    Text(
                        meal.title.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        meal.note.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
                    )
                }
            }
            meal.servings?.let {
                Text(
                    pluralStringResource(R.plurals.servings, it, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_move)) },
                onClick = {
                    menuOpen = false
                    onMove()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_remove_from_plan)) },
                onClick = {
                    menuOpen = false
                    onRemove()
                }
            )
        }
    }
}

/**
 * "+ Add" on a day: a meal type, then one of your recipes (history, searchable) or, with
 * anything typed, that text as a note. Choosing adds at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToDaySheet(
    adding: AddToDayState,
    mealTypes: List<MealType>,
    onMealTypeSelected: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onPickRecipe: (Long) -> Unit,
    onAddNote: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
        ) {
            item {
                SectionHeading(stringResource(R.string.add_to_day_title, dayTitle(adding.day)))
                Spacer(Modifier.height(12.dp))
                MealTypeChoices(types = mealTypes, selected = adding.mealTypeId, onSelect = onMealTypeSelected)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = adding.query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.label_search_or_note)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("addSearch")
                )
                if (adding.query.isNotBlank()) {
                    TextButton(onClick = onAddNote) {
                        Text(stringResource(R.string.action_add_as_note, adding.query.trim()))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            val results = adding.results
            if (results != null && results.isEmpty() && adding.query.isBlank()) {
                item {
                    Text(
                        stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(results.orEmpty(), key = { "pick-${it.id}" }) { recipe ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onPickRecipe(recipe.id) }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        recipe.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Hairline()
            }
        }
    }
}

/** Long-press → Move: the same day strip and meal types as "Add to plan", then "Move". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveSheet(
    moving: MoveState,
    today: Long,
    mealTypes: List<MealType>,
    onDaySelected: (Long) -> Unit,
    onMealTypeSelected: (Long) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        PlanSheetContent(
            title = stringResource(R.string.move_meal_title, moving.meal.title ?: moving.meal.note.orEmpty()),
            days = moving.days,
            today = today,
            selectedDay = moving.day,
            onDaySelected = onDaySelected,
            mealTypes = mealTypes,
            selectedMealTypeId = moving.mealTypeId,
            onMealTypeSelected = onMealTypeSelected,
            servings = null,
            onServingsChange = {},
            confirmLabel = stringResource(R.string.action_move_to_day, dayTitle(moving.day)),
            confirmEnabled = true,
            onConfirm = onConfirm
        )
    }
}
