package com.example.recipeclipper.ui.print

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipePrint.Block
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeDecisionRepository
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.listdetail.ListDetailScreen
import com.example.recipeclipper.ui.listdetail.ListDetailViewModel
import com.example.recipeclipper.ui.recipe.RecipeScreenFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Print or save as PDF (#318) from the recipe screen's menu, and a list's cookbook: real
 * ViewModels over the fakes, with a printer that records the job (a JVM test has no PDF engine;
 * the PDF itself is `PrintPdfTest`'s, on a device).
 */
@RunWith(AndroidJUnit4::class)
class PrintScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val printed = mutableListOf<PrintJob>()
    private val printer = Printer { _, job -> printed += job; true }
    private val recipes = FakeRecipeRepository()
    private fun printViewModel() = PrintViewModel(recipes, FakeAppPreferences(), FakeDecisionRepository())

    @Test
    fun theRecipeMenuPrintsTheRecipeAsShown() {
        val fixture = RecipeScreenFixture(print = printViewModel(), printer = printer)
        fixture.show(compose)
        fixture.viewModel.onServingsChange(8)
        fixture.viewModel.onNotesChange("Less salt")

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNode(hasText("Print or save as PDF") and hasText("This recipe as shown, ready for paper")).performClick()
        compose.waitForIdle()

        val job = printed.single() as PrintJob.Recipe
        assertEquals("Test Stew", job.title)
        assertTrue(job.blocks.contains(Block.Meta("Serves 8 (originally 4)")))
        assertTrue(job.blocks.contains(Block.Ingredient("4 cups flour")))
        assertEquals(Block.Note("Less salt"), job.blocks.last())
    }

    @Test
    fun withoutTheFlagTheRecipeMenuHasNoPrint() {
        RecipeScreenFixture().show(compose)

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Print or save as PDF")).fetchSemanticsNodes().size)
    }

    private fun showList(summaries: List<RecipeSummary>) {
        val lists = FakeListRepository().apply {
            lists.value = listOf(RecipeList(3, "Weeknights", isBuiltIn = false, isFavorites = false, recipeCount = summaries.size))
            recipesIn.value = summaries
        }
        val viewModel = ListDetailViewModel(lists, SavedStateHandle(mapOf(ListDetailViewModel.LIST_ID_ARG to 3L)))
        val print = printViewModel()
        compose.setContent {
            CompositionLocalProvider(LocalPrinter provides printer) {
                ListDetailScreen(onBack = {}, onOpenRecipe = {}, viewModel = viewModel, printViewModel = print)
            }
        }
    }

    @Test
    fun aListMakesACookbookOfItsRecipesInOrder() {
        recipes.recipes.value = mapOf(
            7L to RecipeScreenFixture.testRecipe().copy(id = 7, name = "Adobo"),
            8L to RecipeScreenFixture.testRecipe().copy(id = 8, name = "Carbonara")
        )
        showList(listOf(RecipeSummary(8, "Carbonara", null, null, 2, isSaved = true), RecipeSummary(7, "Adobo", null, null, 1, isSaved = true)))

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNode(hasText("Make a cookbook") and hasText("Every recipe here in one PDF")).performClick()
        compose.waitForIdle()

        val job = printed.single() as PrintJob.Cookbook
        assertEquals("Weeknights", job.title)
        assertEquals("Contents", job.contents)
        assertEquals(listOf("Carbonara", "Adobo"), job.recipes.map { it.first })
    }

    @Test
    fun anEmptyListOffersNoCookbook() {
        showList(emptyList())

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Make a cookbook")).fetchSemanticsNodes().size)
    }
}
