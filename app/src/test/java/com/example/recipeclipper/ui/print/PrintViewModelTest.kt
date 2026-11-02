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
    private val viewModel = PrintViewModel(recipes, preferences, FakeDecisionRepository())

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
}
