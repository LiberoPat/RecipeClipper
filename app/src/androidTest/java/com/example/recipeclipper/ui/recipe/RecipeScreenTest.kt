package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reading view: what it opens on, the servings-and-units row, ticking ingredients, the
 * bookmark (filled once the recipe is in any list), deleting, and what "Share" sends.
 *
 * Share itself fires a system chooser, which a test can't dismiss reliably and which would land
 * on whoever's device this runs on. So the share tests stop at the seam the button calls,
 * [RecipeViewModel.shareText], after driving the screen into the state being shared.
 */
@RunWith(AndroidJUnit4::class)
class RecipeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val fixture = RecipeScreenFixture()

    private fun show() = fixture.show(compose)

    // --- What it opens on ---

    @Test
    fun theReadingViewShowsTitleTimesServingsAndIngredients() {
        show()

        compose.onNodeWithText("Test Stew").assertIsDisplayed()
        compose.onNodeWithText("PREP").assertIsDisplayed()
        compose.onNodeWithText("10m").assertIsDisplayed()
        compose.onNodeWithText("TOTAL").assertIsDisplayed()
        compose.onNodeWithText("30m").assertIsDisplayed()
        compose.onNodeWithText("Serves").assertIsDisplayed()
        compose.onNodeWithText("As written").assertIsDisplayed()
        compose.onNodeWithText("2 cups flour").assertIsDisplayed()
        compose.onNodeWithText("1 cup milk").assertIsDisplayed()
        compose.onNodeWithText("Start cooking").assertIsDisplayed()
    }

    @Test
    fun changingServingsScalesTheIngredientsInPlace() {
        show()

        compose.onNodeWithContentDescription("Decrease servings").performClick()
        compose.onNodeWithContentDescription("Decrease servings").performClick()

        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("1/2 cup milk").assertIsDisplayed()
        compose.onNodeWithText("Original: 4 servings").assertIsDisplayed()
    }

    @Test
    fun choosingAUnitSystemConvertsAndIsRememberedForEveryRecipe() {
        show()

        compose.onNodeWithText("As written").performClick()
        compose.onNodeWithText("Metric").performClick()

        compose.onNodeWithText("240 g flour").assertIsDisplayed()
        compose.onNodeWithText("240 ml milk").assertIsDisplayed()
        assertEquals(UnitSystem.METRIC, fixture.preferences.unitSystem)
    }

    @Test
    fun tickingAnIngredientIsSavedAsItChanges() {
        show()

        compose.onNodeWithText("2 cups flour").assertIsOff()
        compose.onNodeWithText("2 cups flour").performClick()

        compose.onNodeWithText("2 cups flour").assertIsOn()
        assertEquals(listOf(RecipeScreenFixture.RECIPE_ID to setOf(0)), fixture.recipes.setCheckedCalls)
    }

    // --- Bookmark ---

    @Test
    fun aRecipeInNoListHasAnEmptyBookmark() {
        show()

        compose.onNodeWithContentDescription("Save to a list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Saved to a list").assertDoesNotExist()
    }

    /** "Saved" is membership in any list, Favorites or not. */
    @Test
    fun aRecipeInAnyListHasAFilledBookmark() {
        fixture.lists.membership.value = setOf(RecipeScreenFixture.RECIPE_ID to 2L)
        show()

        compose.onNodeWithContentDescription("Saved to a list").assertIsDisplayed()
    }

    @Test
    fun theBookmarkOpensTheSheetAndFillsOnceTicked() {
        show()

        compose.onNodeWithContentDescription("Save to a list").performClick()
        compose.onNodeWithText("Save to").assertIsDisplayed()
        compose.onNodeWithText("Dinner").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            fixture.lists.membership.value == setOf(RecipeScreenFixture.RECIPE_ID to 2L)
        }
        // Behind the sheet, the bookmark reads the same membership the sheet just wrote.
        compose.onNodeWithContentDescription("Saved to a list").assertExists()
    }

    // --- Deleting ---

    @Test
    fun deletingAsksFirstThenDeletesAndLeaves() {
        show()

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete \"Test Stew\"?").assertIsDisplayed()
        assertTrue(fixture.recipes.deleteCalls.isEmpty())

        compose.onNodeWithText("Delete").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { fixture.backs == 1 }
        assertEquals(listOf(RecipeScreenFixture.RECIPE_ID), fixture.recipes.deleteCalls)
    }

    @Test
    fun cancellingTheDeleteKeepsTheRecipe() {
        show()

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText("Delete \"Test Stew\"?").assertDoesNotExist()
        compose.onNodeWithText("Test Stew").assertIsDisplayed()
        assertTrue(fixture.recipes.deleteCalls.isEmpty())
        assertEquals(0, fixture.backs)
    }

    // --- Sharing out ---

    @Test
    fun shareSitsInTheReadingView() {
        show()

        compose.onNodeWithContentDescription("Share recipe").assertIsDisplayed()
    }

    /** As shown on screen: scaled, converted, plain text, no source link. */
    @Test
    fun shareSendsTheRecipeAsScaledAndConvertedOnScreen() {
        show()

        repeat(4) { compose.onNodeWithContentDescription("Increase servings").performClick() }
        compose.onNodeWithText("As written").performClick()
        compose.onNodeWithText("Metric").performClick()
        compose.onNodeWithText("480 g flour").assertIsDisplayed()
        compose.onNodeWithText("480 ml milk").assertIsDisplayed()

        assertEquals(
            """
            Test Stew

            Serves 8 (originally 4)
            Prep 10m · Cook 20m · Total 30m

            INGREDIENTS
            480 g flour
            480 ml milk

            INSTRUCTIONS
            1. Preheat the oven to 350°F.
            2. Simmer for 20 minutes.
            3. Stir in the milk.
            4. Serve.
            """.trimIndent(),
            fixture.viewModel.shareText()
        )
    }
}
