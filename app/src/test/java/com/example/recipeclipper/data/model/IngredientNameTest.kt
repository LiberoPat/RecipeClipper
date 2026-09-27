package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientNameTest {

    private fun name(line: String) = IngredientName.of(line)

    @Test fun `drops the amount, unit and preparation`() {
        assertEquals("all purpose flour", name("1 cup all-purpose flour"))
        assertEquals("unsalted butter", name("3 Tbsp. unsalted butter, melted"))
        assertEquals("eggs", name("2 large eggs, beaten"))
        assertEquals("light brown sugar", name("3/4 cup packed light brown sugar"))
        assertEquals("fresh lemon juice", name("2-3 tablespoons fresh lemon juice"))
        assertEquals("onions", name("2 medium onions, finely chopped"))
    }

    @Test fun `drops an old-style unit, c, T or t`() {
        assertEquals("heavy cream", name("1/2 c. heavy cream"))
        assertEquals("flour", name("2 C flour"))
        assertEquals("butter", name("2 T. butter"))
        assertEquals("salt", name("1 t salt"))
    }

    @Test fun `drops alternate measures, package sizes and compound amounts`() {
        assertEquals("all purpose flour", name("1 1/2 cups (190 g) all-purpose flour"))
        assertEquals("bread flour", name("1 cup/120 grams bread flour"))
        assertEquals("flour", name("1 cup plus 2 tbsp (140 g) flour"))
        assertEquals("diced tomatoes", name("1 (14 oz) can diced tomatoes"))
        assertEquals("coconut milk", name("1 can (14 oz) coconut milk"))
        assertEquals("cream cheese", name("1 (8-ounce) package cream cheese, softened"))
    }

    @Test fun `drops sizes and containers, and the trailing clauses`() {
        assertEquals("garlic", name("3 cloves garlic, minced"))
        assertEquals("salt", name("1 pinch of salt"))
        assertEquals("nutmeg", name("a pinch of nutmeg"))
        assertEquals("flour", name("1 heaping cup flour"))
        assertEquals("milk", name("1 quart milk"))
        assertEquals("chicken breasts", name("4 boneless, skinless chicken breasts"))
        assertEquals("fresh ginger", name("1-inch piece fresh ginger, peeled and grated"))
        assertEquals("olive oil", name("1 tablespoon olive oil, plus more for drizzling"))
        assertEquals("kosher salt", name("Kosher salt, to taste"))
        assertEquals("fresh basil leaves", name("Fresh basil leaves, for serving"))
        assertEquals("vegetable oil", name("Vegetable oil, for frying"))
    }

    @Test fun `a conjunction inside a table alias is part of the name`() {
        assertEquals("half and half", name("1 cup half-and-half"))
    }

    @Test fun `what isn't one ingredient has no name`() {
        assertNull(name(""))
        assertNull(name("   "))
        assertNull(name("For the frosting:"))
        assertNull(name("Salt and pepper, to taste"))
        assertNull(name("1/2 cup butter or margarine"))
        assertNull(name("Juice of 1 lemon"))
    }

    @Test fun `matches only the same ingredient, with plain modifiers`() {
        assertTrue(IngredientName.matches("unsalted butter", "butter"))
        assertTrue(IngredientName.matches("Butter", "unsalted butter"))
        assertTrue(IngredientName.matches("flour", "flour"))
        assertTrue(IngredientName.matches("all-purpose flour", "flour"))
        assertTrue(IngredientName.matches("extra virgin olive oil", "olive oil"))
        assertTrue(IngredientName.matches("large eggs", "eggs"))
        assertFalse(IngredientName.matches("butter beans", "butter"))
        assertFalse(IngredientName.matches("flour tortillas", "flour"))
        assertFalse(IngredientName.matches("", "butter"))
        assertFalse(IngredientName.matches("buttermilk", "milk"))
    }

    @Test fun `a compound name never matches its shorter head noun, either way`() {
        for ((compound, head) in listOf(
            "rice flour" to "flour", "almond flour" to "flour", "peanut butter" to "butter",
            "apple butter" to "butter", "condensed milk" to "milk", "coconut milk" to "milk",
            "brown sugar" to "sugar", "whole milk" to "milk", "salted butter" to "unsalted butter"
        )) {
            assertFalse("$compound / $head", IngredientName.matches(compound, head))
            assertFalse("$head / $compound", IngredientName.matches(head, compound))
        }
        val de = LanguageWords.forTag("de")!!
        assertTrue(IngredientName.matches("ungesalzene Butter", "Butter", de))
        assertFalse(IngredientName.matches("Erdnuss Butter", "Butter", de))
        val fr = LanguageWords.forTag("fr")!!
        assertFalse(IngredientName.matches("farine de riz", "riz", fr))
        val ja = LanguageWords.forTag("ja")!!
        assertTrue(IngredientName.matches("無塩バター", "バター", ja))
        assertFalse(IngredientName.matches("ピーナッツバター", "バター", ja))
    }

    @Test fun `render scales, then converts with the line's own separator`() {
        assertEquals(
            listOf("240 g flour", "2,5 kg potatoes"),
            IngredientRendering.render(listOf("1 cup flour", "1,25 kg potatoes"), 2.0, UnitSystem.METRIC, false)
        )
        assertEquals(listOf("1 cup flour"), IngredientRendering.render(listOf("1 cup flour"), 1.0, UnitSystem.AS_WRITTEN, false))
    }
}
