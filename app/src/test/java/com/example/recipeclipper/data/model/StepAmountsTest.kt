package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Steps modelled on real recipe pages; each amount is shown in ⟦ ⟧. */
class StepAmountsTest {

    private fun annotate(step: String, lines: List<String>, lang: String = "en"): String =
        StepAmounts.marked(StepAmounts.annotate(listOf(step), lines, LanguageWords.forTag(lang)).single())

    @Test fun `the article gives way to the line's amount`() {
        assertEquals("Add ⟦2⟧ carrots and cook 5 minutes.", annotate("Add the carrots and cook 5 minutes.", listOf("2 carrots, peeled and diced")))
        assertEquals("Stir in ⟦250 g⟧ flour.", annotate("Stir in the flour.", listOf("250 g all-purpose flour")))
        assertEquals("Beat ⟦2 large⟧ eggs well.", annotate("Beat the eggs well.", listOf("2 large eggs")))
        assertEquals("Add ⟦115 g⟧ melted butter.", annotate("Add the melted butter.", listOf("115 g unsalted butter")))
    }

    @Test fun `a mention with no article takes the amount after a verb, a list comma or a joining word`() {
        assertEquals(
            "Whisk ⟦2 cups⟧ flour, ⟦1 tsp⟧ baking powder and ⟦1/2 tsp⟧ salt together.",
            annotate("Whisk flour, baking powder and salt together.", listOf("2 cups flour", "1 tsp baking powder", "1/2 tsp salt"))
        )
    }

    @Test fun `the amount follows the servings and units the reading view shows`() {
        val lines = IngredientRendering.render(listOf("1 cup all-purpose flour"), 2.0, UnitSystem.METRIC, false)
        assertEquals("Stir in ⟦240 g⟧ flour.", annotate("Stir in the flour.", lines))
    }

    @Test fun `the same ingredient in two lines stays as written`() {
        val lines = listOf("1 cup sugar", "For the frosting:", "1/2 cup sugar")
        assertEquals("Add the sugar.", annotate("Add the sugar.", lines))
        assertEquals("Season with salt.", annotate("Season with salt.", listOf("1 tsp salt", "salt and pepper")))
    }

    @Test fun `a step that already says how much stays as written`() {
        val lines = listOf("2 cups flour", "1 cup sugar", "115 g butter")
        for (step in listOf(
            "Add 1 cup of the flour.", "Melt half the butter.", "Add the remaining sugar.",
            "Add the rest of the flour.", "Add 2 tablespoons butter.", "Add a little sugar."
        )) assertEquals(step, annotate(step, lines))
    }

    @Test fun `a longer name, compound or prose around the word stays as written`() {
        val lines = listOf("2 cups flour", "1 lemon")
        for (step in listOf("Dust with rice flour.", "Fold in the flour mixture.", "Add the lemon juice.", "Flour the counter."))
            assertEquals(step, annotate(step, lines))
        assertEquals(
            "Add ⟦1 cup⟧ brown sugar and ⟦1 cup⟧ sugar.",
            annotate("Add the brown sugar and the sugar.", listOf("1 cup brown sugar", "1 cup sugar"))
        )
    }

    @Test fun `a line with no amount it can scale, or used in parts, lends none`() {
        assertEquals("Chop the onions.", annotate("Chop the onions.", listOf("2 onions (about 300 g)")))
        assertEquals("Season with salt.", annotate("Season with salt.", listOf("salt, to taste")))
        assertEquals("Add the flour.", annotate("Add the flour.", listOf("2 cups flour, divided")))
        assertEquals("Add the salt.", annotate("Add the salt.", listOf("1 tsp salt, plus more to taste")))
    }

    @Test fun `only the first mention of a line in a step`() {
        assertEquals(
            "Melt ⟦4 tbsp⟧ butter, then brush the pan with butter.",
            annotate("Melt the butter, then brush the pan with butter.", listOf("4 tbsp butter"))
        )
    }

    @Test fun `other languages use their own articles and words`() {
        assertEquals("Ajoutez ⟦200 g de⟧ farine et ⟦3⟧ œufs.", annotate("Ajoutez la farine et les œufs.", listOf("200 g de farine", "3 œufs"), "fr"))
        assertEquals("⟦200 g⟧ Butter schmelzen.", annotate("Die Butter schmelzen.", listOf("200 g Butter"), "de"))
        assertEquals("Añade ⟦250 g de⟧ harina poco a poco.", annotate("Añade la harina poco a poco.", listOf("250 g de harina"), "es"))
        assertEquals("Aggiungete ⟦2⟧ carote.", annotate("Aggiungete le carote.", listOf("2 carote"), "it"))
        assertEquals("Junte ⟦2⟧ ovos e misture.", annotate("Junte os ovos e misture.", listOf("2 ovos"), "pt"))
        assertEquals("Ajoutez le reste de la farine.", annotate("Ajoutez le reste de la farine.", listOf("200 g de farine"), "fr"))
        assertEquals("醤油を加える。", annotate("醤油を加える。", listOf("醤油 大さじ1"), "ja"))
    }

    @Test fun `no words leave every step as written`() {
        assertEquals(listOf(listOf(StepAmounts.Part("Add the carrots."))), StepAmounts.annotate(listOf("Add the carrots."), listOf("2 carrots"), null))
    }
}
