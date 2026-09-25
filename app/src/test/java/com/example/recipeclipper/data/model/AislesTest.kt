package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The aisle table (#50) matches the end of the ingredient's name, like the density table. */
class AislesTest {

    private fun aisle(line: String, language: String = "en") = Aisles.of(line, LanguageWords.forTag(language))

    @Test fun matchesTheEndOfTheName() {
        assertEquals(Aisle.DAIRY, aisle("4 tbsp unsalted butter, melted"))
        assertEquals(Aisle.CONDIMENTS, aisle("2 tbsp peanut butter"))
        assertEquals(Aisle.CANNED, aisle("1 can butter beans, drained"))
        assertEquals(Aisle.PRODUCE, aisle("2 large onions, chopped"))
        assertEquals(Aisle.BAKING, aisle("2 cups all-purpose flour"))
        assertEquals(Aisle.SPICES, aisle("1 tsp ground black pepper"))
        assertEquals(Aisle.PRODUCE, aisle("1 red bell pepper"))
    }

    @Test fun aLongerAliasWins() {
        assertEquals(Aisle.FROZEN, aisle("1 pint ice cream"))
        assertEquals(Aisle.DAIRY, aisle("1 cup heavy cream"))
        assertEquals(Aisle.CANNED, aisle("1 can coconut milk"))
    }

    @Test fun anythingUnknownIsOther() {
        assertEquals(Aisle.OTHER, aisle("paper towels"))
        assertEquals(Aisle.OTHER, aisle("salt and pepper"))
        assertEquals(Aisle.OTHER, aisle("For the sauce:"))
        assertEquals(Aisle.OTHER, Aisles.of("2 onions", null))
    }

    @Test fun eachLanguageUsesItsOwnTable() {
        assertEquals(Aisle.BAKING, aisle("500 g Mehl", "de"))
        assertEquals(Aisle.PRODUCE, aisle("2 cebollas", "es"))
        assertEquals(Aisle.DAIRY, aisle("200 g de beurre", "fr"))
        assertEquals(Aisle.DAIRY, aisle("2 uova", "it"))
        assertEquals(Aisle.GRAINS, aisle("1 xícara de arroz", "pt"))
        assertEquals(Aisle.CONDIMENTS, aisle("醤油 大さじ1", "ja"))
    }

    @Test fun anUnknownStoredKeyIsOther() {
        assertEquals(Aisle.OTHER, Aisle.fromKey("haberdashery"))
        assertEquals(Aisle.DAIRY, Aisle.fromKey("dairy"))
    }
}
