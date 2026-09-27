package com.example.recipeclipper.data.flags

import com.example.recipeclipper.fake.FakeFeatureFlagStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeatureFlagsTest {

    private val definitions = listOf(
        FlagDefinition("mealPlan", "The meal plan", debugDefault = true, releaseDefault = false, issue = 47)
    )

    @Test fun `each build type gets its own default`() {
        assertTrue(FeatureFlags(FakeFeatureFlagStore(), definitions, isDebug = true).isOn(Flag.MEAL_PLAN))
        assertFalse(FeatureFlags(FakeFeatureFlagStore(), definitions, isDebug = false).isOn(Flag.MEAL_PLAN))
    }

    @Test fun `an override wins over the default, and reset returns to it`() {
        val store = FakeFeatureFlagStore()
        val flags = FeatureFlags(store, definitions, isDebug = false)

        flags.set(Flag.MEAL_PLAN, true)
        assertTrue(flags.isOn(Flag.MEAL_PLAN))
        assertTrue(flags.isOverridden(Flag.MEAL_PLAN))
        assertEquals(mapOf("mealPlan" to true), store.overrides.value)

        flags.reset()
        assertFalse(flags.isOn(Flag.MEAL_PLAN))
        assertFalse(flags.isOverridden(Flag.MEAL_PLAN))
    }

    @Test fun `choosing the default stores no override`() {
        val store = FakeFeatureFlagStore(mapOf("mealPlan" to true))
        val flags = FeatureFlags(store, definitions, isDebug = false)

        flags.set(Flag.MEAL_PLAN, false)

        assertEquals(emptyMap<String, Boolean>(), store.overrides.value)
    }

    @Test fun `values follow the store`() = runTest {
        val flags = FeatureFlags(FakeFeatureFlagStore(), definitions, isDebug = false)
        assertFalse(flags.values.first().isOn(Flag.MEAL_PLAN))

        flags.set(Flag.MEAL_PLAN, true)

        assertTrue(flags.values.first().isOn(Flag.MEAL_PLAN))
        assertTrue(flags.current.isOn(Flag.MEAL_PLAN))
    }

    @Test fun `a flag missing from the registry is off`() {
        assertFalse(FeatureFlags(FakeFeatureFlagStore(), emptyList(), isDebug = true).isOn(Flag.MEAL_PLAN))
    }

    @Test fun `parses the registry format`() {
        val parsed = FlagRegistry.parse(
            """{"flags":[{"key":"a","description":"A","defaults":{"debug":true,"release":false},"issue":9}]}"""
        )
        assertEquals(listOf(FlagDefinition("a", "A", true, false, 9)), parsed)
    }

    // The registry check (#87): shared/flags.json and the code agree, so a dead flag is noticed.

    @Test fun `every flag in flags json is a Flag, and every Flag is in flags json`() {
        assertEquals(
            FlagRegistry.definitions.map { it.key }.sorted(),
            Flag.entries.map { it.key }.sorted()
        )
    }

    @Test fun `every Flag is referenced by the app's code`() {
        // Gradle runs unit tests in the module directory.
        val sources = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "FeatureFlags.kt" }
            .map { it.readText() }
            .toList()
        assertTrue("no sources found", sources.isNotEmpty())
        Flag.entries.forEach { flag ->
            assertTrue("Flag.${flag.name} is not used anywhere: retire it", sources.any { "Flag.${flag.name}" in it })
        }
    }
}
