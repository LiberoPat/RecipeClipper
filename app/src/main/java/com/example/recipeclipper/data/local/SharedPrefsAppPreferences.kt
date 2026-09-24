package com.example.recipeclipper.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** The real, `SharedPreferences`-backed [AppPreferences]. The ViewModel only ever sees the
 *  interface, never the `Context` inside this. */
@Singleton
class SharedPrefsAppPreferences @Inject constructor(@ApplicationContext context: Context) : AppPreferences {

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

    /**
     * Over SharedPreferences' change listener. SharedPreferences holds its listeners weakly, so
     * the listener is a local that `awaitClose` keeps alive for as long as someone collects;
     * collection ending unregisters it. Every change re-reads all four values, so the flow
     * always carries a whole, consistent snapshot, and repeats are dropped.
     */
    override val settings: Flow<AppSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(current) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(current)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    private companion object {
        const val KEY_SYSTEM = "unit_system"
        const val KEY_LIQUIDS = "convert_liquids"
        const val KEY_DARK_COOKING = "dark_while_cooking"
        const val KEY_TEMPERATURE_UNIT = "temperature_unit"
    }
}
