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
        assertEquals("de", LanguageWords.forTag("de-DE")?.language)
        assertNull(LanguageWords.forTag("nl-nl"))
        assertNull(LanguageWords.forTag(null))
    }

    private val english = "2 cups chopped fresh basil\n1 tablespoon olive oil\nsalt to taste"
    private val german = "Rührkuchen\n500 g Mehl\n200 g Zucker\n3 Eier\n1 Prise Salz\n2 EL Öl"
    private val ambiguous = "Kuchen\n200 g Mehl\n1 cup sugar"

    @Test
    fun `inLanguage wins, then the page's lang, then the recipe's words, then English`() {
        assertEquals("de-de", LanguageWords.resolve("de-DE", "en") { ambiguous })
        assertEquals("fr", LanguageWords.resolve(null, "fr") { "Gâteau" })
        assertEquals("fr", LanguageWords.resolve("English", "fr") { "Gâteau" })
        assertEquals("en-gb", LanguageWords.resolve("en-GB", null) { english })
        assertEquals("en", LanguageWords.resolve(null, null) { english })
        assertEquals("de", LanguageWords.resolve(null, null) { german })
        assertEquals("en", LanguageWords.resolve(null, null) { "Mehl\nZucker" })
    }

    @Test
    fun `words that clearly say another language beat a declared one, ambiguous words don't`() {
        assertEquals("de", LanguageWords.resolve(null, "en") { german })
        assertEquals("de", LanguageWords.resolve("en-US", "en") { german })
        assertEquals("en", LanguageWords.resolve("de", null) { english })
        assertEquals("en", LanguageWords.resolve(null, "en") { ambiguous })
        assertEquals("en-us", LanguageWords.resolve("en-US", null) { ambiguous })
    }

    @Test
    fun `detection needs a clear lead`() {
        assertEquals("en", LanguageWords.detect("1 cup sugar\n2 cups flour\n1 large egg, divided"))
        assertEquals("de", LanguageWords.detect(german))
        assertEquals("es", LanguageWords.detect("2 tazas de harina\n1 cucharada de azúcar\n3 huevos\nsal al gusto"))
        assertEquals("fr", LanguageWords.detect("250 g de farine\n2 cuillères à soupe de sucre\n3 œufs\nsel et poivre"))
        assertEquals("it", LanguageWords.detect("300 g di farina\n2 cucchiai di zucchero\n3 uova\nsale q.b."))
        assertEquals("pt", LanguageWords.detect("2 xícaras de farinha\n1 colher de açúcar\n3 ovos\nsal a gosto"))
        assertNull(LanguageWords.detect("500 g Mehl\n200 g Zucker"))
        assertNull(LanguageWords.detect(ambiguous))
        assertNull(LanguageWords.detect("Pasta with pesto"))
    }

    @Test
    fun `a German page that declares English is read with German words`() {
        assertEquals("de", LanguageWords.forTag(LanguageWords.resolve(null, "en") { german })?.language)
    }

    @Test
    fun `a stored recipe with no language is detected from its words`() {
        val recipe = Recipe(
            name = "Pancakes", image = null, ingredients = listOf("1 cup flour"), instructions = emptyList(),
            prepTime = null, cookTime = null, totalTime = null, yield = "4", sourceUrl = "https://a.com"
        )
        assertSame(LanguageWords.ENGLISH, LanguageWords.forRecipe(recipe))
        assertEquals("it", LanguageWords.forRecipe(recipe.copy(language = "it-it"))?.language)
        assertNull(LanguageWords.forRecipe(recipe.copy(language = "nl-nl")))
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
        assertNull(IngredientName.of("2 large eggs, beaten", null))
        assertEquals(listOf("2 cups flour"), IngredientRendering.render(listOf("2 cups flour"), 2.0, UnitSystem.METRIC, false, null))
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
