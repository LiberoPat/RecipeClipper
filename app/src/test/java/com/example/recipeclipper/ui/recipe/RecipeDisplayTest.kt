package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.local.AppSettings
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.ServingsScale
import com.example.recipeclipper.data.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** [RecipeDisplay] on its own (#234): the settings, servings and short steps the recipe renders with. */
class RecipeDisplayTest {

    private var shortSteps: List<String?> = emptyList()
    private val display = RecipeDisplay(shortSteps = { shortSteps }, decisions = { Decisions.NONE })

    private val recipe = Recipe(
        name = "Cake", image = null, ingredients = listOf("2 cups flour", "1 cup milk"),
        instructions = listOf("Mix everything together until smooth.", "Bake."), prepTime = null, cookTime = null,
        totalTime = null, yield = "4 servings", sourceUrl = "https://example.com/cake", id = 1
    )

    private fun loaded(state: RecipeUiState = RecipeUiState()) = state.copy(content = display.content(recipe, state))

    private val RecipeUiState.success get() = content as RecipeContent.Success

    // The lines as the renderer itself shows them under these settings and servings.
    private fun rendered(system: UnitSystem, servings: Int? = null) =
        RecipeRenderer.content(recipe.copy(servingsTarget = servings), RecipeRenderer.Settings(unitSystem = system)).ingredients

    @Test fun `a units change re-renders the recipe`() {
        val metric = display.withSettings(loaded(), AppSettings(unitSystem = UnitSystem.METRIC))

        assertEquals(UnitSystem.METRIC, metric.unitSystem)
        assertEquals(rendered(UnitSystem.METRIC), metric.success.ingredients)
        assertNotEquals(rendered(UnitSystem.AS_WRITTEN), metric.success.ingredients)
    }

    @Test fun `dark while cooking alone leaves the recipe as it was`() {
        val state = loaded()

        val dark = display.withSettings(state, AppSettings(darkWhileCooking = true))

        assertEquals(true, dark.darkWhileCooking)
        assertSame(state.content, dark.content)
    }

    @Test fun `a settings change keeps the chosen servings`() {
        val doubled = display.withServings(loaded(), 8)

        val metric = display.withSettings(doubled, AppSettings(unitSystem = UnitSystem.METRIC))

        assertEquals(ServingsScale(4, 8), metric.success.servings)
        assertEquals(rendered(UnitSystem.METRIC, servings = 8), metric.success.ingredients)
    }

    @Test fun `the units dropdown re-renders at once`() {
        val metric = display.withUnitSystem(loaded(), UnitSystem.METRIC)

        assertEquals(UnitSystem.METRIC, metric.unitSystem)
        assertEquals(rendered(UnitSystem.METRIC), metric.success.ingredients)
    }

    @Test fun `servings scale the ingredients, and the recipe's own yield is saved as none`() {
        val doubled = display.withServings(loaded(), 8)

        assertEquals(ServingsScale(4, 8), doubled.success.servings)
        assertEquals(rendered(UnitSystem.AS_WRITTEN, servings = 8), doubled.success.ingredients)
        assertNotEquals(rendered(UnitSystem.AS_WRITTEN), doubled.success.ingredients)
        assertEquals(8, display.savedServings(ServingsScale(4, 8)))
        assertNull(display.savedServings(ServingsScale(4, 4)))
    }

    @Test fun `nothing loaded is left as it is`() {
        val state = RecipeUiState()

        assertSame(state, display.withServings(state, 8))
        assertSame(state, display.rerender(state))
        assertSame(state, display.withShortSteps(state))
    }

    @Test fun `Chef mode's short steps are shown as they now stand`() {
        val state = loaded()
        shortSteps = listOf("Mix until smooth.", null)

        val shown = display.withShortSteps(state)

        assertEquals(listOf("Mix until smooth.", null), shown.success.shortInstructions)
    }
}
