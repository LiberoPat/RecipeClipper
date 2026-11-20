package com.example.recipeclipper.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.PlannedMeal
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.SavedTimer
import com.example.recipeclipper.fake.FakeCookedPhotoRepository
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.data.remote.SharedText
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.edit.PasteTarget
import com.example.recipeclipper.ui.edit.PastedTextFixtures
import com.example.recipeclipper.ui.edit.PastedTextRouter
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

    // 08:00 UTC on the day NOW falls in: breakfast is still on the Today card (#402).
    private val zone = java.util.TimeZone.getDefault()

    @org.junit.Before fun pinZone() = java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))

    @org.junit.After fun restoreZone() = java.util.TimeZone.setDefault(zone)

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
        var pasted: PasteTarget? = null
        /** Today's card (#402): the recipe, its planned servings, and whether to start cooking. */
        var planned: Triple<Long, Int?, Boolean>? = null
    }

    private val plan = FakeMealPlanRepository()
    private val cooked = FakeCookedPhotoRepository(today = PlanDays.today(NOW))

    private fun show(
        oneCookbook: Boolean = false,
        askAi: Boolean = false,
        pasted: PastedTextRouter? = null,
        homeTonight: Boolean = false
    ): Taps {
        val taps = Taps()
        // Built outside setContent: a ViewModel constructed inside a composable would be
        // rebuilt on every recomposition. A fixed clock, so a timer's time left is known.
        val viewModel = HomeViewModel(repository, lists = lists, clock = Clock { NOW }, pasted = pasted, mealPlan = plan, cooked = cooked)
        compose.setContent {
            HomeScreen(
                onOpenUrl = { taps.url = it },
                onOpenPasted = { taps.pasted = it },
                onOpenRecipe = { taps.recipe = it },
                onOpenRecipes = { taps.recipes++ },
                onOpenLists = { taps.lists++ },
                onOpenSettings = { taps.settings++ },
                onNewRecipe = { taps.newRecipe++ },
                onResumeCooking = { taps.resumed = it },
                onOpenPlanned = { id, servings, cook -> taps.planned = Triple(id, servings, cook) },
                viewModel = viewModel,
                oneCookbook = oneCookbook,
                askAiEnabled = askAi,
                homeTonight = homeTonight
            )
        }
        return taps
    }

    /** Ask an AI (#351): the fourth tile, behind its flag. */
    @Test
    fun askAiIsATileWhileItsFlagIsOn() {
        show(askAi = true)
        compose.onNodeWithTag("homeAskAi").assertIsDisplayed()
        compose.onNodeWithText("Ask an AI").assertIsDisplayed()
    }

    @Test
    fun withTheFlagOffThereIsNoAskAiTile() {
        show(askAi = false)
        compose.onAllNodesWithTag("homeAskAi").assertCountEquals(0)
    }

    /** #371: the others are Recipes' to show, not a shelf on Home. */
    @Test
    fun theNewestRecipeIsContinueCookingAndThereIsNoRecentlyViewedShelf() {
        repository.recent.value = listOf(summary(1, "Newest"), summary(2, "Older"))
        show()

        compose.onNodeWithText("CONTINUE COOKING").assertIsDisplayed()
        compose.onNodeWithText("Newest").assertIsDisplayed()
        compose.onNodeWithText("Recently viewed").assertDoesNotExist()
        compose.onNodeWithText("Older").assertDoesNotExist()
        compose.onAllNodesWithTag("home.recent").assertCountEquals(0)
        compose.onAllNodesWithTag("home.seeAll").assertCountEquals(0)
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
    fun tappingContinueCookingsRecipeOpensIt() {
        repository.recent.value = listOf(summary(7, "Newest"))
        val taps = show()

        compose.onNodeWithText("Newest").performClick()

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

    /** #371: with one cookbook the Recipes screen lists the lists, so Home keeps only the Recipes row. */
    @Test
    fun withOneCookbookThereIsNoListsRow() {
        val taps = show(oneCookbook = true)

        compose.onNodeWithText("Lists").assertDoesNotExist()
        compose.onNodeWithText("Recipes").performClick()
        assertEquals(1, taps.recipes)
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
    fun textWithNoRecipeInItOpensNothingAndSaysSo() {
        val taps = show(pasted = PastedTextFixtures().router)

        compose.onNodeWithText("Paste a link").performClick()
        compose.onNodeWithText("Go").assertIsNotEnabled()
        compose.onNodeWithText("Link or recipe text").performTextInput("   ")
        compose.onNodeWithText("Go").assertIsNotEnabled()
        // Too long for the editor, and neither a link nor a list: nothing to open.
        compose.onNodeWithText("Link or recipe text").performTextInput("x".repeat(SharedText.MAX_LENGTH + 1))
        compose.onNodeWithText("Go").assertIsEnabled().performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("No recipe found in that text.").fetchSemanticsNodes().isNotEmpty() }
        assertNull(taps.url)
        assertNull(taps.pasted)
    }

    /** #372: Paste a link takes an AI's answer as it takes a share: a recipe opens the editor. */
    @Test
    fun aPastedJsonRecipeIsHandedUpForTheEditor() {
        val fixtures = PastedTextFixtures()
        val taps = show(pasted = fixtures.router)

        compose.onNodeWithText("Paste a link").performClick()
        compose.onNodeWithText("Link or recipe text").performTextInput(PastedTextFixtures.json)
        compose.onNodeWithText("Go").assertIsEnabled().performClick()

        compose.waitUntil(5_000) { taps.pasted != null }
        val review = taps.pasted as PasteTarget.Review
        assertEquals("Lemon Pasta", fixtures.inbox.take(review.key)!!.reading.name)
        assertNull(taps.url)
        compose.waitForIdle()
        compose.onNodeWithText("Link or recipe text").assertDoesNotExist()
    }

    @Test
    fun aPastedLinkIsHandedUpToBeOpened() {
        val taps = show()

        compose.onNodeWithText("Paste a link").performClick()
        compose.onNodeWithText("Link or recipe text").performTextInput("example.com/chicken")
        compose.onNodeWithText("Go").performClick()

        assertEquals("https://example.com/chicken", taps.url)
        compose.onNodeWithText("Link or recipe text").assertDoesNotExist()
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

    /** The card's photo and name open the recipe, as the Continue cooking row did. */
    @Test
    fun theCardsNameOpensTheRecipeEvenWhileCooking() {
        repository.recent.value = listOf(summary(1, "Adobo"))
        repository.recipes.value = mapOf(1L to recipe(1, CookProgress(active = true)))
        val taps = show()

        compose.onNodeWithText("Adobo").performClick()

        assertEquals(1L, taps.recipe)
        assertNull(taps.resumed)
    }

    @Test
    fun theRecipesAndListsRowsCarryCounts() {
        repository.history.value = (1L..3L).map { summary(it, "Recipe $it") }
        lists.lists.value = (1L..6L).map { RecipeList(it, "List $it", true, it == 1L, recipeCount = 0) }
        show()

        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("6").assertIsDisplayed()
    }

    // Today's card (#402).

    private fun planned(id: Long, type: Long, recipeId: Long?, title: String?, note: String? = null, servings: Int? = null) =
        PlannedMeal(id, PlanDays.today(NOW), type, recipeId, title, null, servings, note)

    @Test
    fun todaysMealLeadsWithStartCookingAtThePlannedServings() {
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, 5, "Lemon chicken", servings = 6))
        repository.recent.value = listOf(summary(9, "Newest"))
        val taps = show(homeTonight = true)

        compose.onNodeWithText("TODAY").assertIsDisplayed()
        compose.onNodeWithText("Lemon chicken").assertIsDisplayed()
        compose.onNodeWithText("CONTINUE COOKING").assertDoesNotExist()
        compose.onNodeWithText("Newest").assertDoesNotExist()
        compose.onNodeWithText("Start cooking").performClick()

        assertEquals(Triple(5L, 6, true), taps.planned)
    }

    @Test
    fun theCardsNameOpensTheRecipeWithoutCooking() {
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, 5, "Lemon chicken"))
        val taps = show(homeTonight = true)

        compose.onNodeWithText("Lemon chicken").performClick()

        assertEquals(Triple(5L, null, false), taps.planned)
    }

    @Test
    fun theRestOfTodayIsAShortLineEachAndTappable() {
        plan.meals.value = listOf(
            planned(1, FakeMealPlanRepository.DINNER, 5, "Lemon chicken"),
            planned(2, FakeMealPlanRepository.BREAKFAST, 6, "Porridge"),
            planned(3, FakeMealPlanRepository.SNACK, null, null, note = "Fruit")
        )
        val taps = show(homeTonight = true)

        compose.onNodeWithText("Porridge").assertIsDisplayed()
        compose.onNodeWithText("Dinner: Lemon chicken").assertIsDisplayed()
        compose.onNodeWithText("Snack: Fruit").assertIsDisplayed()
        compose.onNodeWithText("Dinner: Lemon chicken").performClick()

        assertEquals(Triple(5L, null, false), taps.planned)
    }

    @Test
    fun aMealCookedTodayDropsOffTheCard() {
        plan.meals.value = listOf(
            planned(1, FakeMealPlanRepository.BREAKFAST, 6, "Porridge"),
            planned(2, FakeMealPlanRepository.DINNER, 5, "Lemon chicken")
        )
        cooked.mark(6)
        show(homeTonight = true)

        compose.onNodeWithText("Lemon chicken").assertIsDisplayed()
        compose.onNodeWithText("Porridge").assertDoesNotExist()
        compose.onNodeWithText("Dinner: Lemon chicken").assertDoesNotExist()
    }

    @Test
    fun aPlannedNoteShowsItsTextWithNoButton() {
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, null, null, note = "Dinner at Mum's"))
        val taps = show(homeTonight = true)

        compose.onNodeWithText("Dinner at Mum's").assertIsDisplayed()
        compose.onNodeWithText("Start cooking").assertDoesNotExist()
        compose.onNodeWithText("Dinner at Mum's").performClick()
        assertNull(taps.planned)
    }

    @Test
    fun nothingTodayAndNothingMidCookIsNoCard() {
        repository.recent.value = listOf(summary(1, "Adobo"))
        repository.recipes.value = mapOf(1L to recipe(1, CookProgress()))
        show(homeTonight = true)

        compose.onNodeWithText("TODAY").assertDoesNotExist()
        compose.onNodeWithText("CONTINUE COOKING").assertDoesNotExist()
        compose.onNodeWithText("Open recipe").assertDoesNotExist()
        compose.onAllNodesWithTag("todayCard").assertCountEquals(0)
        // The way in is still there.
        compose.onNodeWithText("Paste a link").assertIsDisplayed()
    }

    @Test
    fun midCookKeepsContinueCookingOverTodaysMeal() {
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, 5, "Lemon chicken"))
        repository.recent.value = listOf(summary(1, "Adobo"))
        repository.recipes.value = mapOf(1L to recipe(1, CookProgress(active = true, currentStep = 1)))
        val taps = show(homeTonight = true)

        compose.onNodeWithText("CONTINUE COOKING").assertIsDisplayed()
        compose.onNodeWithText("Lemon chicken").assertDoesNotExist()
        compose.onNodeWithText("Resume cooking").performClick()

        assertEquals(1L, taps.resumed)
    }

    @Test
    fun aFinishedRunWithATimerStillRunningIsNotOfferedForResuming() {
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, 5, "Lemon chicken"))
        repository.recent.value = listOf(summary(1, "Adobo"))
        val timer = SavedTimer(totalSeconds = 1200, remainingSeconds = 1200, endsAt = NOW + 600_000)
        repository.recipes.value =
            mapOf(1L to recipe(1, CookProgress(doneSteps = setOf(0, 1, 2, 3), timers = mapOf(3 to timer))))
        show(homeTonight = true)

        compose.onNodeWithText("Resume cooking").assertDoesNotExist()
        compose.onNodeWithText("Lemon chicken").assertIsDisplayed()
    }

    @Test
    fun withTheFlagOffTheCardIsAlwaysTheLatestRecipe() {
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, 5, "Lemon chicken"))
        repository.recent.value = listOf(summary(1, "Adobo"))
        repository.recipes.value = mapOf(1L to recipe(1, CookProgress()))
        show(homeTonight = false)

        compose.onNodeWithText("CONTINUE COOKING").assertIsDisplayed()
        compose.onNodeWithText("Open recipe").assertIsDisplayed()
        compose.onNodeWithText("Lemon chicken").assertDoesNotExist()
    }

    private companion object {
        const val NOW = 1_699_948_800_000L
    }
}
