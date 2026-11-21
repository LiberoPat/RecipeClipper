package com.example.recipeclipper.data.flags

import com.example.recipeclipper.fake.FakeFeatureFlagStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Library first (#375) is on by default in debug and release, and needs oneCookbook (#345) for Lists to stay reachable. */
class LibraryFirstFlagTest {

    private fun flags(store: FakeFeatureFlagStore = FakeFeatureFlagStore(), debug: Boolean) =
        FeatureFlags(store, FlagRegistry.definitions, isDebug = debug)

    @Test fun `it is on by default in debug and release`() = runTest {
        for (debug in listOf(true, false)) {
            assertTrue(flags(debug = debug).isOn(Flag.LIBRARY_FIRST))
            assertTrue(flags(debug = debug).values.first().libraryFirst)
        }
    }

    @Test fun `with one cookbook off the tab keeps opening Home`() = runTest {
        val store = FakeFeatureFlagStore(mapOf("oneCookbook" to false))

        assertTrue(flags(store, debug = false).isOn(Flag.LIBRARY_FIRST))
        assertFalse(flags(store, debug = false).values.first().libraryFirst)
    }

    @Test fun `switched off it is off`() = runTest {
        val store = FakeFeatureFlagStore(mapOf("libraryFirst" to false))

        assertFalse(flags(store, debug = true).values.first().libraryFirst)
    }
}
