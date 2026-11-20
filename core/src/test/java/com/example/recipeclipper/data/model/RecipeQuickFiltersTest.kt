package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The quick filters of Recipes (#378): the minutes a stored time says, and what the pantry covers. */
class RecipeQuickFiltersTest {

    private fun recipe(
        id: Long = 1,
        total: String? = null,
        prep: String? = null,
        cook: String? = null,
        cooked: Long? = null,
        language: String? = "en"
    ) = RecipeSummary(
        id, "Recipe $id", null, total, 0, isSaved = false, lastCookedDay = cooked,
        prepTime = prep, cookTime = cook, language = language
    )

    private fun item(name: String, inStock: Boolean = true, alwaysHave: Boolean = false, language: String = "en") =
        PantryItem(name.hashCode().toLong(), name, null, language, Aisle.OTHER, inStock, alwaysHave, null, null)

    private fun saved(id: Long, vararg lines: String, language: String = "en") = SavedRecipe(id, "r$id", language, lines.toList())

    // --- Durations.minutes ---

    @Test fun `minutes reads what format writes back, in each language's symbols`() {
        assertEquals(10, Durations.minutes("10m"))
        assertEquals(90, Durations.minutes("1h 30m"))
        assertEquals(120, Durations.minutes("2h"))
        assertEquals(90, Durations.minutes("1h 30min", LanguageWords.forTag("de")))
        assertEquals(90, Durations.minutes("1時間 30分", LanguageWords.forTag("ja")))
        assertEquals(45, Durations.minutes("45min", LanguageWords.forTag("fr")))
    }

    @Test fun `minutes reads ISO and a phrase in the recipe's words`() {
        assertEquals(90, Durations.minutes("PT1H30M"))
        assertEquals(1500, Durations.minutes("P1DT1H"))
        assertEquals(2, Durations.minutes("PT90S")) // rounded as format rounds it
        assertEquals(90, Durations.minutes("1 hour 30 minutes"))
        assertEquals(135, Durations.minutes("2 horas y 15 minutos", LanguageWords.forTag("es")))
    }

    @Test fun `minutes never guesses`() {
        assertNull(Durations.minutes("Overnight"))
        assertNull(Durations.minutes("20 to 25 minutes"))
        assertNull(Durations.minutes("1-2 hours"))
        assertNull(Durations.minutes("PT0S")) // zero is absent, as format hides it
        assertNull(Durations.minutes("0m"))
        assertNull(Durations.minutes("  "))
        assertNull(Durations.minutes("99999999999999999999 minutes"))
        assertNull(Durations.minutes("1 hour 30 minutes", LanguageWords.forTag("de"))) // another language's words
    }

    @Test fun `a language with no tables reads ISO and English's symbols alone`() {
        assertEquals(20, Durations.minutes("PT20M", null))
        assertEquals(80, Durations.minutes("1h 20m", null))
        assertNull(Durations.minutes("20 minutes", null))
    }

    // --- 30 min or less ---

    @Test fun `30 min or less reads the total, and 30 itself counts`() {
        assertTrue(RecipeQuickFilters.thirtyOrLess(recipe(total = "25m")))
        assertTrue(RecipeQuickFilters.thirtyOrLess(recipe(total = "29m")))
        assertTrue(RecipeQuickFilters.thirtyOrLess(recipe(total = "30m")))
        assertFalse(RecipeQuickFilters.thirtyOrLess(recipe(total = "31m")))
        assertFalse(RecipeQuickFilters.thirtyOrLess(recipe(total = "1h")))
    }

    @Test fun `without a total it adds prep and cook, only when both read`() {
        assertTrue(RecipeQuickFilters.thirtyOrLess(recipe(prep = "10m", cook = "15m")))
        assertTrue(RecipeQuickFilters.thirtyOrLess(recipe(prep = "10m", cook = "20m")))
        assertFalse(RecipeQuickFilters.thirtyOrLess(recipe(prep = "10m", cook = "21m")))
        assertFalse(RecipeQuickFilters.thirtyOrLess(recipe(prep = "10m")))
        assertFalse(RecipeQuickFilters.thirtyOrLess(recipe(cook = "10m")))
        assertFalse(RecipeQuickFilters.thirtyOrLess(recipe(prep = "5m", cook = "Overnight")))
        assertEquals(25, RecipeQuickFilters.minutes(recipe(prep = "10m", cook = "15m")))
    }

    @Test fun `a total that is present but unreadable never matches, and never falls back`() {
        assertFalse(RecipeQuickFilters.thirtyOrLess(recipe(total = "Overnight", prep = "5m", cook = "5m")))
        assertFalse(RecipeQuickFilters.thirtyOrLess(recipe(total = "20 to 25 minutes")))
        assertNull(RecipeQuickFilters.minutes(recipe()))
    }

    @Test fun `a time is read in the recipe's own language`() {
        assertTrue(RecipeQuickFilters.thirtyOrLess(recipe(total = "25min", language = "de")))
        assertTrue(RecipeQuickFilters.thirtyOrLess(recipe(total = "25m", language = null)))
        assertTrue(RecipeQuickFilters.thirtyOrLess(recipe(total = "25m", language = "xx")))
        assertFalse(RecipeQuickFilters.thirtyOrLess(recipe(total = "25 minutes", language = "xx")))
    }

    // --- Not cooked yet ---

    @Test fun `not cooked is the Cooked view turned round`() {
        assertTrue(RecipeQuickFilters.notCooked(recipe(cooked = null)))
        assertFalse(RecipeQuickFilters.notCooked(recipe(cooked = 20_000)))
    }

    // --- Uses what's in my pantry ---

    @Test fun `coverage counts the lines the pantry has, a heading and an unnameable line aside`() {
        val pantry = listOf(item("flour"), item("eggs"), item("salt", alwaysHave = true), item("butter", inStock = false))
        val coverage = RecipeQuickFilters.coverage(
            saved(1, "For the dough:", "2 cups flour", "3 large eggs", "1 tsp salt", "2 tbsp butter"), pantry
        )!!
        assertEquals(PantryCoverage(have = 3, total = 4), coverage) // a staple counts as had; butter is out
        assertTrue(coverage.matches)
    }

    @Test fun `half is enough, less is not, and a recipe with nothing nameable never matches`() {
        val pantry = listOf(item("flour"), item("eggs"))
        assertTrue(RecipeQuickFilters.coverage(saved(1, "2 cups flour", "1 cup milk"), pantry)!!.matches)
        assertFalse(RecipeQuickFilters.coverage(saved(1, "2 cups flour", "1 cup milk", "1 cup sugar"), pantry)!!.matches)
        assertFalse(RecipeQuickFilters.coverage(saved(1, "For the dough:"), pantry)!!.matches)
        assertFalse(PantryCoverage(0, 0).matches)
    }

    @Test fun `presence only, and a compound is never its head noun`() {
        val flour = listOf(item("flour"))
        assertEquals(0, RecipeQuickFilters.coverage(saved(1, "2 cups rice flour"), flour)!!.have)
        assertEquals(1, RecipeQuickFilters.coverage(saved(1, "2 cups all-purpose flour"), flour)!!.have)
        assertEquals(0, RecipeQuickFilters.coverage(saved(1, "1 can butter beans"), listOf(item("butter")))!!.have)
        // Presence only: a pantry holding a pinch of flour covers a recipe wanting a kilo.
        assertEquals(1, RecipeQuickFilters.coverage(saved(1, "5 kg flour"), flour)!!.have)
    }

    @Test fun `only the same language matches, and a language with no tables has no coverage`() {
        assertEquals(0, RecipeQuickFilters.coverage(saved(1, "200 g Butter", language = "de"), listOf(item("butter")))!!.have)
        assertEquals(1, RecipeQuickFilters.coverage(saved(1, "200 g Butter", language = "de"), listOf(item("Butter", language = "de")))!!.have)
        assertNull(RecipeQuickFilters.coverage(saved(1, "200 g butter", language = "xx"), listOf(item("butter"))))
    }

    // --- Combined ---

    @Test fun `the chips combine, and keep the order they were given`() {
        val quickAndNew = recipe(1, total = "20m")
        val quickAndCooked = recipe(2, total = "20m", cooked = 20_000)
        val slowAndNew = recipe(3, total = "2h")
        val unreadable = recipe(4, total = "Overnight")
        val all = listOf(quickAndNew, quickAndCooked, slowAndNew, unreadable)
        val covers = mapOf(1L to PantryCoverage(2, 3), 2L to PantryCoverage(2, 3), 3L to PantryCoverage(3, 3), 4L to PantryCoverage(0, 3))

        assertEquals(all, RecipeQuickFilters.apply(all, emptySet()))
        assertEquals(listOf(quickAndNew, quickAndCooked), RecipeQuickFilters.apply(all, setOf(RecipeQuickFilter.THIRTY_OR_LESS)))
        assertEquals(listOf(quickAndNew, slowAndNew, unreadable), RecipeQuickFilters.apply(all, setOf(RecipeQuickFilter.NOT_COOKED)))
        assertEquals(listOf(quickAndNew), RecipeQuickFilters.apply(all, setOf(RecipeQuickFilter.THIRTY_OR_LESS, RecipeQuickFilter.NOT_COOKED)))
        assertEquals(
            listOf(quickAndNew, slowAndNew),
            RecipeQuickFilters.apply(all, setOf(RecipeQuickFilter.NOT_COOKED, RecipeQuickFilter.USES_PANTRY), covers)
        )
        assertEquals(
            listOf(quickAndNew),
            RecipeQuickFilters.apply(all, RecipeQuickFilter.entries.toSet(), covers)
        )
        // A recipe the pantry was never matched against (no tables) never matches the pantry chip.
        assertEquals(emptyList<RecipeSummary>(), RecipeQuickFilters.apply(all, setOf(RecipeQuickFilter.USES_PANTRY)))
    }
}
