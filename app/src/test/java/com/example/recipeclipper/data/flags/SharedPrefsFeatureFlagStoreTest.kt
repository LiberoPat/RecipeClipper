package com.example.recipeclipper.data.flags

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Developer settings overrides as stored in `feature_flags` (iOS: FeatureFlagsTests). */
@RunWith(AndroidJUnit4::class)
class SharedPrefsFeatureFlagStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = context.getSharedPreferences(SharedPrefsFeatureFlagStore.FILE, Context.MODE_PRIVATE)

    @Before fun clear() {
        file.edit().clear().commit()
    }

    @Test fun `an override saved for a retired flag, or a stray value, is read harmlessly (#242)`() {
        file.edit()
            .putBoolean("mealPlan", false)
            .putBoolean("amountsInSteps", false)
            .putBoolean("cookedPhotos", false)
            .putString("reddit", "not a boolean")
            .commit()

        val flags = FeatureFlags(SharedPrefsFeatureFlagStore(context), FlagRegistry.definitions, isDebug = false)
        val clean = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)

        assertEquals(clean.current, flags.current)
        Flag.entries.forEach { assertFalse(it.key, flags.isOverridden(it)) }

        // Reset clears the strays with everything else.
        flags.reset()
        assertEquals(emptyMap<String, Any?>(), file.all)
    }
}
