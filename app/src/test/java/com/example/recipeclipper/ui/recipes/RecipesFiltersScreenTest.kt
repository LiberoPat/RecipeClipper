package com.example.recipeclipper.ui.recipes

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
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
import com.example.recipeclipper.R
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
 * (opening Lists, Back returning), the sheet's switches then its two choices, each control narrowing the list with its pill and
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
    private fun show(filtersSheet: Boolean = true, firstRow: String = "Quick Soup") {
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
        compose.waitUntil(5_000) { compose.onAllNodesWithText(firstRow).fetchSemanticsNodes().isNotEmpty() }
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
    fun theShelfIsGoneAndListsSitsInTheTitleRowAboveAFullWidthSearch() {
        show()
        compose.onAllNodesWithTag("recipesViewsShelf").assertCountEquals(0)
        compose.onAllNodesWithTag("recipesView-COOKED").assertCountEquals(0)
        val pill = compose.onNodeWithTag("recipesListsPill")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Lists, 3")))
            .fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithTag("libraryAdd").fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithTag("libraryMore").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("recipesSearch").fetchSemanticsNode().boundsInRoot
        assertTrue("Lists is left of the +, which is left of the menu: $pill, $add, $more", pill.right <= add.left && add.right <= more.left)
        assertTrue("on the title row's line: $pill, $add", pill.top < add.bottom && add.top < pill.bottom)
        assertTrue("search is on its own row below: $pill, $search", search.top >= pill.bottom - 1f)
        assertTrue("search takes the full width, as far right as the menu: $search, $more", search.right >= more.right - 24f)
        compose.onNodeWithTag("recipesFilterButton").assert(noFiltersOn())
    }

    @Test
    fun downTheListTheListsPillStaysInThePinnedBarAndOpensLists() {
        val many = (1L..30L).map { RecipeSummary(it, "Recipe $it", null, "20m", it, isSaved = false, language = "en") }
        repository.history.value = many
        repository.recent.value = many
        show(firstRow = "Recipe 1")
        compose.onAllNodesWithTag("libraryPinnedBar").assertCountEquals(0)
        compose.onNodeWithTag("recipesList").performScrollToIndex(25)
        compose.waitForIdle()
        compose.onNodeWithTag("libraryPinnedBar").assertIsDisplayed()
        val pill = compose.onNodeWithTag("recipesListsPillPinned")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Lists, 3")))
            .fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithTag("libraryAddPinned").fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithTag("libraryMorePinned").fetchSemanticsNode().boundsInRoot
        assertTrue("Lists, then the +, then the menu: $pill, $add, $more", pill.right <= add.left && add.right <= more.left)
        compose.onNodeWithTag("recipesListsPillPinned").performClick()
        compose.onNodeWithText("stub:lists").assertIsDisplayed()
    }

    /** A 360 dp phone at the default text size shows the whole placeholder, with no ellipsis, in every language. */
    private fun assertThePlaceholderIsWhole() {
        show()
        val text = compose.activity.getString(R.string.label_search_recipes)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("recipesSearch")), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(
            "\"$text\" not cut: ${layout.size}, line right ${layout.getLineRight(0)}, needs ${layout.multiParagraph.maxIntrinsicWidth}, ellipsized ${layout.isLineEllipsized(0)}",
            !layout.isLineEllipsized(0) && layout.getLineRight(0) <= layout.size.width + 1f
        )
        assertEquals("one line", 1, layout.lineCount)
    }

    @Test @Config(qualifiers = "w360dp-h1600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theSearchPlaceholderIsWholeOnA360dpPhone() = assertThePlaceholderIsWhole()

    @Test @Config(qualifiers = "es-w360dp-h1600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theSearchPlaceholderIsWholeOnA360dpPhoneInSpanish() = assertThePlaceholderIsWhole()

    @Test @Config(qualifiers = "fr-w360dp-h1600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theSearchPlaceholderIsWholeOnA360dpPhoneInFrench() = assertThePlaceholderIsWhole()

    @Test @Config(qualifiers = "de-w360dp-h1600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theSearchPlaceholderIsWholeOnA360dpPhoneInGerman() = assertThePlaceholderIsWhole()

    /** German is the one language kept whole at 1.5 times the text too ("Titel und Zutaten"). */
    @Test @Config(qualifiers = "de-w360dp-h1600dp", fontScale = 1.5f) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theSearchPlaceholderIsWholeInGermanAtOneAndAHalfTimesTheText() = assertThePlaceholderIsWhole()

    @Test @Config(qualifiers = "it-w360dp-h1600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theSearchPlaceholderIsWholeOnA360dpPhoneInItalian() = assertThePlaceholderIsWhole()

    @Test @Config(qualifiers = "pt-rBR-w360dp-h1600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theSearchPlaceholderIsWholeOnA360dpPhoneInPortuguese() = assertThePlaceholderIsWhole()

    /** The title row in each language at 360 dp: the title whole, the + and the menu on screen, the pill whole. */
    private fun assertTheTitleRowFits(title: String) {
        show()
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithTag("libraryMore").fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithTag("libraryAdd").fetchSemanticsNode().boundsInRoot
        assertTrue("the menu is on screen: $more in $screen", more.right <= screen.right + 1f && add.left >= 0f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(title) and isHeading(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$title on one line", 1, layouts.single().lineCount)
        layouts.single().let { assertTrue("$title not cut: size ${it.size}, line right ${it.getLineRight(0)}, ellipsized ${it.isLineEllipsized(0)}, overflow ${it.didOverflowWidth}", !it.isLineEllipsized(0) && it.getLineRight(0) <= it.size.width + 1f) }
        val pill = compose.onAllNodesWithTag("recipesListsPill").fetchSemanticsNodes() +
            compose.onAllNodesWithTag("recipesListsPill-compact").fetchSemanticsNodes()
        assertEquals("exactly one Lists pill is in the title row", 1, pill.size)
        val b = pill.single().boundsInRoot
        assertTrue("the pill is on screen: $b", b.left >= 0f && b.right <= screen.right + 1f)
    }

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test @Config(qualifiers = "w360dp-h1600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theTitleRowFitsInEnglishOn360dp() = assertTheTitleRowFits("Recipes")

    @Test @Config(qualifiers = "es-w360dp-h1600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theTitleRowFitsInSpanishOn360dp() = assertTheTitleRowFits("Recetas")

    @Test @Config(qualifiers = "fr-w360dp-h1600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theTitleRowFitsInFrenchOn360dp() = assertTheTitleRowFits("Recettes")

    @Test @Config(qualifiers = "de-w360dp-h1600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theTitleRowFitsInGermanOn360dp() = assertTheTitleRowFits("Rezepte")

    @Test @Config(qualifiers = "it-w360dp-h1600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theTitleRowFitsInItalianOn360dp() = assertTheTitleRowFits("Ricette")

    @Test @Config(qualifiers = "pt-rBR-w360dp-h1600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theTitleRowFitsInPortugueseOn360dp() = assertTheTitleRowFits("Receitas")

    /** At 1.3 times the text the pill gives up its word and keeps its icon and count (English, 360 dp). */
    @Test @Config(qualifiers = "w360dp-h1600dp", fontScale = 1.5f) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun atLargeTextTheTitleRowStillFitsWithThePillAsIconAndCount() {
        assertTheTitleRowFits("Recipes")
        compose.onAllNodesWithTag("recipesListsPill").assertCountEquals(0)
        compose.onNodeWithTag("recipesListsPill-compact")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Lists, 3")))
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
    fun theSheetHoldsTheSwitchesThenTheTwoChoicesWithAnyAndAnywhereChosen() {
        show()
        openSheet()
        // Read every position before any scroll, so they share one frame: the three switches, then Cooked, then
        // Came from (the owner's regrouping by kind of control, 2026-11-23); no group headings.
        fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
        val switches = listOf("THIRTY_OR_LESS", "USES_PANTRY", "NOT_IN_A_LIST").map { top("recipeFilterSwitch-$it") }
        val cooked = top("recipeFilterCooked-ANY")
        val origin = top("recipeFilterOrigin-ANYWHERE")
        assertEquals("the switches in order", switches.sorted(), switches)
        assertTrue("the switches come before the choices: $switches, $cooked", switches.max() < cooked)
        assertTrue("Cooked, then Came from: $cooked, $origin", cooked < origin)
        compose.onAllNodesWithText("THE RECIPE").assertCountEquals(0)
        compose.onAllNodesWithText("WHERE IT IS, WHERE IT CAME FROM").assertCountEquals(0)
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

    /** German at twice the text: the title row keeps everything on screen, the count never breaks, the choices stack. */
    @Test
    @Config(qualifiers = "de-w360dp-h1600dp", fontScale = 2f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun inGermanAtTwiceTheTextListsNeverClipsAndTheChoicesStack() {
        assertTheTitleRowFits("Rezepte")
        val search = compose.onNodeWithTag("recipesSearch").fetchSemanticsNode().boundsInRoot
        assertTrue("search has the width: $search", search.width > 0f)
        // The count never breaks inside a word ("Reze / pte"): one line, the sort moving under it if need be.
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("recipesCountLine").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("the count on one line", 1, layouts.single().lineCount)
        assertEquals("5 Rezepte", layouts.single().layoutInput.text.text)
        val count = layouts.single()
        assertTrue(
            "not cut short: ${count.size}, the line ends at ${count.getLineRight(0)}",
            !count.isLineEllipsized(0) && count.getLineRight(0) <= count.size.width + 1f
        )

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
