package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.Servings
import com.example.recipeclipper.data.model.ServingsScale
import com.example.recipeclipper.data.model.StepAmounts
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RecipeRenderer] on its own (#169): what the screen shows of a recipe under given settings.
 * The differential corpus's `Render` rows pin the same to the Swift port.
 */
class RecipeRendererTest {

    private val steps = listOf("Preheat the oven to 350°F.", "Whisk the flour and sugar, then bake 20 minutes.")

    private fun recipe(
        yield: String? = "4 servings",
        target: Int? = null,
        ingredients: List<String> = listOf("2 cups flour", "1 cup sugar")
    ) = Recipe(
        name = "Cake", image = null, ingredients = ingredients, instructions = steps, prepTime = null,
        cookTime = null, totalTime = null, yield = yield, sourceUrl = "https://www.example.com/cake",
        language = "en", servingsTarget = target
    )

    private val metric = RecipeRenderer.Settings(
        unitSystem = UnitSystem.METRIC, temperatureUnit = TemperatureUnit.CELSIUS, amountsInSteps = true
    )

    @Test fun `as written shows the recipe's own lines, steps, timers and source`() {
        val shown = RecipeRenderer.content(recipe(), RecipeRenderer.Settings())

        assertEquals(ServingsScale(base = 4, target = 4), shown.servings)
        assertEquals(listOf("2 cups flour", "1 cup sugar"), shown.ingredients)
        assertEquals(steps, shown.instructions)
        assertEquals(listOf(null, 20 * 60), shown.stepTimerSeconds)
        assertEquals("example.com", shown.sourceDomain)
        assertNull("amounts in steps are off", shown.stepAmounts)
        assertEquals(emptyList<String?>(), shown.shortInstructions)
    }

    @Test fun `the chosen servings scale the lines before they are converted`() {
        val shown = RecipeRenderer.content(recipe(target = 8), metric)

        assertEquals(ServingsScale(base = 4, target = 8), shown.servings)
        assertEquals(listOf("480 g flour", "400 g sugar"), shown.ingredients)
        assertEquals("Preheat the oven to 180°C.", shown.instructions[0])
        assertEquals(
            "Whisk ⟦480 g⟧ flour and ⟦400 g⟧ sugar, then bake 20 minutes.",
            StepAmounts.marked(shown.stepAmounts!![1])
        )
    }

    @Test fun `chosen servings stay within the stepper, and a yield with no number has none`() {
        assertEquals(1, RecipeRenderer.content(recipe(target = 0), metric).servings?.target)
        assertEquals(Servings.MAX, RecipeRenderer.content(recipe(target = 1000), metric).servings?.target)
        assertNull(RecipeRenderer.content(recipe(yield = "Makes plenty", target = 6), metric).servings)
        assertNull(RecipeRenderer.content(recipe(yield = null), metric).servings)
    }

    @Test fun `new servings rescale only the ingredients and their amounts in steps`() {
        val shown = RecipeRenderer.content(recipe(), metric)

        val doubled = RecipeRenderer.withServings(shown, 8, metric)!!

        assertEquals(8, doubled.servings?.target)
        assertEquals(listOf("480 g flour", "400 g sugar"), doubled.ingredients)
        assertEquals(shown.instructions, doubled.instructions)
        assertEquals(
            "Whisk ⟦480 g⟧ flour and ⟦400 g⟧ sugar, then bake 20 minutes.",
            StepAmounts.marked(doubled.stepAmounts!![1])
        )
        assertEquals(Servings.MAX, RecipeRenderer.withServings(shown, 500, metric)?.servings?.target)
        assertNull(RecipeRenderer.withServings(RecipeRenderer.content(recipe(yield = null), metric), 8, metric))
    }

    @Test fun `rendering again under new settings keeps the chosen servings and the short steps`() {
        val shorts = listOf(null, "Whisk flour and sugar; bake 20 min at 350°F.")
        val shown = RecipeRenderer.content(recipe(target = 8), RecipeRenderer.Settings(), shorts)

        val again = RecipeRenderer.rerender(shown, metric, shorts)

        assertEquals(RecipeRenderer.content(recipe(target = 8), metric, shorts), again)
        assertEquals(8, again.servings?.target)
        assertEquals(listOf(null, "Whisk flour and sugar; bake 20 min at 180°C."), again.shortInstructions)
    }

    @Test fun `short steps show only one per step, rendered like the steps, with their own amounts`() {
        val shown = RecipeRenderer.content(recipe(), metric)

        val short = RecipeRenderer.withShortSteps(shown, listOf("Oven to 350°F.", null), metric)
        assertEquals(listOf("Oven to 180°C.", null), short.shortInstructions)
        assertEquals(listOf("Oven to 180°C.", ""), short.shortStepAmounts?.map(StepAmounts::marked))

        val stale = RecipeRenderer.withShortSteps(shown, listOf("Oven to 350°F."), metric)
        assertEquals(emptyList<String?>(), stale.shortInstructions)
        assertNull(stale.shortStepAmounts)

        val none = RecipeRenderer.withShortSteps(shown, listOf(null, null), metric)
        assertNull("no short step, no amounts for them", none.shortStepAmounts)
    }

    @Test fun `the model's decided count brackets reach the ingredients`() {
        val apples = "3 large apples, peeled and sliced (about 3 cups)"
        val doubled = recipe(target = 8, ingredients = listOf(apples))
        val total = Decisions(mapOf(DecisionQuestion.countBracket(apples, "en") to "total"))

        val asToday = RecipeRenderer.content(doubled, RecipeRenderer.Settings())
        val decided = RecipeRenderer.content(doubled, RecipeRenderer.Settings(decisions = total))

        assertNotEquals(asToday.ingredients, decided.ingredients)
        assertEquals(listOf("6 large apples, peeled and sliced (about 6 cups)"), decided.ingredients)
    }
}
