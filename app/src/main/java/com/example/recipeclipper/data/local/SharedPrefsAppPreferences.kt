package com.example.recipeclipper.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.recipeclipper.data.model.RecipeSort
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.Tip
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.data.model.WelcomeState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** The real, `SharedPreferences`-backed [AppPreferences] and [TourPreferences]. The ViewModel
 *  only ever sees an interface, never the `Context` inside this. */
@Singleton
class SharedPrefsAppPreferences @Inject constructor(
    @ApplicationContext context: Context
) : AppPreferences, TourPreferences {

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

    override var welcome: WelcomeState
        get() = WelcomeState.fromStoredName(prefs.getString(KEY_WELCOME, null))
        set(value) {
            prefs.edit { putString(KEY_WELCOME, value.name) }
        }

    override var sampleAdded: Boolean
        get() = prefs.getBoolean(KEY_SAMPLE_ADDED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_SAMPLE_ADDED, value) }
        }

    override fun setTipSeen(tip: Tip, seen: Boolean) {
        prefs.edit { putBoolean(tip.key, seen) }
    }

    private fun currentSeenTips(): Set<Tip> = Tip.entries.filterTo(mutableSetOf()) { prefs.getBoolean(it.key, false) }

    /** Over the same change listener as [settings]. */
    override val seenTips: Flow<Set<Tip>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(currentSeenTips()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(currentSeenTips())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    private companion object {
        const val KEY_WELCOME = "tour_welcome"
        const val KEY_SAMPLE_ADDED = "tour_sample_added"
        const val KEY_SYSTEM = "unit_system"
        const val KEY_LIQUIDS = "convert_liquids"
        const val KEY_DARK_COOKING = "dark_while_cooking"
        const val KEY_TEMPERATURE_UNIT = "temperature_unit"
        const val KEY_EXPIRY_REMINDERS = "expiry_reminders"
        const val KEY_CHEF_MODE = "chef_mode"
        const val KEY_AMOUNTS_IN_STEPS = "amounts_in_steps"
        const val KEY_RECIPE_SORT = "recipe_sort"
    }
}
