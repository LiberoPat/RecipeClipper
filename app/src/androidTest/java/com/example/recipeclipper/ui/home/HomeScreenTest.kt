package com.example.recipeclipper.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeRecipeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first Compose tests in this project. They exist because every ViewModel behind Home was
 * already covered and 188 JVM tests stayed green through a crash that made the app unusable:
 * a duplicate LazyColumn key, which lives entirely in the view.
 *
 * The screens take their ViewModel as a parameter (defaulting to `hiltViewModel()`), so a test
 * can build a real ViewModel over a fake repository and skip Hilt altogether. What's under test
 * is the actual wiring — repository to ViewModel to pixels — not a stub of it.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()

    private fun summary(id: Long, title: String) = RecipeSummary(
        id = id,
        title = title,
        imageUrl = null,
        totalTime = null,
        lastViewedAt = id,
        isSaved = false
    )

    private class Taps {
        var recipe: Long? = null
        var history = 0
        var lists = 0
        var settings = 0
        var url: String? = null
    }

    private fun show(): Taps {
        val taps = Taps()
        compose.setContent {
            HomeScreen(
                onOpenUrl = { taps.url = it },
                onOpenRecipe = { taps.recipe = it },
                onOpenHistory = { taps.history++ },
                onOpenLists = { taps.lists++ },
                onOpenSettings = { taps.settings++ },
                viewModel = HomeViewModel(repository)
            )
        }
        return taps
    }

    @Test
    fun theNewestRecipeIsContinueCookingAndTheRestAreRecentlyViewed() {
        repository.recent.value = listOf(summary(1, "Newest"), summary(2, "Older"))
        show()

        compose.onNodeWithText("Continue cooking").assertIsDisplayed()
        compose.onNodeWithText("Recently viewed").assertIsDisplayed()
        compose.onNodeWithText("Newest").assertIsDisplayed()
        compose.onNodeWithText("Older").assertIsDisplayed()
    }

    /**
     * The regression guard for the crash that prompted these tests: Home rendering a full set
     * of rows at once. Duplicate LazyColumn keys throw during measure, so this fails loudly
     * rather than subtly if the keying breaks again.
     */
    @Test
    fun aFullHomeRendersWithoutDuplicateKeys() {
        repository.recent.value = (1L..10L).map { summary(it, "Recipe $it") }
        show()

        compose.onNodeWithText("Recipe 1").assertIsDisplayed() // continue cooking
        compose.onNodeWithText("Recipe 2").assertIsDisplayed() // first recently viewed
    }

    /** Six are fetched, one becomes "Continue cooking", so at most five are listed below it. */
    @Test
    fun recentlyViewedStopsAtFive() {
        repository.recent.value = (1L..10L).map { summary(it, "Recipe $it") }
        show()

        compose.onNodeWithText("Recipe 6").assertIsDisplayed()
        compose.onNodeWithText("Recipe 7").assertDoesNotExist()
    }

    @Test
    fun thereIsNoSavedSection() {
        repository.recent.value = listOf(summary(1, "Newest"))
        show()

        // Removed deliberately: Lists answers this better. A recipe in two sections under one
        // key is also what crashed the screen.
        compose.onNodeWithText("Saved").assertDoesNotExist()
    }

    @Test
    fun tappingARecipeOpensIt() {
        repository.recent.value = listOf(summary(1, "Newest"), summary(7, "Older"))
        val taps = show()

        compose.onNodeWithText("Older").performClick()

        assertEquals(7L, taps.recipe)
    }

    @Test
    fun theSettingsGearOpensSettings() {
        val taps = show()

        compose.onNodeWithContentDescription("Settings").performClick()

        assertEquals(1, taps.settings)
    }

    @Test
    fun historyAndListsRowsOpenTheirScreens() {
        val taps = show()

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Lists").performClick()

        assertEquals(1, taps.history)
        assertEquals(1, taps.lists)
    }

    /** All three entries are unconditional — they used to come and go with the database. */
    @Test
    fun theNavEntriesAreThereWithAnEmptyDatabase() {
        show()

        compose.onNodeWithText("History").assertIsDisplayed()
        compose.onNodeWithText("Lists").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun theEmptyHintShowsWhenThereIsNothingToResume() {
        show()

        compose.onNodeWithText("Recipes you open will show up here.").assertIsDisplayed()
    }

    @Test
    fun theEmptyHintGoesOnceThereIsARecipe() {
        repository.recent.value = listOf(summary(1, "Newest"))
        show()

        compose.onNodeWithText("Recipes you open will show up here.").assertDoesNotExist()
    }

    @Test
    fun somethingThatIsNotALinkIsRejectedRatherThanOpened() {
        val taps = show()

        compose.onNodeWithText("Recipe URL").performTextInput("not a link")
        compose.onNodeWithText("Go").performClick()

        compose.onNodeWithText("That doesn't look like a link.").assertIsDisplayed()
        assertNull(taps.url)
    }

    @Test
    fun aLinkIsHandedUpToBeOpened() {
        val taps = show()

        compose.onNodeWithText("Recipe URL").performTextInput("example.com/chicken")
        compose.onNodeWithText("Go").performClick()

        assertTrue("expected a url, got ${taps.url}", taps.url?.contains("example.com/chicken") == true)
    }
}
