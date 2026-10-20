package com.example.recipeclipper.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.SavedTimer
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val lists = FakeListRepository()

    private fun summary(id: Long, title: String) = RecipeSummary(
        id = id,
        title = title,
        imageUrl = null,
        totalTime = null,
        lastViewedAt = id,
        isSaved = false
    )

    private fun recipe(id: Long, cook: CookProgress) = Recipe(
        name = "Recipe $id", image = null, ingredients = listOf("1 egg"),
        instructions = (1..4).map { "Step $it" }, prepTime = null, cookTime = null,
        totalTime = null, yield = null, sourceUrl = "https://example.com/$id", id = id, cook = cook
    )

    private class Taps {
        var recipe: Long? = null
        var resumed: Long? = null
        var recipes = 0
        var lists = 0
        var settings = 0
        var newRecipe = 0
        var url: String? = null
    }

    private fun show(): Taps {
        val taps = Taps()
        // Built outside setContent: a ViewModel constructed inside a composable would be
        // rebuilt on every recomposition. A fixed clock, so a timer's time left is known.
        val viewModel = HomeViewModel(repository, lists = lists, clock = Clock { NOW })
        compose.setContent {
            HomeScreen(
                onOpenUrl = { taps.url = it },
                onOpenRecipe = { taps.recipe = it },
                onOpenRecipes = { taps.recipes++ },
                onOpenLists = { taps.lists++ },
                onOpenSettings = { taps.settings++ },
                onNewRecipe = { taps.newRecipe++ },
                onResumeCooking = { taps.resumed = it },
                viewModel = viewModel
            )
        }
        return taps
    }

    @Test
    fun theNewestRecipeIsContinueCookingAndTheRestAreRecentlyViewed() {
        repository.recent.value = listOf(summary(1, "Newest"), summary(2, "Older"))
        show()

        compose.onNodeWithText("CONTINUE COOKING").assertIsDisplayed()
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

        // A row of photos (#286): the fifth is off to the side until the row scrolls.
        compose.onNodeWithTag("home.recent").performScrollToNode(hasText("Recipe 6"))
        compose.onNodeWithText("Recipe 6").assertIsDisplayed()
        compose.onNodeWithTag("home.recent").performScrollToIndex(4)
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
    fun recipesAndListsRowsOpenTheirScreens() {
        val taps = show()

        compose.onNodeWithText("Recipes").performClick()
        compose.onNodeWithText("Lists").performClick()

        assertEquals(1, taps.recipes)
        assertEquals(1, taps.lists)
    }

    /** All three entries are unconditional — they used to come and go with the database. */
    @Test
    fun theNavEntriesAreThereWithAnEmptyDatabase() {
        show()

        compose.onNodeWithText("Recipes").assertIsDisplayed()
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

    // "Add a recipe" (#286): three equal tiles in place of the link field.

    @Test
    fun somethingThatIsNotALinkCannotBeOpened() {
        val taps = show()

        compose.onNodeWithText("Paste a link").performClick()
        compose.onNodeWithText("Recipe URL").performTextInput("not a link")

        compose.onNodeWithText("Go").assertIsNotEnabled()
        assertNull(taps.url)
    }

    @Test
    fun aPastedLinkIsHandedUpToBeOpened() {
        val taps = show()

        compose.onNodeWithText("Paste a link").performClick()
        compose.onNodeWithText("Recipe URL").performTextInput("example.com/chicken")
        compose.onNodeWithText("Go").performClick()

        assertEquals("https://example.com/chicken", taps.url)
        compose.onNodeWithText("Recipe URL").assertDoesNotExist()
    }

    @Test
    fun typeARecipeOpensTheEditor() {
        val taps = show()

        compose.onNodeWithText("Type a recipe").performClick()

        assertEquals(1, taps.newRecipe)
    }

    @Test
    fun aLinkCanBeSharedFromAnyApp() {
        show()

        compose.onNodeWithText("Or share a link to Recipe Clipper from any app.").assertIsDisplayed()
    }

    // The resume card (#286).

    @Test
    fun aRecipeBeingCookedResumesInCookMode() {
        repository.recent.value = listOf(summary(1, "Adobo"))
        repository.recipes.value = mapOf(1L to recipe(1, CookProgress(active = true, currentStep = 1)))
        val taps = show()

        compose.onNodeWithText("Step 2 of 4").assertIsDisplayed()
        compose.onNodeWithText("Resume cooking").performClick()

        assertEquals(1L, taps.resumed)
        assertNull(taps.recipe)
    }

    @Test
    fun aRunningTimerShowsItsTimeLeft() {
        repository.recent.value = listOf(summary(1, "Adobo"))
        val timer = SavedTimer(totalSeconds = 1200, remainingSeconds = 1200, endsAt = NOW + 1_122_000)
        repository.recipes.value = mapOf(1L to recipe(1, CookProgress(active = true, timers = mapOf(0 to timer))))
        show()

        compose.onNodeWithText("18:42 left").assertIsDisplayed()
    }

    @Test
    fun aRecipeNotBeingCookedJustOpens() {
        repository.recent.value = listOf(summary(1, "Adobo"))
        repository.recipes.value = mapOf(1L to recipe(1, CookProgress()))
        val taps = show()

        compose.onNodeWithText("Step 1 of 4").assertDoesNotExist()
        compose.onNodeWithText("Resume cooking").assertDoesNotExist()
        compose.onNodeWithText("Open recipe").performClick()

        assertEquals(1L, taps.recipe)
    }

    @Test
    fun theRecipesAndListsRowsCarryCounts() {
        repository.history.value = (1L..3L).map { summary(it, "Recipe $it") }
        lists.lists.value = (1L..6L).map { RecipeList(it, "List $it", true, it == 1L, recipeCount = 0) }
        show()

        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("6").assertIsDisplayed()
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
