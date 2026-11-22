package com.example.recipeclipper.ui.recipes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.PlannedMeal
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.SavedTimer
import com.example.recipeclipper.fake.AutoBackupFixture
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeCookedPhotoRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.askai.AskAiViewModel
import com.example.recipeclipper.ui.edit.PastedTextFixtures
import com.example.recipeclipper.ui.home.HomeViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Library first (#375): the library as the Recipes tab's first screen. Real ViewModels over fakes,
 * as the Home tests were: the compact Today / Continue cooking card above search, the + and its
 * Add a recipe sheet, the gear, and the empty library's tiles with Restore and the sample.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1600dp")
class LibraryFirstScreenTest {

    @get:Rule
    val compose = createComposeRule()

    // 08:00 UTC on the day NOW falls in: breakfast is still on the Today card (#402).
    private val zone = java.util.TimeZone.getDefault()

    @Before fun pinZone() = java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))

    @After fun restoreZone() = java.util.TimeZone.setDefault(zone)

    private val repository = FakeRecipeRepository()
    private val plan = FakeMealPlanRepository()
    private val cooked = FakeCookedPhotoRepository(today = PlanDays.today(NOW))
    private val files = FakeBackupFiles()
    private val backup = AutoBackupFixture()
    private val fixtures = PastedTextFixtures()
    // The Today card's close (#448): the flag as debug has it, and where the close is kept.
    private val preferences = FakeAppPreferences()
    private val flagStore = FakeFeatureFlagStore()

    /** What the screen handed on. */
    private class Taps {
        var recipe: Long? = null
        var resumed: Long? = null
        var settings = 0
        var newRecipe = 0
        var url: String? = null
        var scanned: List<String>? = null
        var planned: Triple<Long, Int?, Boolean>? = null
    }

    private fun summary(id: Long, title: String) = RecipeSummary(id, title, null, null, id, isSaved = false)

    private fun recipe(id: Long, cook: CookProgress) = Recipe(
        name = "Recipe $id", image = null, ingredients = listOf("1 egg"),
        instructions = (1..4).map { "Step $it" }, prepTime = null, cookTime = null,
        totalTime = null, yield = null, sourceUrl = "https://example.com/$id", id = id, cook = cook
    )

    private fun planned(id: Long, type: Long, recipeId: Long?, title: String?, note: String? = null, servings: Int? = null) =
        PlannedMeal(id, PlanDays.today(NOW), type, recipeId, title, null, servings, note)

    private fun libraryOf(vararg recipes: RecipeSummary) {
        repository.history.value = recipes.toList()
        repository.recent.value = recipes.toList()
    }

    private lateinit var home: HomeViewModel

    private fun show(askAi: Boolean = true, scan: Boolean = true, homeTonight: Boolean = true): Taps {
        val taps = Taps()
        val library = RecipesViewModel(repository, FakeAppPreferences(), lists = FakeListRepository(), pasted = fixtures.router)
        home = HomeViewModel(
            repository, backup.repository, files, backup.autoBackup, FakeListRepository(), Clock { NOW },
            fixtures.router, plan, cooked, preferences, FeatureFlags(flagStore, FlagRegistry.definitions, isDebug = true)
        )
        val askAiViewModel = AskAiViewModel(
            FakePantryRepository(), FakeListRepository(), repository, FakeAppPreferences(), Clock { NOW }, fixtures.router
        )
        compose.setContent {
            RecipesScreen(
                onBack = {},
                onOpenRecipe = { taps.recipe = it },
                onNewRecipe = { taps.newRecipe++ },
                onOpenUrl = { taps.url = it },
                onScan = { taps.scanned = it },
                viewModel = library,
                scanEnabled = scan,
                askAiEnabled = askAi,
                askAiViewModel = askAiViewModel.takeIf { askAi },
                homeTonight = homeTonight,
                libraryRoot = LibraryRoot(
                    viewModel = home,
                    onOpenSettings = { taps.settings++ },
                    onResumeCooking = { taps.resumed = it },
                    onOpenPlanned = { id, servings, cook -> taps.planned = Triple(id, servings, cook) }
                )
            )
        }
        return taps
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun top(node: androidx.compose.ui.test.SemanticsNodeInteraction) =
        node.fetchSemanticsNode().boundsInRoot.top

    // --- The card ---

    @Test
    fun todaysMealSitsAboveSearchWithStartCooking() {
        libraryOf(summary(9, "Newest"))
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, 5, "Lemon chicken", servings = 6))
        val taps = show()

        waitForText("Lemon chicken")
        compose.onNodeWithTag("libraryCard").assertIsDisplayed()
        compose.onNodeWithText("TODAY · DINNER").assertIsDisplayed()
        // Above search, under the title.
        assertTrue(top(compose.onNodeWithTag("libraryCard")) < top(compose.onNodeWithText("Search titles and ingredients")))
        assertTrue(top(compose.onNodeWithText("Recipes")) < top(compose.onNodeWithTag("libraryCard")))

        compose.onNodeWithText("Start cooking").performClick()
        assertEquals(Triple(5L, 6, true), taps.planned)
        assertNull(taps.resumed)
    }

    @Test
    fun theCardsNameOpensThePlannedRecipeWithoutCooking() {
        libraryOf(summary(9, "Newest"))
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, 5, "Lemon chicken", servings = 6))
        val taps = show()

        waitForText("Lemon chicken")
        compose.onNodeWithText("Lemon chicken").performClick()

        assertEquals(Triple(5L, 6, false), taps.planned)
    }

    @Test
    fun theNextMealIsOneLineUnderTheName() {
        libraryOf(summary(9, "Newest"))
        plan.meals.value = listOf(
            planned(1, FakeMealPlanRepository.BREAKFAST, 6, "Porridge"),
            planned(2, FakeMealPlanRepository.DINNER, 5, "Lemon chicken")
        )
        val taps = show()

        waitForText("Porridge")
        compose.onNodeWithText("Dinner: Lemon chicken").assertIsDisplayed().performClick()
        assertEquals(Triple(5L, null, false), taps.planned)
    }

    @Test
    fun aPlannedNoteHasNoButton() {
        libraryOf(summary(9, "Newest"))
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, null, null, note = "Dinner at Mum's"))
        show()

        waitForText("Dinner at Mum's")
        compose.onNodeWithText("Start cooking").assertDoesNotExist()
    }

    @Test
    fun midCookTheCardIsContinueCookingWithTheStepAndTheCountdown() {
        libraryOf(summary(1, "Adobo"))
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, 5, "Lemon chicken"))
        val timer = SavedTimer(totalSeconds = 1200, remainingSeconds = 1200, endsAt = NOW + 1_122_000)
        repository.recipes.value = mapOf(1L to recipe(1, CookProgress(active = true, currentStep = 1, timers = mapOf(1 to timer))))
        val taps = show()

        waitForText("Step 2 of 4")
        compose.onNodeWithText("CONTINUE COOKING").assertIsDisplayed()
        compose.onNodeWithText("18:42 left").assertIsDisplayed()
        // Today's meal waits: the cook is mid-cook.
        compose.onNodeWithText("Lemon chicken").assertDoesNotExist()
        compose.onNodeWithText("Resume cooking").performClick()
        assertEquals(1L, taps.resumed)
        assertNull(taps.planned)
    }

    @Test
    fun theCardsNameOpensTheRecipeWhileCooking() {
        libraryOf(summary(1, "Adobo"))
        repository.recipes.value = mapOf(1L to recipe(1, CookProgress(active = true)))
        val taps = show()

        waitForText("CONTINUE COOKING")
        // The name is on the card and in the list below it; the card's is the first.
        compose.onAllNodesWithText("Adobo")[0].performClick()
        assertEquals(1L, taps.recipe)
        assertNull(taps.resumed)
    }

    @Test
    fun withNothingToCookThereIsNoCardAndSearchSitsUnderTheTitle() {
        libraryOf(summary(1, "Adobo"))
        repository.recipes.value = mapOf(1L to recipe(1, CookProgress()))
        show()

        waitForText("Adobo")
        compose.onAllNodesWithTag("libraryCard").assertCountEquals(0)
        // Never an "Open recipe" card for a recipe nobody is cooking.
        compose.onNodeWithText("Open recipe").assertDoesNotExist()
        compose.onNodeWithText("CONTINUE COOKING").assertDoesNotExist()
        compose.onNodeWithText("Search titles and ingredients").assertIsDisplayed()
    }

    @Test
    fun withTheTodayFlagOffOnlyACookingRecipeGetsACard() {
        libraryOf(summary(1, "Adobo"))
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, 5, "Lemon chicken"))
        show(homeTonight = false)

        waitForText("Adobo")
        compose.onAllNodesWithTag("libraryCard").assertCountEquals(0)
        compose.onNodeWithText("Lemon chicken").assertDoesNotExist()
    }

    // --- Closing the Today card (#448) ---

    @Test
    fun theTodayCardsCloseHidesItUntilTomorrow() {
        libraryOf(summary(9, "Newest"))
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, 5, "Lemon chicken"))
        show()

        waitForText("Lemon chicken")
        compose.onNodeWithContentDescription("Hide today's meal until tomorrow").assertIsDisplayed().performClick()

        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("libraryCard").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Lemon chicken").assertDoesNotExist()
        assertEquals(PlanDays.today(NOW), preferences.todayCardDismissedDay)
        // Search sits under the title again, as with nothing to cook.
        compose.onNodeWithText("Search titles and ingredients").assertIsDisplayed()
    }

    @Test
    fun continueCookingHasNoClose() {
        libraryOf(summary(1, "Adobo"))
        repository.recipes.value = mapOf(1L to recipe(1, CookProgress(active = true)))
        show()

        waitForText("CONTINUE COOKING")
        compose.onAllNodesWithTag("todayClose").assertCountEquals(0)
    }

    @Test
    fun withTheCloseFlagOffTheTodayCardHasNoClose() {
        flagStore.setOverride(Flag.TODAY_CARD_CLOSE.key, false)
        libraryOf(summary(9, "Newest"))
        plan.meals.value = listOf(planned(1, FakeMealPlanRepository.DINNER, 5, "Lemon chicken"))
        show()

        waitForText("Lemon chicken")
        compose.onAllNodesWithTag("todayClose").assertCountEquals(0)
    }

    // --- The gear and the + ---

    @Test
    fun theGearBesideTheTitleOpensSettings() {
        libraryOf(summary(1, "Adobo"))
        val taps = show()

        compose.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()

        assertEquals(1, taps.settings)
        // Beside the title, left of the +.
        assertTrue(
            compose.onNodeWithTag("librarySettings").fetchSemanticsNode().boundsInRoot.left <
                compose.onNodeWithTag("libraryAdd").fetchSemanticsNode().boundsInRoot.left
        )
        // No Back: the library is the tab's first screen.
        compose.onNodeWithText("Back").assertDoesNotExist()
    }

    @Test
    fun thePlusOpensTheSheetOfFourWaysEachWithItsLine() {
        libraryOf(summary(1, "Adobo"))
        show()

        compose.onNodeWithTag("libraryAdd").performClick()

        compose.onNodeWithTag("addRecipeSheet").assertIsDisplayed()
        compose.onNodeWithText("Add a recipe").assertIsDisplayed()
        for ((name, hint) in listOf(
            "Paste a link" to "A link or a recipe's text",
            "Scan a recipe" to "A card or a cookbook page",
            "Type a recipe" to "Write it in yourself",
            "Ask an AI" to "A prompt for any chat app"
        )) {
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithText(hint).assertIsDisplayed()
        }
        compose.onNodeWithText("Or share a link to Pith from any app.").assertIsDisplayed()
    }

    @Test
    fun theFlagsLeaveOutScanAndAskAnAi() {
        libraryOf(summary(1, "Adobo"))
        show(askAi = false, scan = false)

        compose.onNodeWithTag("libraryAdd").performClick()

        compose.onNodeWithText("Paste a link").assertIsDisplayed()
        compose.onNodeWithText("Type a recipe").assertIsDisplayed()
        compose.onAllNodesWithTag("addScan").assertCountEquals(0)
        compose.onAllNodesWithTag("addAskAi").assertCountEquals(0)
    }

    @Test
    fun typeARecipeClosesTheSheetAndOpensTheEditor() {
        libraryOf(summary(1, "Adobo"))
        val taps = show()

        compose.onNodeWithTag("libraryAdd").performClick()
        compose.onNodeWithTag("addType").performClick()

        assertEquals(1, taps.newRecipe)
        compose.waitForIdle()
        compose.onNodeWithTag("addRecipeSheet").assertDoesNotExist()
    }

    @Test
    fun askAnAiClosesTheSheetAndOpensItsOwn() {
        libraryOf(summary(1, "Adobo"))
        show()

        compose.onNodeWithTag("libraryAdd").performClick()
        compose.onNodeWithTag("addAskAi").performClick()

        compose.waitUntil(5_000) { home.uiState.value.askAiOpen }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("What do you want to cook?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("addRecipeSheet").assertDoesNotExist()
    }

    @Test
    fun scanAFlagOnShowsItsRowInTheSheet() {
        libraryOf(summary(1, "Adobo"))
        show()

        compose.onNodeWithTag("libraryAdd").performClick()

        compose.onNodeWithTag("addScan").assertIsDisplayed()
    }

    // --- The empty library ---

    @Test
    fun anEmptyLibraryShowsTheFourWaysInAsTilesAndRestore() {
        files.files["content://picked"] = "{}"
        backup.repository.importResult = BackupResult.Success(ImportSummary(3, 1, 0, 0))
        val taps = show()

        waitForText("Your recipes go here")
        compose.onNodeWithTag("emptyLibrary").assertIsDisplayed()
        for (tag in listOf("emptyPasteLink", "emptyScan", "emptyType", "emptyAskAi")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        compose.onNodeWithText("Restore from a backup file").assertIsDisplayed()
        // The plain library has nothing to search yet.
        compose.onNodeWithText("Search titles and ingredients").assertDoesNotExist()
        // The + is still the way in.
        compose.onNodeWithTag("libraryAdd").assertIsDisplayed()

        compose.onNodeWithTag("emptyType").performClick()
        assertEquals(1, taps.newRecipe)

        compose.runOnIdle { home.onRestorePicked("content://picked") }
        compose.waitForIdle()
        assertEquals(listOf("{}"), backup.repository.importedTexts)
        compose.onNodeWithText("Imported", substring = true).assertIsDisplayed()
    }

    @Test
    fun theSampleAloneStillShowsTheTilesWithTheSampleUnderThem() {
        repository.sample = 3L
        libraryOf(summary(3, "Tomato and White Bean Soup"))
        show()

        waitForText("Tomato and White Bean Soup")
        compose.onNodeWithTag("emptyLibrary").assertIsDisplayed()
        compose.onNodeWithText("Restore from a backup file").assertIsDisplayed()
    }

    @Test
    fun aLibraryWithRecipesShowsNoTilesAndNoRestore() {
        libraryOf(summary(1, "Adobo"))
        show()

        waitForText("Adobo")
        compose.onAllNodesWithTag("emptyLibrary").assertCountEquals(0)
        compose.onNodeWithText("Restore from a backup file").assertDoesNotExist()
    }

    /** The one-time "Keep a backup copy?" card (#150) sits under the card, above search, until answered. */
    @Test
    fun theBackupFolderCardIsOfferedOnceInTheLibrary() {
        libraryOf(summary(1, "Adobo"))
        show()

        waitForText("Keep a backup copy?")
        assertTrue(top(compose.onNodeWithText("Keep a backup copy?")) < top(compose.onNodeWithText("Search titles and ingredients")))
        compose.onNodeWithText("Not now").performClick()

        compose.onNodeWithText("Keep a backup copy?").assertDoesNotExist()
        assertTrue(backup.store.record.value.folderPromptDone)
    }

    private companion object {
        const val NOW = 1_699_948_800_000L
    }
}
