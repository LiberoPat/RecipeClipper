package com.example.recipeclipper.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.example.recipeclipper.MainActivity
import com.example.recipeclipper.R
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.MealPlanRepository
import com.example.recipeclipper.data.PlanCalendar
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.GroceryCombiner
import com.example.recipeclipper.data.model.WidgetContent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The home-screen widgets (#309): Tonight (today's planned meals) and the grocery list (what's
 * left to buy), as RemoteViews: Glance isn't in the Compose BOM (it is versioned on its own and
 * brings WorkManager in), and two short lists don't need it. The content is
 * [WidgetContent]'s; this only reads the repositories and draws. The list's lines are as
 * stored, without the on-device model's decisions (#104), as the iOS widget, which runs
 * apart from the app, has them. A tap opens the Week or
 * Groceries tab. With the `widgets` flag off, a placed widget shows only the app's name.
 */
@Singleton
class HomeWidgets @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mealPlan: MealPlanRepository,
    private val groceries: GroceryRepository,
    private val calendar: PlanCalendar,
    private val flags: FeatureFlags
) {

    // One redraw at a time (#329). Each reads its data inside the lock, so the redraw that
    // finishes last is the one that read last: an older list is never drawn over a newer one.
    private val drawing = Mutex()

    private val dayChange = WidgetDayChange(context)

    /** What [start] follows: the widgets on a home screen, and the day Tonight shows (0 without one). */
    private data class Placed(val tonight: Boolean = false, val groceries: Boolean = false, val today: Long = 0)

    private val placed = MutableStateFlow(Placed())

    private data class Content(val meals: List<WidgetContent.Meal>, val lines: List<String>)

    private class Ids(val tonight: IntArray, val groceries: IntArray, val today: Long)

    // Which widgets are placed now, noted for [start]: every redraw looks, and the providers
    // ask for one whenever a widget is placed or removed.
    private fun look(manager: AppWidgetManager): Ids {
        val ids = Ids(
            manager.getAppWidgetIds(ComponentName(context, TonightWidgetProvider::class.java)),
            manager.getAppWidgetIds(ComponentName(context, GroceriesWidgetProvider::class.java)),
            calendar.today()
        )
        placed.value = Placed(ids.tonight.isNotEmpty(), ids.groceries.isNotEmpty(), if (ids.tonight.isEmpty()) 0 else ids.today)
        return ids
    }

    /**
     * Redraws the placed widgets when what they show changes, for as long as the process lives.
     * It follows the mapped [WidgetContent], not the tables, so a write that leaves the widgets
     * as they are (another day's plan, a recipe) redraws nothing; and with no widget placed, or
     * the flag off, nothing is observed at all (#329). The day's turn is [WidgetDayChange]'s.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            AppWidgetManager.getInstance(context)?.let(::look)
            combine(placed, flags.values.map { it.isOn(Flag.WIDGETS) }.distinctUntilChanged(), ::Pair)
                .flatMapLatest { (placed, on) ->
                    when {
                        !placed.tonight && !placed.groceries -> emptyFlow()
                        !on -> flowOf(null)
                        else -> content(placed)
                    }
                }
                .distinctUntilChanged()
                .debounce(300)
                .collect { update() }
        }
    }

    private fun content(placed: Placed): Flow<Content?> = combine(
        if (placed.tonight) {
            combine(mealPlan.observeDays(placed.today, placed.today), mealPlan.observeMealTypes()) { meals, types ->
                WidgetContent.tonight(meals, types, placed.today)
            }
        } else flowOf(emptyList()),
        if (placed.groceries) {
            groceries.observeItems().map { WidgetContent.groceries(GroceryCombiner.sections(it)) }
        } else flowOf(emptyList())
    ) { meals, lines -> Content(meals, lines) }

    /** Draws every placed widget from the data as it is now, one redraw at a time. */
    suspend fun update() = drawing.withLock {
        val manager = AppWidgetManager.getInstance(context) ?: return@withLock
        val ids = look(manager)
        dayChange.follow(ids.today.takeIf { ids.tonight.isNotEmpty() })
        val on = flags.isOn(Flag.WIDGETS)
        if (ids.tonight.isNotEmpty()) {
            val meals = if (on) {
                val today = ids.today
                WidgetContent.tonight(mealPlan.observeDays(today, today).first(), mealPlan.observeMealTypes().first(), today)
            } else null
            for (id in ids.tonight) manager.updateAppWidget(id, sized(manager, id, MEAL_ROW) { rows -> tonight(meals, rows) })
        }
        if (ids.groceries.isNotEmpty()) {
            val lines = if (on) {
                WidgetContent.groceries(GroceryCombiner.sections(groceries.observeItems().first()))
            } else null
            for (id in ids.groceries) manager.updateAppWidget(id, sized(manager, id, LINE_ROW) { rows -> groceryList(lines, rows) })
        }
    }

    // A widget is OPTION_APPWIDGET_MAX_HEIGHT high in portrait and OPTION_APPWIDGET_MIN_HEIGHT
    // in landscape. It is drawn for each, and the launcher shows the one for how the phone is
    // held, so turning it needs no redraw (#329).
    private fun sized(manager: AppWidgetManager, id: Int, row: Height, card: (rows: Int) -> RemoteViews): RemoteViews {
        val options = manager.getAppWidgetOptions(id)
        val portrait = rowsThatFit(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0), row, ::spToDp)
        val landscape = rowsThatFit(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0), row, ::spToDp)
        return if (portrait == landscape) card(portrait) else RemoteViews(card(landscape), card(portrait))
    }

    // Text is sized in sp, so this follows the font scale (non-linear from Android 14).
    private fun spToDp(sp: Float): Float {
        val metrics = context.resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics) / metrics.density
    }

    private fun tonight(meals: List<WidgetContent.Meal>?, rows: Int): RemoteViews =
        card(R.string.widget_tonight_name, ACTION_OPEN_WEEK, meals, R.string.widget_tonight_empty, rows) { meal ->
            RemoteViews(context.packageName, R.layout.widget_row_meal).apply {
                setTextViewText(R.id.widget_meal_type, meal.mealType)
                setViewVisibility(R.id.widget_meal_type, if (meal.mealType.isEmpty()) View.GONE else View.VISIBLE)
                setTextViewText(R.id.widget_meal_title, meal.title)
            }
        }

    private fun groceryList(lines: List<String>?, rows: Int): RemoteViews =
        card(R.string.tab_groceries, ACTION_OPEN_GROCERIES, lines, R.string.widget_groceries_empty, rows) { line ->
            RemoteViews(context.packageName, R.layout.widget_row_line).apply { setTextViewText(R.id.widget_line, line) }
        }

    // The card: [items] as rows, as many as [rows] fit, then "+N more" ([WidgetContent.fit]: on
    // its own line, or beside the one item there is room for); the empty line when there are
    // none; only the app's name when [items] is null (the flag is off).
    private fun <T> card(title: Int, action: String, items: List<T>?, empty: Int, rows: Int, row: (T) -> RemoteViews) =
        RemoteViews(context.packageName, R.layout.widget_list).apply {
            setTextViewText(R.id.widget_title, context.getString(if (items == null) R.string.app_name else title))
            setOnClickPendingIntent(R.id.widget_root, openTab(action))
            removeAllViews(R.id.widget_rows)
            val list = items.orEmpty()
            val fit = WidgetContent.fit(list.size, rows)
            list.take(fit.shown).forEach { item ->
                addView(R.id.widget_rows, if (fit.inline) counted(row(item), fit.more) else row(item))
            }
            if (fit.more > 0 && !fit.inline) addView(R.id.widget_rows, more(R.layout.widget_row_more, fit.more))
            setTextViewText(R.id.widget_empty, context.getString(empty))
            setViewVisibility(R.id.widget_empty, if (items != null && list.isEmpty()) View.VISIBLE else View.GONE)
        }

    // "+N more", in a layout that colours it: a colour set here would be the one of the theme
    // at this moment, and stay when the system switches to dark (#329).
    private fun more(layout: Int, count: Int) = RemoteViews(context.packageName, layout).apply {
        setTextViewText(R.id.widget_more, context.resources.getQuantityString(R.plurals.widget_more, count, count))
    }

    private fun counted(row: RemoteViews, count: Int) =
        more(R.layout.widget_row_counted, count).apply { addView(R.id.widget_counted_item, row) }

    private fun openTab(action: String): PendingIntent = PendingIntent.getActivity(
        context,
        action.hashCode(),
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** A height that is part fixed (padding, in dp) and part text (in sp: it grows with the font scale). */
    internal data class Height(val dp: Float, val sp: Float)

    companion object {
        /** MainActivity opens the Week tab for an intent with this action. */
        const val ACTION_OPEN_WEEK = "com.example.recipeclipper.action.OPEN_WEEK"

        /** MainActivity opens the Groceries tab for an intent with this action. */
        const val ACTION_OPEN_GROCERIES = "com.example.recipeclipper.action.OPEN_GROCERIES"

        // A line of text is about this many times its size high, font padding included.
        private const val LINE = 1.36f

        // widget_list: its padding (12 + 12), the rows' top margin (6) and the 17sp title.
        private val CHROME = Height(dp = 30f, sp = 17 * LINE)

        /** widget_row_line (and "+N more"): 2 + 2 of padding around 15sp. */
        internal val LINE_ROW = Height(dp = 4f, sp = 15 * LINE)

        /** widget_row_meal: 3 + 3 of padding around a 12sp meal type over a 15sp title. */
        internal val MEAL_ROW = Height(dp = 6f, sp = (12 + 15) * LINE)

        private const val DEFAULT_ROWS = 4
        private const val MAX_ROWS = 12

        /**
         * How many rows of [row] fit a widget [heightDp] high, under its title ("+N more" takes
         * one). The rows are sized in sp, so [spToDp] carries the font scale (#329): larger
         * text means fewer rows, never clipped ones. Four when the launcher gave no height.
         */
        internal fun rowsThatFit(heightDp: Int, row: Height, spToDp: (Float) -> Float): Int {
            if (heightDp <= 0) return DEFAULT_ROWS
            val free = heightDp - CHROME.dp - spToDp(CHROME.sp)
            return (free / (row.dp + spToDp(row.sp))).toInt().coerceIn(1, MAX_ROWS)
        }
    }
}
