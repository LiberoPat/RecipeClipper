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
        FlagDefinition("freeTier", "The free tier", debugDefault = true, releaseDefault = false, issue = 107)
    )

    @Test fun `each build type gets its own default`() {
        assertTrue(FeatureFlags(FakeFeatureFlagStore(), definitions, isDebug = true).isOn(Flag.FREE_TIER))
        assertFalse(FeatureFlags(FakeFeatureFlagStore(), definitions, isDebug = false).isOn(Flag.FREE_TIER))
    }

    @Test fun `an override wins over the default, and reset returns to it`() {
        val store = FakeFeatureFlagStore()
        val flags = FeatureFlags(store, definitions, isDebug = false)

        flags.set(Flag.FREE_TIER, true)
        assertTrue(flags.isOn(Flag.FREE_TIER))
        assertTrue(flags.isOverridden(Flag.FREE_TIER))
        assertEquals(mapOf("freeTier" to true), store.overrides.value)

        flags.reset()
        assertFalse(flags.isOn(Flag.FREE_TIER))
        assertFalse(flags.isOverridden(Flag.FREE_TIER))
    }

    @Test fun `choosing the default stores no override`() {
        val store = FakeFeatureFlagStore(mapOf("freeTier" to true))
        val flags = FeatureFlags(store, definitions, isDebug = false)

        flags.set(Flag.FREE_TIER, false)

        assertEquals(emptyMap<String, Boolean>(), store.overrides.value)
    }

    @Test fun `values follow the store`() = runTest {
        val flags = FeatureFlags(FakeFeatureFlagStore(), definitions, isDebug = false)
        assertFalse(flags.values.first().isOn(Flag.FREE_TIER))

        flags.set(Flag.FREE_TIER, true)

        assertTrue(flags.values.first().isOn(Flag.FREE_TIER))
        assertTrue(flags.current.isOn(Flag.FREE_TIER))
    }

    @Test fun `a flag missing from the registry is off`() {
        assertFalse(FeatureFlags(FakeFeatureFlagStore(), emptyList(), isDebug = true).isOn(Flag.FREE_TIER))
    }

    @Test fun `a stored override for a retired flag is ignored (#242)`() {
        // mealPlan, amountsInSteps and cookedPhotos were retired (#242); a phone may still hold
        // an override for one from Developer settings. It names no Flag, so it changes nothing.
        val retired = mapOf("mealPlan" to false, "amountsInSteps" to false, "cookedPhotos" to false)
        val flags = FeatureFlags(FakeFeatureFlagStore(retired), FlagRegistry.definitions, isDebug = false)
        val clean = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)

        assertEquals(clean.current, flags.current)
        Flag.entries.forEach { assertFalse(flags.isOverridden(it)) }
        flags.reset()
        assertEquals(clean.current, flags.current)
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
        // Gradle runs unit tests in the module directory; the model is in :core (#238), Flag too.
        val sources = listOf(File("src/main/java"), File("../core/src/main/java")).flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "FeatureFlags.kt" && it.name != "Flag.kt" }
                .map { it.readText() }
                .toList()
        }
        assertTrue("no sources found", sources.isNotEmpty())
        Flag.entries.forEach { flag ->
            assertTrue("Flag.${flag.name} is not used anywhere: retire it", sources.any { "Flag.${flag.name}" in it })
        }
    }
}
