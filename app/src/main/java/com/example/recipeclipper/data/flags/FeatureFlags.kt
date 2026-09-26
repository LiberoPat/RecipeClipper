package com.example.recipeclipper.data.flags

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Every feature flag (#87), one constant per entry in `shared/flags.json`, which both apps
 * read. `FeatureFlagRegistryTest` fails if the two lists differ, or if a flag is no longer
 * referenced anywhere: retiring a flag deletes it here, in flags.json and its branches in one PR.
 */
enum class Flag(val key: String) {
    /** The meal plan (#47, #49–#51): the bottom tabs and the recipe screen's plan actions. */
    MEAL_PLAN("mealPlan"),

    /** Ingredient amounts inside steps (#101): the Settings switch and what it shows. */
    AMOUNTS_IN_STEPS("amountsInSteps"),

    /** The free tier (#107): 20 recipes, and the one-time unlock for unlimited ones. */
    FREE_TIER("freeTier");

    companion object {
        fun forKey(key: String): Flag? = entries.firstOrNull { it.key == key }
    }
}

/** One flag as `shared/flags.json` declares it. */
data class FlagDefinition(
    val key: String,
    val description: String,
    val debugDefault: Boolean,
    val releaseDefault: Boolean,
    val issue: Int
) {
    fun defaultFor(isDebug: Boolean) = if (isDebug) debugDefault else releaseDefault
}

/** `shared/flags.json`, read from the Java resources like the shared tables. Pure. */
object FlagRegistry {
    const val RESOURCE = "/flags.json"

    val definitions: List<FlagDefinition> by lazy {
        val stream = FlagRegistry::class.java.getResourceAsStream(RESOURCE)
            ?: error("$RESOURCE is missing from the resources")
        parse(stream.bufferedReader().use { it.readText() })
    }

    fun parse(json: String): List<FlagDefinition> {
        val flags = JSONObject(json).getJSONArray("flags")
        return (0 until flags.length()).map { i ->
            val flag = flags.getJSONObject(i)
            val defaults = flag.getJSONObject("defaults")
            FlagDefinition(
                key = flag.getString("key"),
                description = flag.getString("description"),
                debugDefault = defaults.getBoolean("debug"),
                releaseDefault = defaults.getBoolean("release"),
                issue = flag.getInt("issue")
            )
        }
    }
}

/**
 * Where the Developer settings overrides live: only the flags that differ from their default,
 * by key. [SharedPrefsFeatureFlagStore] is the real one (its own prefs file, never
 * `unit_preferences`); tests use `FakeFeatureFlagStore`.
 */
interface FeatureFlagStore {
    /** The current overrides, then every change. */
    val overrides: StateFlow<Map<String, Boolean>>

    /** [value] null removes the override, so the flag follows its default again. */
    fun setOverride(key: String, value: Boolean?)

    fun clear()
}

/** Which flags are on, at one moment. */
data class FlagValues(private val on: Set<Flag>) {
    fun isOn(flag: Flag) = flag in on

    companion object {
        /** Everything off: what a composable sees when nothing provides the real values. */
        val ALL_OFF = FlagValues(emptySet())
    }
}

/**
 * Typed access to the flags: each is its build type's default from `shared/flags.json`
 * unless the store holds an override. Platform-free, so ViewModels and tests use it as is.
 */
class FeatureFlags(
    private val store: FeatureFlagStore,
    val definitions: List<FlagDefinition>,
    private val isDebug: Boolean
) {
    private val byKey = definitions.associateBy { it.key }

    fun default(flag: Flag): Boolean = byKey[flag.key]?.defaultFor(isDebug) ?: false

    fun definition(flag: Flag): FlagDefinition? = byKey[flag.key]

    fun isOn(flag: Flag): Boolean = store.overrides.value[flag.key] ?: default(flag)

    fun isOverridden(flag: Flag): Boolean = flag.key in store.overrides.value

    val current: FlagValues get() = valuesOf(store.overrides.value)

    /** The current values, then every change. */
    val values: Flow<FlagValues> = store.overrides.map(::valuesOf).distinctUntilChanged()

    /** Choosing the default removes the override, so a later change of default still applies. */
    fun set(flag: Flag, on: Boolean) {
        store.setOverride(flag.key, if (on == default(flag)) null else on)
    }

    fun reset() = store.clear()

    /**
     * Developer settings' "Unlocked" (#107): the unlock counts as bought, to test the unlimited
     * library without a store. Kept in the same store under a key that is no flag's, so Reset
     * clears it too.
     */
    val unlockedOverride: Boolean get() = store.overrides.value[UNLOCKED_OVERRIDE] == true

    /** [unlockedOverride], then every change. */
    val unlockedOverrides: Flow<Boolean> =
        store.overrides.map { it[UNLOCKED_OVERRIDE] == true }.distinctUntilChanged()

    fun setUnlockedOverride(on: Boolean) = store.setOverride(UNLOCKED_OVERRIDE, if (on) true else null)

    private fun valuesOf(overrides: Map<String, Boolean>) =
        FlagValues(Flag.entries.filter { overrides[it.key] ?: default(it) }.toSet())

    companion object {
        /** The store key of [unlockedOverride]; the same on iOS. */
        const val UNLOCKED_OVERRIDE = "override.unlocked"
    }
}
