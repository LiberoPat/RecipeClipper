package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A count's bracket (#88's leftovers) with the model's answer (#104): the number logic stays in code. */
class CountBracketDecisionTest {

    private val de = LanguageWords.forTag("de")!!
    private val fr = LanguageWords.forTag("fr")!!
    private val apples = "3 large apples, peeled and sliced (about 3 cups)"

    @Test fun `unsure keeps the line as written, as before`() {
        assertEquals("4 Apfel (ca. 800g)", IngredientScaler.scale("4 Apfel (ca. 800g)", 2.0, de, null))
        assertEquals(apples, IngredientScaler.scale(apples, 2.0))
    }

    @Test fun `total scales the bracket with the count`() {
        assertEquals("8 Apfel (ca. 1600g)", IngredientScaler.scale("4 Apfel (ca. 800g)", 2.0, de, CountBracket.TOTAL))
        assertEquals(
            "6 large apples, peeled and sliced (about 6 cups)",
            IngredientScaler.scale(apples, 2.0, LanguageWords.ENGLISH, CountBracket.TOTAL)
        )
        assertEquals("2 patate douce (600-800 g)", IngredientScaler.scale("1 patate douce (300-400 g)", 2.0, fr, CountBracket.TOTAL))
    }

    @Test fun `each scales the count and leaves each item's size`() {
        assertEquals("2 patate douce (300-400 g)", IngredientScaler.scale("1 patate douce (300-400 g)", 2.0, fr, CountBracket.EACH))
        assertEquals("8 Apfel (ca. 800g)", IngredientScaler.scale("4 Apfel (ca. 800g)", 2.0, de, CountBracket.EACH))
    }

    @Test fun `a decision never changes a line whose bracket is not a count's`() {
        for (line in listOf("1 ⅔ cups bread flour (8 ½ ounces)", "1 can (14 oz) tomatoes", "2 cloves garlic (minced)")) {
            val asToday = IngredientScaler.scale(line, 2.0)
            assertEquals(asToday, IngredientScaler.scale(line, 2.0, LanguageWords.ENGLISH, CountBracket.TOTAL))
            assertEquals(asToday, IngredientScaler.scale(line, 2.0, LanguageWords.ENGLISH, CountBracket.EACH))
            assertFalse(line, IngredientScaler.needsCountDecision(line, LanguageWords.ENGLISH))
        }
    }

    @Test fun `only a count's bracket holding nothing but an amount is asked about`() {
        assertTrue(IngredientScaler.needsCountDecision("4 Apfel (ca. 800g)", de))
        assertTrue(IngredientScaler.needsCountDecision("1 patate douce (300-400 g)", fr))
        assertTrue(IngredientScaler.needsCountDecision(apples, LanguageWords.ENGLISH))
        // Prose in the bracket, or an alternative without a unit: nothing to decide.
        assertFalse(IngredientScaler.needsCountDecision("1 large onion ((or 2 small onions), sliced)", LanguageWords.ENGLISH))
        assertFalse(IngredientScaler.needsCountDecision("2 eggs", LanguageWords.ENGLISH))
        assertFalse(IngredientScaler.needsCountDecision("4 Apfel (ca. 800g)", null))
    }

    @Test fun `rendering applies a cached decision for the line's language only`() {
        val line = "4 Apfel (ca. 800g)"
        val decided = Decisions(mapOf(DecisionQuestion.countBracket(line, "de") to "total"))
        assertEquals(listOf("8 Apfel (ca. 1600g)"), IngredientRendering.render(listOf(line), 2.0, UnitSystem.AS_WRITTEN, false, de, decided))
        assertEquals(listOf(line), IngredientRendering.render(listOf(line), 2.0, UnitSystem.AS_WRITTEN, false, de, Decisions.NONE))
        val unsure = Decisions(mapOf(DecisionQuestion.countBracket(line, "de") to "unsure"))
        assertEquals(listOf(line), IngredientRendering.render(listOf(line), 2.0, UnitSystem.AS_WRITTEN, false, de, unsure))
        val otherLanguage = Decisions(mapOf(DecisionQuestion.countBracket(line, "fr") to "total"))
        assertEquals(listOf(line), IngredientRendering.render(listOf(line), 2.0, UnitSystem.AS_WRITTEN, false, de, otherLanguage))
    }
}
