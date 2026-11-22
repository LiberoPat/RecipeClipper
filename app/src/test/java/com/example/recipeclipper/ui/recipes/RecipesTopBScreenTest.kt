package com.example.recipeclipper.ui.recipes

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.PlannedMeal
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeQuickFilter
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.AutoBackupFixture
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeCookedPhotoRepository
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.edit.PastedTextFixtures
import com.example.recipeclipper.ui.home.HomeViewModel
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The top of Recipes, design B (#449): the ⋮ menu's Select and Settings, the views shelf, the filter
 * button and its sheet, the pills and Clear, Select all, and the flag off keeping the old top. Real
 * ViewModels over the hand-written fakes.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1600dp")
class RecipesTopBScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone = java.util.TimeZone.getDefault()

    @Before fun pinZone() = java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))

    @After fun restoreZone() = java.util.TimeZone.setDefault(zone)

    private val repository = FakeRecipeRepository().apply {
        history.value = rows()
        // What Home's "is the library empty" reads.
        recent.value = rows()
    }

    private fun rows() = listOf(
            RecipeSummary(1, "Quick Soup", null, "20m", 1, isSaved = false, language = "en"),
            RecipeSummary(2, "Slow Brisket", null, "3h", 2, isSaved = true, language = "en"),
            RecipeSummary(3, "Toast", null, "5m", 3, isSaved = false, lastCookedDay = 20_000, language = "en")
        )
    private val lists = FakeListRepository().apply {
        lists.value = listOf(
            RecipeList(1, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 1),
            RecipeList(2, "Dinner", isBuiltIn = true, isFavorites = false, recipeCount = 0)
        )
    }
    private val plan = FakeMealPlanRepository()

    private class Taps {
        var settings = 0
        var view: RecipeFilter? = null
        var lists = 0
    }

    private lateinit var library: RecipesViewModel

    private fun show(topB: Boolean = true): Taps = showAt("Quick Soup", topB)

    private fun showAt(firstRow: String, topB: Boolean = true): Taps {
        val taps = Taps()
        val backup = AutoBackupFixture()
        val fixtures = PastedTextFixtures()
        library = RecipesViewModel(repository, FakeAppPreferences(), lists = lists, pasted = fixtures.router)
        val home = HomeViewModel(
            repository, backup.repository, FakeBackupFiles(), backup.autoBackup, lists, Clock { NOW },
            fixtures.router, plan, FakeCookedPhotoRepository(today = PlanDays.today(NOW)), FakeAppPreferences(),
            FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)
        )
        val save = SaveToListViewModel(lists)
        compose.setContent {
            RecipesScreen(
                onBack = {},
                onOpenRecipe = {},
                onOpenView = { taps.view = it },
                onOpenLists = { taps.lists++ },
                viewModel = library,
                scanEnabled = false,
                oneCookbook = true,
                multiSelect = true,
                saveViewModel = save,
                quickFilters = true,
                gridEnabled = true,
                askAiEnabled = false,
                askAiViewModel = null,
                topB = topB,
                homeTonight = true,
                libraryRoot = LibraryRoot(
                    viewModel = home,
                    onOpenSettings = { taps.settings++ },
                    onResumeCooking = {},
                    onOpenPlanned = { _, _, _ -> }
                )
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(firstRow).fetchSemanticsNodes().isNotEmpty() }
        return taps
    }

    private fun filtersOn(n: Int) = SemanticsMatcher.expectValue(
        SemanticsProperties.StateDescription, if (n == 1) "1 filter on" else "$n filters on"
    )

    private fun noFiltersOn() = SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)

    // --- The title row ---

    @Test
    fun theMoreMenuIsLastAndHoldsSelectAndSettings() {
        val taps = show()

        // No gear and no Select button in the row; the ⋮ sits right of the +.
        compose.onAllNodesWithTag("librarySettings").assertCountEquals(0)
        compose.onAllNodesWithTag("selectRecipes").assertCountEquals(0)
        assertTrue(
            compose.onNodeWithTag("libraryAdd").fetchSemanticsNode().boundsInRoot.left <
                compose.onNodeWithTag("libraryMore").fetchSemanticsNode().boundsInRoot.left
        )

        compose.onNodeWithTag("libraryMore").performClick()
        compose.onNodeWithTag("librarySettings").assertIsDisplayed().performClick()
        assertEquals(1, taps.settings)

        compose.onNodeWithTag("libraryMore").performClick()
        compose.onNodeWithTag("librarySelect").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertTrue(library.uiState.value.selecting)
        compose.onNodeWithTag("selectedCount").assertIsDisplayed()
    }

    @Test
    fun selectAllTicksEveryRowAndDoneLeaves() {
        show()
        compose.onNodeWithTag("libraryMore").performClick()
        compose.onNodeWithTag("librarySelect").performClick()

        compose.onNodeWithTag("selectAllRecipes").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("3 selected").assertIsDisplayed()
        assertEquals(setOf(1L, 2L, 3L), library.uiState.value.selectedIds)

        compose.onNodeWithTag("doneSelecting").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("libraryMore").assertIsDisplayed()
    }

    // --- The pinned bar (#449, the owner's follow-up) ---

    private fun aLongLibrary() {
        val many = (1L..30L).map { RecipeSummary(it, "Recipe $it", null, "20m", it, isSaved = false, language = "en") }
        repository.history.value = many
        repository.recent.value = many
    }

    @Test
    fun downTheListABarPinsWithThePlusAndTheMenusSelectAndSettings() {
        aLongLibrary()
        val taps = showAt("Recipe 1")
        // At rest: the large title, no pinned bar.
        compose.onAllNodesWithTag("libraryPinnedBar").assertCountEquals(0)

        compose.onNodeWithTag("recipesList").performScrollToIndex(25)
        compose.waitForIdle()
        compose.onNodeWithTag("libraryPinnedBar").assertIsDisplayed()
        compose.onNodeWithTag("libraryAddPinned").assertIsDisplayed()

        compose.onNodeWithTag("libraryMorePinned").performClick()
        compose.onNodeWithTag("librarySettings").performClick()
        assertEquals(1, taps.settings)

        compose.onNodeWithTag("libraryMorePinned").performClick()
        compose.onNodeWithTag("librarySelect").performClick()
        compose.waitForIdle()
        assertTrue(library.uiState.value.selecting)
        // Selecting has its own bar; the pinned one steps aside.
        compose.onAllNodesWithTag("libraryPinnedBar").assertCountEquals(0)
        compose.onNodeWithTag("selectedCount").assertIsDisplayed()

        // Back at the top, the bar goes again.
        compose.onNodeWithTag("doneSelecting").performClick()
        compose.onNodeWithTag("recipesList").performScrollToIndex(0)
        compose.waitForIdle()
        compose.onAllNodesWithTag("libraryPinnedBar").assertCountEquals(0)
    }

    @Test
    fun withTheFlagOffNothingPins() {
        aLongLibrary()
        showAt("Recipe 1", topB = false)

        compose.onNodeWithTag("recipesList").performScrollToIndex(25)
        compose.waitForIdle()
        compose.onAllNodesWithTag("libraryPinnedBar").assertCountEquals(0)
    }

    // --- The views shelf ---

    @Test
    fun theShelfHoldsListsThenTheViewsAndEachCellNavigates() {
        val taps = show()

        compose.onNodeWithTag("recipesViewsShelf").assertIsDisplayed()
        compose.onAllNodesWithTag("recipesViewsStrip").assertCountEquals(0)
        compose.onNodeWithTag("recipesLists").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Lists, 2")))
        compose.onNodeWithTag("recipesView-NOT_IN_A_LIST")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Not in a list, 2")))
        // Above search.
        assertTrue(
            compose.onNodeWithTag("recipesViewsShelf").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithTag("recipesSearch").fetchSemanticsNode().boundsInRoot.top
        )

        compose.onNodeWithTag("recipesView-COOKED").performClick()
        assertEquals(RecipeFilter.COOKED, taps.view)
        compose.onNodeWithTag("recipesLists").performClick()
        assertEquals(1, taps.lists)
        // They navigate, never filter.
        assertTrue(library.uiState.value.quick.isEmpty())
    }

    @Test
    fun atTheDefaultSizeTheFiveCellsShareTheWidth() {
        show()
        val shelf = compose.onNodeWithTag("recipesViewsShelf").fetchSemanticsNode().boundsInRoot
        // Where the cell's right edge lies, scrolled out of sight or not (bounds in root are clipped).
        val last = compose.onNodeWithTag("recipesView-CHAT").fetchSemanticsNode().let { it.positionInRoot.x + it.size.width }
        assertTrue("the last cell ends inside the shelf", last <= shelf.right + 1f)
    }

    /** German's longest words at twice the text size: the cells take what they need and the shelf scrolls, never clips. */
    @Test
    @Config(qualifiers = "de-w360dp-h1600dp", fontScale = 2f)
    // Real text measurement: the legacy shadows measure every word the same.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun inGermanAtTwiceTheTextTheShelfScrollsRatherThanClipping() {
        show()
        val shelf = compose.onNodeWithTag("recipesViewsShelf").fetchSemanticsNode().boundsInRoot
        // Where the cell's right edge lies, scrolled out of sight or not (bounds in root are clipped).
        val last = compose.onNodeWithTag("recipesView-CHAT").fetchSemanticsNode().let { it.positionInRoot.x + it.size.width }
        val typed = compose.onNodeWithTag("recipesView-TYPED").fetchSemanticsNode()
        assertTrue("the cells run past the shelf, to scroll to: $last vs $shelf, typed ${typed.positionInRoot} ${typed.size}", last > shelf.right)
        compose.onNodeWithText("Selbst eingegeben").assertExists()
    }

    // --- The filters ---

    @Test
    fun aFilterTurnedOnInTheSheetShowsAPillAndCountsOnTheBadge() {
        show()
        // No chips under search any more, and no pills while nothing is on.
        compose.onAllNodesWithTag("recipeQuick-THIRTY_OR_LESS").assertCountEquals(0)
        compose.onAllNodesWithTag("recipesFilterClear").assertCountEquals(0)
        compose.onNodeWithTag("recipesFilterButton").assert(noFiltersOn())

        compose.onNodeWithTag("recipesFilterButton").performClick()
        compose.onNodeWithTag("recipesFilterSheet").assertIsDisplayed()
        compose.onNodeWithText("Filter recipes").assertIsDisplayed()
        compose.onNodeWithText("Total time, or prep plus cook").assertIsDisplayed()
        compose.onNodeWithTag("recipeFilterSwitch-THIRTY_OR_LESS").assertIsOff().performClick()
        compose.onNodeWithTag("recipeFilterSwitch-THIRTY_OR_LESS").assertIsOn()
        compose.onNodeWithTag("recipeFilterSwitch-NOT_COOKED").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(RecipeQuickFilter.THIRTY_OR_LESS, RecipeQuickFilter.NOT_COOKED),
            library.uiState.value.activeQuickFilters
        )
        compose.onNodeWithText("Slow Brisket").assertDoesNotExist()
        // The sheet stays open while the switches change.
        compose.onNodeWithTag("recipesFilterSheet").assertIsDisplayed()
    }

    @Test
    fun thePillsXRemovesItsFilterAndClearRemovesAll() {
        show()
        compose.runOnIdle {
            library.onQuickFilterToggle(RecipeQuickFilter.THIRTY_OR_LESS)
            library.onQuickFilterToggle(RecipeQuickFilter.NOT_COOKED)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("recipesFilterButton").assert(filtersOn(2))
        compose.onNodeWithTag("recipeFilterPill-THIRTY_OR_LESS").assertIsDisplayed()
        compose.onNodeWithTag("recipeFilterPill-NOT_COOKED")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Remove Not cooked yet")))
            .performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("recipeFilterPill-NOT_COOKED").assertCountEquals(0)
        compose.onNodeWithTag("recipesFilterButton").assert(filtersOn(1))
        compose.onNodeWithText("Toast").assertIsDisplayed()

        compose.onNodeWithTag("recipesFilterClear").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("recipeFilterPill-THIRTY_OR_LESS").assertCountEquals(0)
        compose.onAllNodesWithTag("recipesFilterClear").assertCountEquals(0)
        compose.onNodeWithTag("recipesFilterButton").assert(noFiltersOn())
        compose.onNodeWithText("Slow Brisket").assertIsDisplayed()
    }

    @Test
    fun theSheetsClearTurnsEverySwitchOff() {
        show()
        compose.onNodeWithTag("recipesFilterButton").performClick()
        compose.onNodeWithTag("recipeFilterSwitch-THIRTY_OR_LESS").performClick()
        compose.onNodeWithTag("recipeFilterSwitch-USES_PANTRY").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("recipesFilterSheetClear").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("recipeFilterSwitch-THIRTY_OR_LESS").assertIsOff()
        compose.onNodeWithTag("recipeFilterSwitch-USES_PANTRY").assertIsOff()
        assertTrue(library.uiState.value.quick.isEmpty())
    }

    // --- Today's card keeps its close button (#448) ---

    @Test
    fun todaysCardInBKeepsItsCloseButtonAndTheNextMealBesideStartCooking() {
        plan.meals.value = listOf(
            PlannedMeal(1, PlanDays.today(NOW), FakeMealPlanRepository.BREAKFAST, 6, "Porridge", null, null, null),
            PlannedMeal(2, PlanDays.today(NOW), FakeMealPlanRepository.DINNER, 5, "Lemon chicken", null, null, null)
        )
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Porridge").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("TODAY · BREAKFAST").assertIsDisplayed()
        compose.onNodeWithText("Dinner: Lemon chicken").assertIsDisplayed()
        compose.onNodeWithText("Start cooking").assertIsDisplayed()
        compose.onNodeWithTag("todayClose").assertIsDisplayed()
    }

    // --- The flag off ---

    @Test
    fun withTheFlagOffTheOldTopStays() {
        show(topB = false)

        compose.onNodeWithTag("librarySettings").assertIsDisplayed()
        compose.onNodeWithTag("selectRecipes").assertIsDisplayed()
        compose.onAllNodesWithTag("libraryMore").assertCountEquals(0)
        compose.onNodeWithTag("recipesViewsStrip").assertIsDisplayed()
        compose.onAllNodesWithTag("recipesViewsShelf").assertCountEquals(0)
        compose.onNodeWithTag("recipeQuick-THIRTY_OR_LESS").assertIsDisplayed()
        compose.onAllNodesWithTag("recipesFilterButton").assertCountEquals(0)
    }

    private companion object {
        const val NOW = 1_699_948_800_000L
    }
}
