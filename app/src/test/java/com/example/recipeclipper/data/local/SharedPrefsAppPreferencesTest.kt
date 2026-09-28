package com.example.recipeclipper.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.RecipeSort
import com.example.recipeclipper.data.model.Tooltip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Recipes screen's sort, the tour and the pantry use-up log, as stored in `unit_preferences` (iOS: PreferencesAndSourceTests). */
@RunWith(AndroidJUnit4::class)
class SharedPrefsAppPreferencesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = context.getSharedPreferences("unit_preferences", Context.MODE_PRIVATE)

    @Before fun clear() {
        file.edit().clear().commit()
    }

    @Test fun `recipe sort defaults to recently viewed`() {
        assertEquals(RecipeSort.RECENTLY_VIEWED, SharedPrefsAppPreferences(context).recipeSort)
    }

    @Test fun `recipe sort round-trips by name, under recipe_sort, and a new instance reads it`() {
        SharedPrefsAppPreferences(context).recipeSort = RecipeSort.DATE_ADDED

        assertEquals("DATE_ADDED", file.getString("recipe_sort", null))
        assertEquals(RecipeSort.DATE_ADDED, SharedPrefsAppPreferences(context).recipeSort)
        assertEquals(RecipeSort.DATE_ADDED, SharedPrefsAppPreferences(context).current.recipeSort)
    }

    @Test fun `an unknown stored sort reads as recently viewed`() {
        file.edit().putString("recipe_sort", "RATING").commit()

        assertEquals(RecipeSort.RECENTLY_VIEWED, SharedPrefsAppPreferences(context).recipeSort)
    }

    @Test fun `the tour's state round-trips under its keys, and the tooltips flow follows every change`() = runBlocking {
        val preferences = SharedPrefsAppPreferences(context)
        assertFalse(preferences.sampleAdded)
        assertEquals(emptySet<Tooltip>(), preferences.seenTooltips.first())

        preferences.sampleAdded = true
        preferences.setTooltipSeen(Tooltip.COOK_TIMER, true)

        assertTrue(file.getBoolean("tour_sample_added", false))
        assertTrue(file.getBoolean("tooltip_cook_timer", false))
        val again = SharedPrefsAppPreferences(context)
        assertTrue(again.sampleAdded)
        assertEquals(setOf(Tooltip.COOK_TIMER), again.seenTooltips.first())

        preferences.setTooltipSeen(Tooltip.COOK_TIMER, false)
        assertFalse("unseen again removes the key", file.contains("tooltip_cook_timer"))
        assertEquals(emptySet<Tooltip>(), again.seenTooltips.first())
    }

    @Test fun `everyone starts with every tooltip unseen, whatever #151 stored`() = runBlocking {
        // #151's keys are ignored: someone who dismissed every tip, or skipped the tour, still
        // gets the tooltips (the owner's decision, reversing #163).
        file.edit()
            .putString("tour_welcome", "SEEN")
            .putBoolean("tour_tip_recipe", true)
            .putBoolean("tour_tip_cook_mode", true)
            .commit()
        assertEquals(emptySet<Tooltip>(), SharedPrefsAppPreferences(context).seenTooltips.first())
    }

    @Test fun `the pantry use-up log round-trips under pantry_use_up, skipping what it can't read`() {
        SharedPrefsAppPreferences(context).useUps = mapOf(7L to 1_000L, 12L to 2_000L)
        assertEquals("7:1000,12:2000", file.getString("pantry_use_up", null))
        assertEquals(mapOf(7L to 1_000L, 12L to 2_000L), SharedPrefsAppPreferences(context).useUps)

        file.edit().putString("pantry_use_up", "7:1000,x:5,9,:3,12:2000").commit()
        assertEquals(mapOf(7L to 1_000L, 12L to 2_000L), SharedPrefsAppPreferences(context).useUps)

        SharedPrefsAppPreferences(context).useUps = emptyMap()
        assertFalse(file.contains("pantry_use_up"))
    }
}
