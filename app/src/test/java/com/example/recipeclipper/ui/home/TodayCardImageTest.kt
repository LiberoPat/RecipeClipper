package com.example.recipeclipper.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagValues
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home's Today card (#402) with the no-photo image (#403): a recipe with no photo gets the designed band. */
@RunWith(AndroidJUnit4::class)
class TodayCardImageTest {

    @get:Rule
    val compose = createComposeRule()

    private fun meal(recipeId: Long? = 7, imageUrl: String? = null) = TodayMeal(
        entryId = 1, mealType = "Dinner", recipeId = recipeId, title = "Lentil soup", imageUrl = imageUrl,
        servings = 4, recipeUid = "uid-7", language = "en",
        ingredients = listOf("1 cup red lentils", "2 carrots, diced")
    )

    private fun show(lead: TodayMeal, flagOn: Boolean = true) {
        compose.setContent {
            val flags = if (flagOn) FlagValues(setOf(Flag.NO_PHOTO_IMAGE)) else FlagValues.ALL_OFF
            CompositionLocalProvider(LocalFlagValues provides flags) {
                RecipeClipperTheme { TodayCard(TodayCard(lead, emptyList()), onStartCooking = {}, onOpen = {}) }
            }
        }
    }

    @Test
    fun aRecipeWithNoPhotoShowsItsDesignedImageAsTheBand() {
        show(meal())
        compose.onNodeWithTag("noPhoto.label", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aPlannedNoteKeepsThePlainBand() {
        show(meal(recipeId = null))
        assertEquals(0, compose.onAllNodesWithTag("noPhoto.label", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("noPhoto.monogram", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun withTheFlagOffTheBandIsAsItWas() {
        show(meal(), flagOn = false)
        assertEquals(0, compose.onAllNodesWithTag("noPhoto.label", useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
