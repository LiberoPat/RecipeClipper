package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The recipe's language picks its words (#14); a language with none leaves every line as written. */
class LanguageWordsTest {

    @Test
    fun `tags are normalised and anything else is no tag`() {
        assertEquals("en-us", LanguageWords.normalize(" en-US "))
        assertEquals("pt-br", LanguageWords.normalize("pt_BR"))
        assertEquals("de", LanguageWords.normalize("de"))
        assertNull(LanguageWords.normalize("English"))
        assertNull(LanguageWords.normalize(""))
        assertNull(LanguageWords.normalize(null))
    }

    @Test
    fun `words are found by the primary subtag, and only for a shipped language`() {
        assertSame(LanguageWords.ENGLISH, LanguageWords.forTag("en"))
        assertSame(LanguageWords.ENGLISH, LanguageWords.forTag("en-GB"))
        assertNull(LanguageWords.forTag("de-de"))
        assertNull(LanguageWords.forTag(null))
    }

    @Test
    fun `inLanguage wins, then the page's lang, then the recipe's words, then English`() {
        val english = "2 cups chopped fresh basil\n1 tablespoon olive oil\nsalt to taste"
        assertEquals("de-de", LanguageWords.resolve("de-DE", "en") { english })
        assertEquals("fr", LanguageWords.resolve(null, "fr") { english })
        assertEquals("fr", LanguageWords.resolve("English", "fr") { english })
        assertEquals("en", LanguageWords.resolve(null, null) { english })
        assertEquals("en", LanguageWords.resolve(null, null) { "Mehl\nZucker" })
    }

    @Test
    fun `detection needs a clear lead`() {
        assertEquals("en", LanguageWords.detect("1 cup sugar\n2 cups flour\n1 large egg, divided"))
        assertNull(LanguageWords.detect("500 g Mehl\n200 g Zucker"))
        assertNull(LanguageWords.detect("Pasta with pesto"))
    }

    @Test
    fun `a stored recipe with no language is detected from its words`() {
        val recipe = Recipe(
            name = "Pancakes", image = null, ingredients = listOf("1 cup flour"), instructions = emptyList(),
            prepTime = null, cookTime = null, totalTime = null, yield = "4", sourceUrl = "https://a.com"
        )
        assertSame(LanguageWords.ENGLISH, LanguageWords.forRecipe(recipe))
        assertNull(LanguageWords.forRecipe(recipe.copy(language = "it-it")))
    }

    @Test
    fun `with no words every line stays as written`() {
        // English rules would read "2 bis 3" as a range's start and scale it to "4 bis 3".
        assertEquals("2 bis 3 Eier", IngredientScaler.scale("2 bis 3 Eier", 2.0, null))
        assertEquals("2 cups flour", UnitConverter.convert("2 cups flour", UnitSystem.METRIC, true, words = null))
        assertEquals("Bake at 350°F", TemperatureConverter.convert("Bake at 350°F", TemperatureUnit.CELSIUS, null))
        assertNull(StepTimers.parse("Bake for 20 minutes", null))
        assertNull(Servings.parse("4 servings", null))
        assertEquals(YieldKind.SERVES, Servings.kind("Makes 12", null))
        assertEquals("4-6", Servings.pickYield(listOf("4", "4-6"), null))
    }

    @Test
    fun `English is the default and reads as before`() {
        val en = LanguageWords.ENGLISH
        assertEquals("4 to 6 eggs", IngredientScaler.scale("2 to 3 eggs", 2.0, en))
        assertEquals("1 cup plus 2 tbsp flour", IngredientScaler.scale("1/2 cup plus 1 tbsp flour", 2.0, en))
        assertEquals(90 * 60, StepTimers.parse("Bake 1 hour and 30 minutes", en))
        assertEquals("1 hr 30 min", StepTimers.label(90 * 60, en))
        assertEquals("45 sec", StepTimers.label(45))
    }
}
