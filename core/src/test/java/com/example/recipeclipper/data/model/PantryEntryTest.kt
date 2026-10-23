package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An amount typed with a pantry item's name (#302): split only when the text starts with one
 * real amount, else kept whole. The iOS `PantryEntryTests` mirror these, and the corpus's
 * `Entry` rows pin both.
 */
class PantryEntryTest {

    private fun split(text: String, language: String = "en") =
        PantryEntry.split(text, LanguageWords.forTag(language))

    private fun assertSplit(text: String, quantity: String, name: String, language: String = "en") =
        assertEquals(text, PantryEntry(name, quantity), split(text, language))

    private fun assertWhole(text: String, language: String = "en") =
        assertEquals(text, PantryEntry(text.trim(), null), split(text, language))

    @Test fun `a count, a unit, a fraction and a mixed number split`() {
        assertSplit("3 onions", "3", "onions")
        assertSplit("  3 onions ", "3", "onions")
        assertSplit("2 cups rice", "2 cups", "rice")
        assertSplit("1½ lb chicken thighs", "1½ lb", "chicken thighs")
        assertSplit("½ cabbage", "½", "cabbage")
        assertSplit("1/2 cup sugar", "1/2 cup", "sugar")
        assertSplit("1 1/2 cups flour", "1 1/2 cups", "flour")
        assertSplit("500g butter", "500g", "butter")
        assertSplit("2 tbsp. olive oil", "2 tbsp.", "olive oil")
        assertSplit("0.5 kg rice", "0.5 kg", "rice")
    }

    @Test fun `a container belongs to the amount, so the name is the ingredient`() {
        assertSplit("2 cans tomatoes", "2 cans", "tomatoes")
        assertSplit("2 cans of tomatoes", "2 cans", "tomatoes")
        assertSplit("1 jar honey", "1 jar", "honey")
        assertSplit("1 Dose Tomaten", "1 Dose", "Tomaten", "de")
        assertSplit("1 boîte de tomates", "1 boîte", "tomates", "fr")
        assertSplit("2 latas de tomate", "2 latas", "tomate", "es")
        assertWhole("2 cans")
        assertSplit("2 canned tomatoes", "2", "canned tomatoes")
    }

    @Test fun `the name stays as typed`() {
        assertSplit("6 large Eggs", "6", "large Eggs")
        assertSplit("3 red onions, small", "3", "red onions, small")
        assertSplit("2 onions (red)", "2", "onions (red)")
    }

    @Test fun `a word joining the unit to the name belongs to neither`() {
        assertSplit("2 cups of rice", "2 cups", "rice")
        assertSplit("200 g de farine", "200 g", "farine", "fr")
        assertSplit("2 c. à soupe d'huile d'olive", "2 c. à soupe", "huile d'olive", "fr")
        assertSplit("¾ de taza de arroz", "¾ de taza", "arroz", "es")
        assertSplit("200 g di farina", "200 g", "farina", "it")
        assertSplit("2 xícaras de arroz", "2 xícaras", "arroz", "pt")
    }

    @Test fun `other languages read their own units and decimals`() {
        assertSplit("500 g Mehl", "500 g", "Mehl", "de")
        assertSplit("1,5 kg Kartoffeln", "1,5 kg", "Kartoffeln", "de")
        assertSplit("2 EL Öl", "2 EL", "Öl", "de")
        assertSplit("3 Zwiebeln", "3", "Zwiebeln", "de")
        assertSplit("3 oignons", "3", "oignons", "fr")
    }

    @Test fun `what isn't one amount stays whole`() {
        listOf(
            "00 flour", "7up", "5-spice", "five-spice powder", "1/2", "2 kg", "2-3 onions",
            "2 to 3 onions", "2 or 3 onions", "2 eggs plus 3 yolks", "1 cup plus 2 tbsp flour",
            "1 cup butter or 1/2 cup oil", "2 cups rice (400 g)", "1 kg 00 flour", "1 kg flour, 2 kg sugar",
            "2-inch piece ginger", "2% milk", "180 C water", "350°F", "350 °F oven", "1,500 g flour",
            "0 eggs", "onions", "", "   "
        ).forEach { assertWhole(it) }
        listOf("2-3 Zwiebeln", "180 C Wasser", "Zwiebeln 3").forEach { assertWhole(it, "de") }
        assertWhole("2 à 3 oignons", "fr")
    }

    @Test fun `a language with no tables, or amounts after the name, stays whole`() {
        assertEquals(PantryEntry("3 onions", null), PantryEntry.split("3 onions", null))
        assertWhole("3 玉ねぎ", "ja")
    }
}
