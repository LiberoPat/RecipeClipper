package com.example.recipeclipper.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.recipeclipper.data.model.MealReminders
import com.example.recipeclipper.data.model.RecipeSort
import com.example.recipeclipper.data.model.RecipesLayout
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.data.model.UnitSystem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** The real, `SharedPreferences`-backed [AppPreferences], [TourPreferences] and [UseUpLog]. The
 *  ViewModel only ever sees an interface, never the `Context` inside this. */
@Singleton
class SharedPrefsAppPreferences @Inject constructor(
    @ApplicationContext context: Context
) : AppPreferences, TourPreferences, UseUpLog, PlanChoices {

    // The file keeps its original name: renaming it would strand every existing user's
    // saved unit choice for no gain.
    private val prefs = context.getSharedPreferences("unit_preferences", Context.MODE_PRIVATE)

    override var unitSystem: UnitSystem
        // A stored GRAMS (the option #17 removed) reads as METRIC.
        get() = UnitSystem.fromStoredName(prefs.getString(KEY_SYSTEM, null))
        set(value) {
            prefs.edit { putString(KEY_SYSTEM, value.name) }
        }

    override var convertLiquids: Boolean
        get() = prefs.getBoolean(KEY_LIQUIDS, false)
        set(value) {
            prefs.edit { putBoolean(KEY_LIQUIDS, value) }
        }

    override var temperatureUnit: TemperatureUnit
        get() {
            val saved = prefs.getString(KEY_TEMPERATURE_UNIT, null)
            return TemperatureUnit.values().firstOrNull { it.name == saved } ?: TemperatureUnit.AS_WRITTEN
        }
        set(value) {
            prefs.edit { putString(KEY_TEMPERATURE_UNIT, value.name) }
        }

    override var darkWhileCooking: Boolean
        get() = prefs.getBoolean(KEY_DARK_COOKING, false)
        set(value) {
            prefs.edit { putBoolean(KEY_DARK_COOKING, value) }
        }

    override var expiryReminders: Boolean
        get() = prefs.getBoolean(KEY_EXPIRY_REMINDERS, false)
        set(value) {
            prefs.edit { putBoolean(KEY_EXPIRY_REMINDERS, value) }
        }

    override var eveningReminder: Boolean
        get() = prefs.getBoolean(KEY_EVENING_REMINDER, true)
        set(value) {
            prefs.edit { putBoolean(KEY_EVENING_REMINDER, value) }
        }

    // Only the switch writes the key, so its presence is the cook's own choice (#413).
    override val eveningReminderChosen: Boolean
        get() = prefs.contains(KEY_EVENING_REMINDER)

    // A stored time outside the day (a damaged file) reads as the default.
    override var eveningReminderMinute: Int
        get() = prefs.getInt(KEY_EVENING_REMINDER_MINUTE, MealReminders.EVENING_MINUTE).takeIf { it in 0 until 24 * 60 }
            ?: MealReminders.EVENING_MINUTE
        set(value) {
            prefs.edit { putInt(KEY_EVENING_REMINDER_MINUTE, value.coerceIn(0, 24 * 60 - 1)) }
        }

    override var tonightReminder: Boolean
        get() = prefs.getBoolean(KEY_TONIGHT_REMINDER, false)
        set(value) {
            prefs.edit { putBoolean(KEY_TONIGHT_REMINDER, value) }
        }

    override var tonightReminderMinute: Int
        get() = prefs.getInt(KEY_TONIGHT_REMINDER_MINUTE, MealReminders.TONIGHT_MINUTE).takeIf { it in 0 until 24 * 60 }
            ?: MealReminders.TONIGHT_MINUTE
        set(value) {
            prefs.edit { putInt(KEY_TONIGHT_REMINDER_MINUTE, value.coerceIn(0, 24 * 60 - 1)) }
        }

    override var chefMode: Boolean
        get() = prefs.getBoolean(KEY_CHEF_MODE, false)
        set(value) {
            prefs.edit { putBoolean(KEY_CHEF_MODE, value) }
        }

    override var amountsInSteps: Boolean
        get() = prefs.getBoolean(KEY_AMOUNTS_IN_STEPS, false)
        set(value) {
            prefs.edit { putBoolean(KEY_AMOUNTS_IN_STEPS, value) }
        }

    override var recipeSort: RecipeSort
        get() = RecipeSort.fromStoredName(prefs.getString(KEY_RECIPE_SORT, null))
        set(value) {
            prefs.edit { putString(KEY_RECIPE_SORT, value.name) }
        }

    override var recipesLayout: RecipesLayout
        get() = RecipesLayout.fromStoredName(prefs.getString(KEY_RECIPES_LAYOUT, null))
        set(value) {
            prefs.edit { putString(KEY_RECIPES_LAYOUT, value.name) }
        }

    override var showTodayCard: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TODAY_CARD, true)
        set(value) {
            prefs.edit { putBoolean(KEY_SHOW_TODAY_CARD, value) }
        }

    // Stored as a Long (an epoch day); absent when the card was never closed.
    override var todayCardDismissedDay: Long?
        get() = if (prefs.contains(KEY_TODAY_CARD_DISMISSED)) prefs.getLong(KEY_TODAY_CARD_DISMISSED, 0L) else null
        set(value) {
            prefs.edit { if (value == null) remove(KEY_TODAY_CARD_DISMISSED) else putLong(KEY_TODAY_CARD_DISMISSED, value) }
        }

    /**
     * Over SharedPreferences' change listener. SharedPreferences holds its listeners weakly, so
     * the listener is a local that `awaitClose` keeps alive for as long as someone collects;
     * collection ending unregisters it. Every change re-reads every value, so the flow
     * always carries a whole, consistent snapshot, and repeats are dropped.
     */
    override val settings: Flow<AppSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(current) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(current)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    override var sampleAdded: Boolean
        get() = prefs.getBoolean(KEY_SAMPLE_ADDED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_SAMPLE_ADDED, value) }
        }

    override fun setTooltipSeen(tooltip: Tooltip, seen: Boolean) {
        prefs.edit { if (seen) putBoolean(tooltip.key, true) else remove(tooltip.key) }
    }

    private fun currentSeenTooltips(): Set<Tooltip> =
        Tooltip.entries.filterTo(mutableSetOf()) { prefs.getBoolean(it.key, false) }

    /** Over the same change listener as [settings]. */
    override val seenTooltips: Flow<Set<Tooltip>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(currentSeenTooltips()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(currentSeenTooltips())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    override var useUps: Map<Long, Long>
        get() = UseUpLog.decode(prefs.getString(KEY_USE_UP, null))
        set(value) {
            prefs.edit { if (value.isEmpty()) remove(KEY_USE_UP) else putString(KEY_USE_UP, UseUpLog.encode(value)) }
        }

    override var planMealKeys: List<String>?
        get() = PlanChoices.decode(prefs.getString(KEY_PLAN_MEALS, null))
        set(value) {
            prefs.edit {
                if (value.isNullOrEmpty()) remove(KEY_PLAN_MEALS) else putString(KEY_PLAN_MEALS, PlanChoices.encode(value))
            }
        }

    private companion object {
        const val KEY_PLAN_MEALS = "plan_my_week_meals"
        const val KEY_USE_UP = "pantry_use_up"
        const val KEY_SAMPLE_ADDED = "tour_sample_added"
        const val KEY_SYSTEM = "unit_system"
        const val KEY_LIQUIDS = "convert_liquids"
        const val KEY_DARK_COOKING = "dark_while_cooking"
        const val KEY_TEMPERATURE_UNIT = "temperature_unit"
        const val KEY_EXPIRY_REMINDERS = "expiry_reminders"
        const val KEY_EVENING_REMINDER = "evening_reminder"
        const val KEY_EVENING_REMINDER_MINUTE = "evening_reminder_minute"
        const val KEY_TONIGHT_REMINDER = "tonight_reminder"
        const val KEY_TONIGHT_REMINDER_MINUTE = "tonight_reminder_minute"
        const val KEY_CHEF_MODE = "chef_mode"
        const val KEY_AMOUNTS_IN_STEPS = "amounts_in_steps"
        const val KEY_RECIPE_SORT = "recipe_sort"
        const val KEY_RECIPES_LAYOUT = "recipes_layout"
        const val KEY_SHOW_TODAY_CARD = "show_today_card"
        const val KEY_TODAY_CARD_DISMISSED = "today_card_dismissed_day"
    }
}
