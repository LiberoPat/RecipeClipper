package com.example.recipeclipper.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.RecipeSort
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Recipes screen's sort, as stored in `unit_preferences` (iOS: PreferencesAndSourceTests). */
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
}
