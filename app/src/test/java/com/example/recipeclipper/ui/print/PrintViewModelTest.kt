package com.example.recipeclipper.ui.print

import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipePrint
import com.example.recipeclipper.data.model.RecipePrint.Block
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeDecisionRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrintViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun recipe(id: Long, name: String, servingsTarget: Int? = null) = Recipe(
        name = name, image = null, ingredients = listOf("1 cup milk", "For the top:", "2 oz butter"),
        instructions = listOf("Stir."), prepTime = null, cookTime = null, totalTime = null,
        yield = "2 servings", sourceUrl = "https://example.com/$id", id = id, notes = "My note",
        servingsTarget = servingsTarget
    )

    private val recipes = FakeRecipeRepository().apply {
        this.recipes.value = mapOf(1L to recipe(1, "Pancakes", servingsTarget = 4), 2L to recipe(2, "Waffles"))
    }
    private val preferences = FakeAppPreferences(unitSystem = UnitSystem.METRIC)
    private val viewModel = PrintViewModel(recipes, preferences, FakeDecisionRepository(), rendering = mainDispatcherRule.dispatcher)

    private fun idle() = mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    private fun cookbook() = viewModel.uiState.value.job as PrintJob.Cookbook

    @Test fun `a recipe is printed as handed over`() {
        val blocks = listOf(Block.Title("Pancakes"))
        viewModel.printRecipe("Pancakes", blocks)
        assertEquals(PrintJob.Recipe("Pancakes", blocks), viewModel.uiState.value.job)
        viewModel.onDone()
        assertNull(viewModel.uiState.value.job)
        assertFalse(viewModel.uiState.value.busy)
    }

    @Test fun `a cookbook holds the list's recipes in order, a missing one skipped`() {
        viewModel.makeCookbook("Breakfast", listOf(2L, 9L, 1L), RecipePrint.Labels.ENGLISH, "Contents")
        idle()
        assertEquals("Breakfast", cookbook().title)
        assertEquals("Contents", cookbook().contents)
        assertEquals(listOf("Waffles", "Pancakes"), cookbook().recipes.map { it.first })
    }

    @Test fun `each recipe renders as the reading view opens it, at its servings and in the cook's units`() {
        viewModel.makeCookbook("Breakfast", listOf(1L), RecipePrint.Labels.ENGLISH, "Contents")
        idle()
        val blocks = cookbook().recipes.single().second
        assertTrue(blocks.contains(Block.Meta("Serves 4 (originally 2)")))
        assertEquals(
            listOf(Block.Ingredient("480 ml milk"), Block.GroupHeading("For the top:"), Block.Ingredient("115 g butter")),
            blocks.filter { it is Block.Ingredient || it is Block.GroupHeading }
        )
    }

    @Test fun `a cookbook leaves the cook's notes out`() {
        viewModel.makeCookbook("Breakfast", listOf(1L), RecipePrint.Labels.ENGLISH, "Contents")
        idle()
        assertFalse(cookbook().recipes.single().second.any { it is Block.Note })
    }

    @Test fun `a cookbook with nothing left in it fails`() {
        viewModel.makeCookbook("Breakfast", listOf(9L), RecipePrint.Labels.ENGLISH, "Contents")
        idle()
        assertTrue(viewModel.uiState.value.failed)
        viewModel.onFailureShown()
        assertFalse(viewModel.uiState.value.failed)
    }

    @Test fun `a cookbook reads its recipes in one query`() {
        viewModel.makeCookbook("Breakfast", listOf(2L, 1L), RecipePrint.Labels.ENGLISH, "Contents")
        idle()
        assertEquals(listOf(listOf(2L, 1L)), recipes.byIdsQueries)
        assertEquals(2, cookbook().recipes.size)
    }

    @Test fun `a cookbook is busy until it is handed over, and the screen draws it`() {
        viewModel.makeCookbook("Breakfast", listOf(1L), RecipePrint.Labels.ENGLISH, "Contents")
        assertTrue(viewModel.uiState.value.busy)
        idle()
        assertTrue(viewModel.uiState.value.busy)
        viewModel.onDone()
        assertEquals(PrintUiState(), viewModel.uiState.value)
    }

    @Test fun `leaving the screen drops the job, built or still building`() {
        viewModel.makeCookbook("Breakfast", listOf(1L), RecipePrint.Labels.ENGLISH, "Contents")
        viewModel.onLeave()
        idle()
        assertEquals(PrintUiState(), viewModel.uiState.value)

        viewModel.printRecipe("Pancakes", listOf(Block.Title("Pancakes")))
        viewModel.onLeave()
        assertEquals(PrintUiState(), viewModel.uiState.value)
    }

    @Test fun `the failure line going leaves a job started meanwhile alone`() {
        viewModel.makeCookbook("Breakfast", listOf(9L), RecipePrint.Labels.ENGLISH, "Contents")
        idle()
        assertTrue(viewModel.uiState.value.failed)
        val job = PrintJob.Recipe("Pancakes", listOf(Block.Title("Pancakes")))
        viewModel.printRecipe(job.title, job.blocks)
        viewModel.onFailureShown()
        assertEquals(PrintUiState(busy = true, job = job), viewModel.uiState.value)
    }
}
