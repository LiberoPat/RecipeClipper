package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tour's sample recipe (#151), from `shared/sample/recipe.json` (iOS: SampleRecipeTests). */
class SampleRecipeTest {

    @Test
    fun `it is written in every UI language, and English stands in for the rest`() {
        assertEquals(setOf("en", "es", "fr", "de", "it", "pt"), SampleRecipe.languages.toSet())
        assertEquals("pt", SampleRecipe.forLanguage("pt-BR").language)
        assertEquals("de", SampleRecipe.forLanguage("de").language)
        assertEquals("en", SampleRecipe.forLanguage("ja").language)
        assertEquals("en", SampleRecipe.forLanguage(null).language)
    }

    @Test
    fun `it is saved like a typed-in recipe, under its own link`() {
        val sample = SampleRecipe.forLanguage("en")
        assertEquals("manual:sample", sample.sourceUrl)
        assertTrue(SampleRecipe.isSample(sample.sourceUrl))
        assertTrue(ManualRecipe.isManual(sample.sourceUrl))
        assertEquals(ContentOrigin.MANUAL, sample.origin)
        assertFalse("never fetched: no Update from source", sample.canUpdateFromSource)
        assertNull(sample.image)
        assertFalse(SampleRecipe.isSample(ManualRecipe.newSourceUrl("0f8fad5b-d9cb-469f-a165-70867728950e")))
    }

    /** What the tour offers it for: scaling, units and cook mode's timers, in each language. */
    @Test
    fun `every language's sample serves 4, scales, and has a timer in its first step`() {
        for (language in SampleRecipe.languages) {
            val sample = SampleRecipe.forLanguage(language)
            val words = LanguageWords.forTag(language)
            assertNotNull("$language has tables", words)
            assertTrue(language, sample.name.isNotBlank())
            assertEquals(language, 10, sample.ingredients.size)
            assertEquals(language, 6, sample.instructions.size)
            assertEquals(language, 4, Servings.parse(sample.yield, words))
            assertEquals(language, 8 * 60, StepTimers.parse(sample.instructions[0], words))
            val doubled = IngredientRendering.render(sample.ingredients, 2.0, UnitSystem.AS_WRITTEN, false, words)
            assertNotEquals("$language: the first line scales", sample.ingredients[0], doubled[0])
        }
    }

    @Test
    fun `the English sample converts to metric`() {
        val sample = SampleRecipe.forLanguage("en")
        val metric = IngredientRendering.render(sample.ingredients, 1.0, UnitSystem.METRIC, false)
        assertEquals("30 ml olive oil", metric[0])
        assertEquals("115 g baby spinach", metric[7])
    }
}
