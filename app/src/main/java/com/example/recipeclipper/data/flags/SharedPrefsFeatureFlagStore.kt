package com.example.recipeclipper.data.flags

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [FeatureFlagStore]: the SharedPreferences file `feature_flags`, apart from the
 * user's settings in `unit_preferences`, so resetting flags can never touch those. Only this
 * app writes it, so the in-memory copy is updated on each write rather than by a listener.
 */
@Singleton
class SharedPrefsFeatureFlagStore @Inject constructor(
    @ApplicationContext context: Context
) : FeatureFlagStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _overrides = MutableStateFlow(read())
    override val overrides: StateFlow<Map<String, Boolean>> = _overrides.asStateFlow()

    override fun setOverride(key: String, value: Boolean?) {
        prefs.edit { if (value == null) remove(key) else putBoolean(key, value) }
        _overrides.value = read()
    }

    override fun clear() {
        prefs.edit { clear() }
        _overrides.value = emptyMap()
    }

    private fun read(): Map<String, Boolean> =
        prefs.all.mapNotNull { (key, value) -> (value as? Boolean)?.let { key to it } }.toMap()

    companion object {
        const val FILE = "feature_flags"
    }
}
