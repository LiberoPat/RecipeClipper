package com.example.recipeclipper.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    /** Redraws every placed widget whenever the plan, the list or the flags change, for as long
     *  as the process lives. The system's own refresh covers the rest (and midnight). */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start(scope: CoroutineScope) {
        val today = calendar.today()
        scope.launch {
            combine(
                mealPlan.observeDays(today - 1, today + 1),
                mealPlan.observeMealTypes(),
                groceries.observeItems(),
                flags.values
            ) { _, _, _, _ -> }
                .debounce(300)
                .collect { update() }
        }
    }

    /** Draws every placed widget from the data as it is now. */
    suspend fun update() {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val tonightIds = manager.getAppWidgetIds(ComponentName(context, TonightWidgetProvider::class.java))
        val groceryIds = manager.getAppWidgetIds(ComponentName(context, GroceriesWidgetProvider::class.java))
        val on = flags.isOn(Flag.WIDGETS)
        if (tonightIds.isNotEmpty()) {
            val today = calendar.today()
            val meals = if (on) {
                WidgetContent.tonight(mealPlan.observeDays(today, today).first(), mealPlan.observeMealTypes().first(), today)
            } else null
            for (id in tonightIds) manager.updateAppWidget(id, tonight(meals, maxRows(manager, id, MEAL_ROW_DP)))
        }
        if (groceryIds.isNotEmpty()) {
            val lines = if (on) {
                WidgetContent.groceries(GroceryCombiner.sections(groceries.observeItems().first()))
            } else null
            for (id in groceryIds) manager.updateAppWidget(id, groceryList(lines, maxRows(manager, id, LINE_ROW_DP)))
        }
    }

    // How many rows fit the widget's height as placed (the "+N more" line takes one).
    private fun maxRows(manager: AppWidgetManager, id: Int, rowDp: Int): Int {
        val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return if (height <= 0) DEFAULT_ROWS else ((height - CHROME_DP) / rowDp).coerceIn(1, MAX_ROWS)
    }

    private fun tonight(meals: List<WidgetContent.Meal>?, maxRows: Int): RemoteViews =
        card(R.string.widget_tonight_name, ACTION_OPEN_WEEK, meals, R.string.widget_tonight_empty, maxRows) { meal ->
            RemoteViews(context.packageName, R.layout.widget_row_meal).apply {
                setTextViewText(R.id.widget_meal_type, meal.mealType)
                setViewVisibility(R.id.widget_meal_type, if (meal.mealType.isEmpty()) View.GONE else View.VISIBLE)
                setTextViewText(R.id.widget_meal_title, meal.title)
            }
        }

    private fun groceryList(lines: List<String>?, maxRows: Int): RemoteViews =
        card(R.string.tab_groceries, ACTION_OPEN_GROCERIES, lines, R.string.widget_groceries_empty, maxRows) { line ->
            RemoteViews(context.packageName, R.layout.widget_row_line).apply { setTextViewText(R.id.widget_line, line) }
        }

    // The card: [items] as rows, as many as fit, then "+N more"; the empty line when there are
    // none; only the app's name when [items] is null (the flag is off).
    private fun <T> card(title: Int, action: String, items: List<T>?, empty: Int, maxRows: Int, row: (T) -> RemoteViews) =
        RemoteViews(context.packageName, R.layout.widget_list).apply {
            setTextViewText(R.id.widget_title, context.getString(if (items == null) R.string.app_name else title))
            setOnClickPendingIntent(R.id.widget_root, openTab(action))
            removeAllViews(R.id.widget_rows)
            val list = items.orEmpty()
            val shown = if (list.size <= maxRows) list else list.take(maxRows - 1)
            shown.forEach { addView(R.id.widget_rows, row(it)) }
            if (shown.size < list.size) {
                val more = list.size - shown.size
                addView(R.id.widget_rows, RemoteViews(context.packageName, R.layout.widget_row_line).apply {
                    setTextViewText(R.id.widget_line, context.resources.getQuantityString(R.plurals.widget_more, more, more))
                    setTextColor(R.id.widget_line, context.getColor(R.color.widget_muted))
                })
            }
            setTextViewText(R.id.widget_empty, context.getString(empty))
            setViewVisibility(R.id.widget_empty, if (items != null && list.isEmpty()) View.VISIBLE else View.GONE)
        }

    private fun openTab(action: String): PendingIntent = PendingIntent.getActivity(
        context,
        action.hashCode(),
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        /** MainActivity opens the Week tab for an intent with this action. */
        const val ACTION_OPEN_WEEK = "com.example.recipeclipper.action.OPEN_WEEK"

        /** MainActivity opens the Groceries tab for an intent with this action. */
        const val ACTION_OPEN_GROCERIES = "com.example.recipeclipper.action.OPEN_GROCERIES"

        private const val CHROME_DP = 48
        private const val MEAL_ROW_DP = 42
        private const val LINE_ROW_DP = 24
        private const val DEFAULT_ROWS = 4
        private const val MAX_ROWS = 12
    }
}
