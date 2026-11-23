package com.example.recipeclipper.ui.recipes

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.CookedChoice
import com.example.recipeclipper.data.model.OriginChoice
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.RecipeFilterChoices
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.AutoBackupFixture
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeCookedPhotoRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
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
 * Recipes' views folded into the filter sheet (#454), over design B: no shelf, the Lists pill left of search
 * (opening Lists, Back returning), the sheet's two groups, each control narrowing the list with its pill and
 * the badge, Clear, German at twice the text, and the flag off keeping design B. Real ViewModels over fakes.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1600dp")
class RecipesFiltersScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val zone = java.util.TimeZone.getDefault()

    @Before fun pinZone() = java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))

    @After fun restoreZone() = java.util.TimeZone.setDefault(zone)

    private fun rows() = listOf(
        RecipeSummary(1, "Quick Soup", null, "20m", 1, isSaved = false, language = "en"),
        RecipeSummary(2, "Slow Brisket", null, "3h", 2, isSaved = true, language = "en"),
        RecipeSummary(3, "Toast", null, "5m", 3, isSaved = false, lastCookedDay = 20_000, language = "en"),
        RecipeSummary(4, "Typed Pie", null, null, 4, isSaved = false, sourceUrl = "manual:pie", language = "en"),
        RecipeSummary(5, "Chat Stew", null, null, 5, isSaved = true, sourceUrl = "manual:chat", isFromChat = true, language = "en")
    )

    private val repository = FakeRecipeRepository().apply {
        history.value = rows()
        recent.value = rows()
    }
    private val lists = FakeListRepository().apply {
        lists.value = listOf(
            RecipeList(1, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 2),
            RecipeList(2, "Dinner", isBuiltIn = true, isFavorites = false, recipeCount = 0),
            RecipeList(3, "Lunch", isBuiltIn = true, isFavorites = false, recipeCount = 0)
        )
    }

    private lateinit var library: RecipesViewModel

    /** The library on a small nav graph: `recipes` is the real screen, `lists` a stand-in, so Back is real. */
    private fun show(filtersSheet: Boolean = true) {
        val backup = AutoBackupFixture()
        val fixtures = PastedTextFixtures()
        library = RecipesViewModel(repository, FakeAppPreferences(), lists = lists, pasted = fixtures.router)
        val home = HomeViewModel(
            repository, backup.repository, FakeBackupFiles(), backup.autoBackup, lists, Clock { NOW },
            fixtures.router, FakeMealPlanRepository(), FakeCookedPhotoRepository(today = PlanDays.today(NOW)), FakeAppPreferences(),
            FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)
        )
        val save = SaveToListViewModel(lists)
        compose.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "recipes") {
                composable("recipes") {
                    RecipesScreen(
                        onBack = {},
                        onOpenRecipe = {},
                        onOpenLists = { nav.navigate("lists") },
                        viewModel = library,
                        scanEnabled = false,
                        oneCookbook = true,
                        multiSelect = true,
                        saveViewModel = save,
                        quickFilters = true,
                        gridEnabled = true,
                        askAiEnabled = false,
                        askAiViewModel = null,
                        topB = true,
                        filtersSheet = filtersSheet,
                        homeTonight = true,
                        libraryRoot = LibraryRoot(viewModel = home, onOpenSettings = {}, onResumeCooking = {}, onOpenPlanned = { _, _, _ -> })
                    )
                }
                composable("lists") { Text("stub:lists") }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Quick Soup").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun filtersOn(n: Int) = SemanticsMatcher.expectValue(
        SemanticsProperties.StateDescription, if (n == 1) "1 filter on" else "$n filters on"
    )

    private fun noFiltersOn() = SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)

    private fun shown(title: String) = compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()

    private fun openSheet() {
        compose.onNodeWithTag("recipesFilterButton").performClick()
        compose.onNodeWithTag("recipesFilterSheet").assertIsDisplayed()
    }

    // --- The top ---

    @Test
    fun theShelfIsGoneAndListsSitsLeftOfSearchOnOneRow() {
        show()
        compose.onAllNodesWithTag("recipesViewsShelf").assertCountEquals(0)
        compose.onAllNodesWithTag("recipesView-COOKED").assertCountEquals(0)
        val pill = compose.onNodeWithTag("recipesListsPill")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Lists, 3")))
            .fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("recipesSearch").fetchSemanticsNode().boundsInRoot
        assertTrue("Lists is left of search: $pill, $search", pill.right <= search.left)
        assertTrue("on the same row", pill.top < search.bottom && search.top < pill.bottom)
        compose.onNodeWithTag("recipesFilterButton").assert(noFiltersOn())
    }

    @Test
    fun theListsPillOpensListsAndBackReturnsToRecipes() {
        show()
        compose.onNodeWithTag("recipesListsPill").performClick()
        compose.onNodeWithText("stub:lists").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("recipesListsPill").assertIsDisplayed()
        compose.onNodeWithText("Quick Soup").assertIsDisplayed()
    }

    // --- The sheet ---

    @Test
    fun theSheetHoldsTwoGroupsWithAnyAndAnywhereChosen() {
        show()
        openSheet()
        compose.onNodeWithText("THE RECIPE").assertIsDisplayed()
        compose.onNodeWithText("WHERE IT IS, WHERE IT CAME FROM").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recipeFilterSwitch-THIRTY_OR_LESS").assertIsOff()
        compose.onNodeWithTag("recipeFilterCooked-ANY").assertIsSelected()
        compose.onNodeWithTag("recipeFilterCooked-COOKED").assertIsNotSelected()
        compose.onNodeWithTag("recipeFilterSwitch-USES_PANTRY").assertIsOff()
        compose.onNodeWithTag("recipeFilterSwitch-NOT_IN_A_LIST").assertIsOff()
        compose.onNodeWithTag("recipeFilterOrigin-ANYWHERE").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Saved to none of your lists yet").assertExists()
    }

    @Test
    fun eachControlNarrowsTheListAndShowsItsPill() {
        show()
        openSheet()

        compose.onNodeWithTag("recipeFilterCooked-COOKED").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("recipeFilterCooked-COOKED").assertIsSelected()
        compose.onNodeWithTag("recipeFilterCooked-ANY").assertIsNotSelected()
        assertEquals(listOf(3L), library.uiState.value.recipes!!.map { it.id })

        compose.onNodeWithTag("recipeFilterCooked-NOT_YET").performClick()
        compose.waitForIdle()
        assertEquals(listOf(1L, 2L, 4L, 5L), library.uiState.value.recipes!!.map { it.id })
        compose.onNodeWithTag("recipeFilterCooked-ANY").performClick()

        compose.onNodeWithTag("recipeFilterSwitch-NOT_IN_A_LIST").performScrollTo().performClick()
        compose.onNodeWithTag("recipeFilterSwitch-NOT_IN_A_LIST").assertIsOn()
        compose.onNodeWithTag("recipeFilterOrigin-TYPED").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(4L), library.uiState.value.recipes!!.map { it.id })

        compose.onNodeWithTag("recipeFilterOrigin-CHAT").performClick()
        compose.waitForIdle()
        // From a chat and in no list: nothing here is both.
        assertEquals(emptyList<Long>(), library.uiState.value.recipes!!.map { it.id })
        compose.onNodeWithTag("recipeFilterSwitch-NOT_IN_A_LIST").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(5L), library.uiState.value.recipes!!.map { it.id })
        compose.onNodeWithTag("recipeFilterSwitch-THIRTY_OR_LESS").performScrollTo().performClick()
        compose.waitForIdle()
        // Chat Stew has no time: 30 min or less never guesses.
        assertEquals(emptyList<Long>(), library.uiState.value.recipes!!.map { it.id })
        compose.onNodeWithTag("recipesFilterSheet").assertIsDisplayed()
    }

    @Test
    fun twoFiltersOnShowTwoPillsAndTheBadgeAndAPillsXRemovesOne() {
        show()
        compose.runOnIdle {
            library.onFilterChoicesChange(RecipeFilterChoices(cooked = CookedChoice.NOT_YET, notInAList = true))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("recipesFilterButton").assert(filtersOn(2))
        compose.onNodeWithTag("recipeFilterPill-NOT_COOKED").assertIsDisplayed()
        compose.onNodeWithTag("recipeFilterPill-NOT_IN_A_LIST")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Remove Not in a list")))
        assertTrue(shown("Quick Soup"))
        assertTrue(shown("Typed Pie"))
        assertTrue(!shown("Toast") && !shown("Slow Brisket"))

        compose.onNodeWithTag("recipeFilterPill-NOT_IN_A_LIST").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("recipeFilterPill-NOT_IN_A_LIST").assertCountEquals(0)
        compose.onNodeWithTag("recipesFilterButton").assert(filtersOn(1))
        compose.onNodeWithText("Slow Brisket").assertIsDisplayed()
    }

    @Test
    fun clearUnderSearchAndInTheSheetTurnsEveryFilterOff() {
        show()
        compose.runOnIdle {
            library.onFilterChoicesChange(RecipeFilterChoices(thirtyOrLess = true, origin = OriginChoice.TYPED))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("recipesFilterClear").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("recipeFilterPill-THIRTY_OR_LESS").assertCountEquals(0)
        compose.onAllNodesWithTag("recipesFilterClear").assertCountEquals(0)
        compose.onNodeWithTag("recipesFilterButton").assert(noFiltersOn())
        assertEquals(5, library.uiState.value.recipes!!.size)

        openSheet()
        compose.onNodeWithTag("recipeFilterSwitch-USES_PANTRY").performClick()
        compose.onNodeWithTag("recipeFilterCooked-COOKED").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("recipesFilterSheetClear").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("recipeFilterSwitch-USES_PANTRY").assertIsOff()
        compose.onNodeWithTag("recipeFilterCooked-ANY").assertIsSelected()
        assertTrue(library.uiState.value.choices.isEmpty)
    }

    /** German at twice the text: the Lists pill keeps its words whole, search takes the rest, the choices stack. */
    @Test
    @Config(qualifiers = "de-w360dp-h1600dp", fontScale = 2f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun inGermanAtTwiceTheTextListsNeverClipsAndTheChoicesStack() {
        show()
        val pill = compose.onNodeWithTag("recipesListsPill").fetchSemanticsNode()
        val search = compose.onNodeWithTag("recipesSearch").fetchSemanticsNode().boundsInRoot
        assertTrue("the pill is laid out whole: ${pill.boundsInRoot} vs ${pill.size}", pill.boundsInRoot.width >= pill.size.width - 1f)
        assertTrue("search keeps room beside it: $search", search.width > 0f && pill.boundsInRoot.right <= search.left)
        compose.onNodeWithText("Listen").assertIsDisplayed()

        openSheet()
        val any = compose.onNodeWithTag("recipeFilterOrigin-ANYWHERE").performScrollTo().fetchSemanticsNode().boundsInRoot
        val typed = compose.onNodeWithTag("recipeFilterOrigin-TYPED").performScrollTo().fetchSemanticsNode().boundsInRoot
        assertTrue("stacked, not side by side: $any, $typed", typed.top >= any.bottom - 1f)
        compose.onNodeWithText("Selbst eingegeben").assertExists()
    }

    // --- The flag off ---

    @Test
    fun withTheFlagOffDesignBStays() {
        show(filtersSheet = false)
        compose.onNodeWithTag("recipesViewsShelf").assertIsDisplayed()
        compose.onNodeWithTag("recipesLists").assertIsDisplayed()
        compose.onAllNodesWithTag("recipesListsPill").assertCountEquals(0)
        openSheet()
        compose.onNodeWithTag("recipeFilterSwitch-NOT_COOKED").assertIsDisplayed()
        compose.onAllNodesWithTag("recipeFilterCooked").assertCountEquals(0)
        compose.onAllNodesWithTag("recipeFilterSwitch-NOT_IN_A_LIST").assertCountEquals(0)
    }

    private companion object {
        const val NOW = 1_699_948_800_000L
    }
}
