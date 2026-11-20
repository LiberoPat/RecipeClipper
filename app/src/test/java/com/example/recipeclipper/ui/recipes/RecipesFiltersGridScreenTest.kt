package com.example.recipeclipper.ui.recipes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.RecipesLayout
import com.example.recipeclipper.data.model.SavedRecipe
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.passTheSearchDebounce
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The chips under Recipes' search (#378) and the photo grid (#380) on screen: the chips filter
 * the rows and combine with the search, the pantry chip says on each row how many it has, and the
 * toggle beside the sort turns the rows into a grid of titled tiles that open their recipe.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1600dp")
class RecipesFiltersGridScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()
    private val preferences = FakeAppPreferences()

    private val adobo = summary(1, "Chicken Adobo", total = "45m")
    private val salad = summary(2, "Quick Salad", total = "15m")
    private val bread = summary(3, "Banana Bread", total = "1h 10m")

    private fun summary(id: Long, title: String, total: String? = null, cooked: Long? = null) =
        RecipeSummary(id, title, null, total, 0, isSaved = false, lastCookedDay = cooked, language = "en")

    private fun item(name: String) =
        PantryItem(name.hashCode().toLong(), name, null, "en", Aisle.OTHER, true, false, null, null)

    private var opened: Long? = null

    private fun show(
        quickFilters: Boolean = true,
        gridEnabled: Boolean = true,
        pantry: FakePantryRepository? = null,
        save: SaveToListViewModel? = null,
        recipes: List<RecipeSummary> = listOf(adobo, salad, bread)
    ) {
        repository.history.value = recipes
        val viewModel = RecipesViewModel(repository, preferences, pantry = pantry, lists = FakeListRepository())
        compose.setContent {
            RecipesScreen(
                onBack = {},
                onOpenRecipe = { opened = it },
                viewModel = viewModel,
                quickFilters = quickFilters,
                gridEnabled = gridEnabled,
                oneCookbook = save != null,
                saveViewModel = save,
                scanEnabled = false
            )
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitGone(text: String) = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    // --- Chips ---

    @Test
    fun theChipsAreUnderSearchAndEachOneNarrowsTheRows() {
        show()
        waitForText("Chicken Adobo")
        compose.onNodeWithText("Under 30 min").assertIsDisplayed()
        compose.onNodeWithText("Not cooked yet").assertIsDisplayed()
        compose.onNodeWithText("Uses what's in my pantry").assertIsDisplayed()

        compose.onNodeWithTag("recipeQuick-UNDER_30").assertIsOff().performClick()
        waitGone("Chicken Adobo")
        compose.onNodeWithTag("recipeQuick-UNDER_30").assertIsOn()
        compose.onNodeWithText("Quick Salad").assertIsDisplayed()
        compose.onAllNodesWithText("Banana Bread").assertCountEquals(0)
        compose.onNodeWithText("1 recipe").assertIsDisplayed()

        // Off again, every recipe is back.
        compose.onNodeWithTag("recipeQuick-UNDER_30").performClick()
        waitForText("Chicken Adobo")
        compose.onNodeWithText("3 recipes").assertIsDisplayed()
    }

    @Test
    fun chipsCombineWithEachOtherAndWithTheSearch() {
        show(recipes = listOf(adobo, salad.copy(lastCookedDay = 20_000), bread.copy(totalTime = "20m"), summary(4, "Tuna Melt", total = "10m")))
        waitForText("Chicken Adobo")

        compose.onNodeWithTag("recipeQuick-UNDER_30").performClick()
        compose.onNodeWithTag("recipeQuick-NOT_COOKED").performClick()
        waitGone("Chicken Adobo") // 45m
        waitGone("Quick Salad") // cooked
        compose.onNodeWithText("Banana Bread").assertIsDisplayed()
        compose.onNodeWithText("Tuna Melt").assertIsDisplayed()

        // The search is the repository's; what it answers the chips still narrow.
        compose.onNodeWithText("Search titles and ingredients").performTextInput("tuna")
        repository.history.value = listOf(summary(4, "Tuna Melt", total = "10m"), adobo)
        passTheSearchDebounce()
        waitGone("Banana Bread")
        compose.onNodeWithText("Tuna Melt").assertIsDisplayed()
        compose.onAllNodesWithText("Chicken Adobo").assertCountEquals(0)
    }

    @Test
    fun aChipThatLeavesNothingSaysSoAndKeepsTheChipsToTurnItOff() {
        show(recipes = listOf(bread))
        waitForText("Banana Bread")
        compose.onNodeWithTag("recipeQuick-UNDER_30").performClick()
        waitForText("No recipes match these filters.")
        compose.onNodeWithTag("recipeQuick-UNDER_30").assertIsOn()
    }

    @Test
    fun withTheFlagOffThereAreNoChipsAndNoToggle() {
        preferences.recipesLayout = RecipesLayout.GRID // stored, but the flag is off: rows
        show(quickFilters = false, gridEnabled = false)
        waitForText("Chicken Adobo")
        compose.onAllNodesWithTag("recipeQuick-UNDER_30").assertCountEquals(0)
        compose.onAllNodesWithTag("recipes.layout").assertCountEquals(0)
        compose.onAllNodesWithTag("recipesGrid").assertCountEquals(0)
    }

    @Test
    fun thePantryChipSaysOnEachRowHowManyIngredientsItHasAndExplainsItself() {
        val lines = listOf(
            SavedRecipe(1, "Chicken Adobo", "en", listOf("2 lb chicken thighs", "1/2 cup soy sauce", "4 cloves garlic", "1 cup rice flour")),
            SavedRecipe(2, "Quick Salad", "en", listOf("1 head lettuce", "2 tomatoes")),
            SavedRecipe(3, "Banana Bread", "en", listOf("3 bananas", "2 cups flour", "2 eggs", "1/2 cup sugar"))
        )
        repository.recipeLines.value = lines
        val pantry = FakePantryRepository(listOf(item("chicken thighs"), item("soy sauce"), item("flour")))
        show(pantry = pantry)
        waitForText("Chicken Adobo")

        compose.onNodeWithTag("recipeQuick-USES_PANTRY").performClick()
        // Nothing is shown until the pantry is matched; then the covered recipes, each with its count.
        waitForText("You have 2 of 4")
        compose.onAllNodesWithText("Quick Salad").assertCountEquals(0)
        // Half is enough. The adobo has two of four ("rice flour" is never "flour"); the bread one of four: not enough.
        compose.onNodeWithText("You have 2 of 4").assertIsDisplayed()
        compose.onAllNodesWithText("Banana Bread").assertCountEquals(0)
        compose.onNodeWithTag("recipeQuickPantryNote").assertIsDisplayed()
        compose.onNodeWithText("1 recipe").assertIsDisplayed()

        // Live: stocking the sugar and eggs brings the bread in, saying so.
        pantry.items.value = pantry.items.value + item("eggs") + item("sugar")
        waitForText("Banana Bread")
        compose.onNodeWithText("You have 3 of 4").assertIsDisplayed()
    }

    // --- Grid ---

    @Test
    fun theToggleBesideTheSortTurnsTheRowsIntoAGridOfTitledTilesAndBack() {
        show()
        waitForText("Chicken Adobo")
        compose.onAllNodesWithTag("recipesGrid").assertCountEquals(0)
        compose.onNodeWithContentDescription("Show as a grid").assertIsDisplayed().performClick()

        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("recipesGrid").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(RecipesLayout.GRID, preferences.recipesLayout)
        compose.onNodeWithText("Chicken Adobo").assertIsDisplayed()
        compose.onNodeWithText("Banana Bread").assertIsDisplayed()
        compose.onNodeWithText("45m").assertIsDisplayed() // the time under the title, as a row has it
        // The search, the chips and the sort are still above it.
        compose.onNodeWithTag("recipes.sort").assertIsDisplayed()
        compose.onNodeWithText("Under 30 min").assertIsDisplayed()

        compose.onNodeWithText("Quick Salad").performClick()
        assertEquals(2L, opened)

        compose.onNodeWithContentDescription("Show as a list").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("recipesGrid").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Chicken Adobo").assertIsDisplayed()
        assertEquals(RecipesLayout.ROWS, preferences.recipesLayout)
    }

    @Test
    fun theStoredGridOpensAsAGridAndTheChipsStillNarrowIt() {
        preferences.recipesLayout = RecipesLayout.GRID
        show()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("recipesGrid").fetchSemanticsNodes().isNotEmpty() }
        waitForText("Chicken Adobo")
        compose.onNodeWithTag("recipeQuick-UNDER_30").performClick()
        waitGone("Chicken Adobo")
        compose.onNodeWithText("Quick Salad").assertIsDisplayed()
        compose.onNodeWithTag("recipesGrid").assertIsDisplayed()
    }

    @Test
    fun aGridTileKeepsTheBookmarkAsTheSaveControl() {
        val lists = FakeListRepository()
        val save = SaveToListViewModel(lists)
        repository.history.value = listOf(adobo.copy(isSaved = true), salad)
        val viewModel = RecipesViewModel(repository, FakeAppPreferences(recipesLayout = RecipesLayout.GRID), lists = lists)
        compose.setContent {
            RecipesScreen(
                onBack = {}, onOpenRecipe = {}, viewModel = viewModel, quickFilters = true, gridEnabled = true,
                oneCookbook = true, saveViewModel = save, scanEnabled = false
            )
        }
        waitForText("Chicken Adobo")
        // Filled on a recipe in a list, outlined (and tappable) on one in none.
        compose.onNodeWithContentDescription("Saved to a list").assertIsDisplayed()
        compose.onNodeWithTag("saveToList-2").assertIsDisplayed().performClick()
        compose.onNodeWithText("New list", substring = true).assertIsDisplayed()
    }
}
