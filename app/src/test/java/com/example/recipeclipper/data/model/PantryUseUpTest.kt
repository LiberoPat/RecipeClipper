package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Using up the pantry at the end of cooking (#147): what subtracts exactly, and every refusal
 * that asks instead. The iOS `PantryUseUpTests` mirror these.
 */
class PantryUseUpTest {

    private var nextId = 1L

    private fun item(
        name: String,
        quantity: String?,
        inStock: Boolean = true,
        alwaysHave: Boolean = false,
        language: String? = "en"
    ) = PantryItem(nextId++, name, quantity, language, Aisle.OTHER, inStock, alwaysHave, null, null)

    /** The one row [lines] give against [pantry]'s items, in English unless told otherwise. */
    private fun change(pantry: PantryItem, vararg lines: String, language: String = "en"): UseUpChange {
        val rows = PantryUseUp.rows(lines.toList(), language, listOf(pantry))
        assertEquals("one row for ${pantry.name}", 1, rows.size)
        return rows.single().change
    }

    private fun after(pantry: PantryItem, vararg lines: String, language: String = "en"): String? {
        val change = change(pantry, *lines, language = language)
        assertTrue("worked out: $change", change is UseUpChange.Subtract)
        return (change as UseUpChange.Subtract).after
    }

    // --- Worked out

    @Test fun `a weight from the same unit, the owner's chicken`() {
        val chicken = item("chicken", "2 lb")
        assertEquals(UseUpChange.Subtract("2 lb", "1 lb"), change(chicken, "1 lb chicken"))
    }

    @Test fun `a count, sizes between the number and the name included, the owner's eggs`() {
        assertEquals("4", after(item("eggs", "6"), "2 large eggs, beaten"))
        assertEquals("4 eggs", after(item("Eggs", "6 eggs"), "2 eggs"))
    }

    @Test fun `within one family the result is exact, in the pantry's unit when it can be`() {
        assertEquals("1 1/2 lb", after(item("chicken", "2 lb"), "8 oz chicken"))
        assertEquals("29 oz", after(item("chicken", "2 lb"), "3 oz chicken"))
        assertEquals("2 1/2 cups", after(item("milk", "4 cups"), "1 1/2 cups milk"))
    }

    @Test fun `metric falls back to the smaller unit rather than round`() {
        assertEquals("880 g", after(item("flour", "1 kg"), "120 g flour"))
        assertEquals("500 g", after(item("flour", "1 kg"), "500 g flour"))
        assertEquals("650 ml", after(item("milk", "1 l"), "250 ml milk", "100 ml milk"))
    }

    @Test fun `the rest of the quantity stays as written, and a decimal comma stays a comma`() {
        assertEquals("1 lb pack", after(item("chicken", "2 lb pack"), "1 lb chicken"))
        assertEquals("1,5 kg", after(item("flour", "2,5 kg"), "1 kg flour"))
    }

    @Test fun `volume to weight goes through the density table`() {
        // Flour is 120 g a cup.
        assertEquals("760 g", after(item("flour", "1 kg"), "2 cups all-purpose flour"))
    }

    @Test fun `the line's own second measure wins over the table`() {
        assertEquals("875 g", after(item("flour", "1 kg"), "1 cup (125 g) flour"))
        assertEquals("875 g", after(item("flour", "1 kg"), "1 cup/125 g flour"))
    }

    @Test fun `across weight families it rounds as the converter does`() {
        // 2 lb less 454 g is 453 g: shown in the pantry's pounds, as Ounces would show it.
        assertEquals("1 lb", after(item("chicken", "2 lb"), "454 g chicken"))
        // 1 kg less 1 lb is 546 g, to the converter's 5 g.
        assertEquals("545 g", after(item("chicken", "1 kg"), "1 lb chicken"))
    }

    @Test fun `a cup from millilitres is Metric's 240 ml, as the recipe's Metric view shows it`() {
        assertEquals("760 ml", after(item("milk", "1 l"), "1 cup milk"))
    }

    @Test fun `the lines are the recipe's as shown, so scaled amounts subtract as scaled`() {
        assertEquals("4 1/2", after(item("eggs", "6"), "1 1/2 eggs"))
    }

    @Test fun `at zero or below the item is used up`() {
        assertEquals(UseUpChange.Subtract("2", null), change(item("eggs", "2"), "3 eggs"))
        assertEquals(UseUpChange.Subtract("1 lb", null), change(item("chicken", "1 lb"), "16 oz chicken"))
    }

    @Test fun `several lines using one item are one row, added up`() {
        val flour = item("flour", "1 kg")
        val rows = PantryUseUp.rows(listOf("200 g flour", "2 tbsp butter", "100 g flour"), "en", listOf(flour))
        assertEquals(1, rows.size)
        assertEquals(listOf("200 g flour", "100 g flour"), rows.single().lines)
        assertEquals(UseUpChange.Subtract("1 kg", "700 g"), rows.single().change)
    }

    @Test fun `other languages read with their own words`() {
        assertEquals("4", after(item("Eier", "6", language = "de"), "2 große Eier", language = "de"))
        assertEquals("750 g", after(item("Mehl", "1 kg", language = "de"), "250 g Mehl", language = "de"))
    }

    // --- Refusals: the row asks, never guesses

    @Test fun `no quantity, or one that isn't an amount, asks`() {
        assertEquals(UseUpChange.Ask, change(item("flour", null), "1 cup flour"))
        assertEquals(UseUpChange.Ask, change(item("flour", "half a bag"), "1 cup flour"))
        assertEquals(UseUpChange.Ask, change(item("flour", "1 bag"), "1 cup flour"))
        assertEquals(UseUpChange.Ask, change(item("flour", "about 1 kg"), "1 cup flour"))
    }

    @Test fun `a line with no amount, or more than one, asks`() {
        assertEquals(UseUpChange.Ask, change(item("flour", "1 kg"), "flour, for dusting"))
        assertEquals(UseUpChange.Ask, change(item("milk", "1 l"), "1-2 cups milk"))
        assertEquals(UseUpChange.Ask, change(item("flour", "1 kg"), "1 cup plus 2 tbsp flour"))
        assertEquals(UseUpChange.Ask, change(item("eggs", "6"), "2 eggs plus 3 yolks"))
        assertEquals(UseUpChange.Ask, change(item("eggs", "6"), "2 eggs (about 100 g)"))
    }

    @Test fun `different kinds of unit ask`() {
        // A count against a weight, and a weight against a count.
        assertEquals(UseUpChange.Ask, change(item("chicken", "2 lb"), "1 chicken"))
        assertEquals(UseUpChange.Ask, change(item("eggs", "6"), "100 g eggs"))
    }

    @Test fun `volume and weight with no density ask`() {
        // Walnuts are out of the density table on purpose.
        assertEquals(UseUpChange.Ask, change(item("walnuts", "500 g"), "1 cup walnuts"))
        assertEquals(UseUpChange.Ask, change(item("condensed milk", "400 g"), "1 cup condensed milk"))
    }

    @Test fun `a count of parts or packages asks`() {
        assertEquals(UseUpChange.Ask, change(item("garlic", "3"), "2 cloves garlic"))
        assertEquals(UseUpChange.Ask, change(item("tomatoes", "4"), "1 can tomatoes"))
    }

    @Test fun `an imperial volume that isn't exact asks rather than round`() {
        assertEquals(UseUpChange.Ask, change(item("milk", "2 cups"), "100 ml milk"))
    }

    @Test fun `a unit whose size varies asks`() {
        assertEquals(UseUpChange.Ask, change(item("farine", "1 kg", language = "fr"), "1 tasse de farine", language = "fr"))
    }

    // --- Not listed at all

    @Test fun `a different ingredient is never used up`() {
        val pantry = listOf(item("rice flour", "1 kg"), item("butter beans", "2 cans"))
        assertTrue(PantryUseUp.rows(listOf("1 cup flour", "2 tbsp butter"), "en", pantry).isEmpty())
    }

    @Test fun `staples, items already out, unnamed lines and headings are left out`() {
        val pantry = listOf(item("salt", "1 kg", alwaysHave = true), item("milk", "1 l", inStock = false), item("pepper", "50 g"))
        assertTrue(PantryUseUp.rows(listOf("1 tsp salt", "1 cup milk", "For the sauce:", "salt and pepper"), "en", pantry).isEmpty())
    }

    @Test fun `a language with no words, or a different language, lists nothing`() {
        assertTrue(PantryUseUp.rows(listOf("2 eggs"), "xx", listOf(item("eggs", "6"))).isEmpty())
        assertTrue(PantryUseUp.rows(listOf("2 eggs"), "en", listOf(item("eggs", "6", language = "de"))).isEmpty())
    }
}
