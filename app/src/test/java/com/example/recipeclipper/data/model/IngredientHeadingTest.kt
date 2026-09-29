package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Group headings among the ingredients, in every language: the colon form the parsers write. */
class IngredientHeadingTest {

    @Test fun `a line ending in a colon is a heading in any language`() {
        listOf(
            "For the sauce:", "Für die Füllung:", "Pour la garniture :", "Para la salsa:",
            "Per la crema:", "Para a cobertura:", "  Batter:  ", "Minted Yoghurt (optional):"
        ).forEach { assertTrue(it, IngredientHeading.isHeading(it)) }
    }

    @Test fun `an ingredient line is not a heading`() {
        listOf("250 g Mehl", "2 cups flour", "Salz", "salt: to taste", "", "Note: use cold butter.")
            .forEach { assertFalse(it, IngredientHeading.isHeading(it)) }
    }

    @Test fun `groceries use the same test`() {
        assertFalse(GrocerySources.buyable("Für die Füllung:"))
        assertTrue(GrocerySources.buyable("200 g Quark"))
    }

    @Test fun `a heading is never scaled, converted or cut`() {
        val de = LanguageWords.forTag("de")!!
        // "2 Portionen" would scale if it were an ingredient.
        val lines = listOf("Für 2 Portionen Soße:", "250 g Mehl", "1/2 TL Salz")
        assertEquals(
            listOf("Für 2 Portionen Soße:", "313 g Mehl", "3 ml Salz"),
            IngredientRendering.render(lines, 1.25, UnitSystem.METRIC, false, de)
        )
        assertEquals(
            listOf("2 Portionen Soße:", "313 g Mehl"),
            IngredientRendering.render(listOf("2 Portionen Soße:", "250 g Mehl"), 1.25, UnitSystem.AS_WRITTEN, false, de)
        )
    }
}
