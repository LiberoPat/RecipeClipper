package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which questions are asked (#104), and what a "same" or an aisle changes. */
class DecisionCandidatesTest {

    private val en = LanguageWords.ENGLISH

    private fun item(name: String, inStock: Boolean = true, language: String = "en") =
        PantryItem(name.length.toLong(), name, null, language, Aisle.OTHER, inStock = inStock, alwaysHave = false, purchasedDay = null, expiresDay = null)

    @Test fun `close names share a head noun but don't match`() {
        assertTrue(DecisionCandidates.close("rice flour", "flour", en))
        assertTrue(DecisionCandidates.close("whole milk", "milk", en))
        assertTrue(DecisionCandidates.close("bread flour", "rye flour", en))
        assertTrue(DecisionCandidates.close("Weizenmehl", "Mehl", LanguageWords.forTag("de")!!))
        assertFalse(DecisionCandidates.close("unsalted butter", "butter", en)) // already a match
        assertFalse(DecisionCandidates.close("carrots", "onions", en))
    }

    @Test fun `only a definite same turns Buy into Have`() {
        val pantry = listOf(item("flour"), item("milk"), item("caster sugar"))
        val sameFlour = Decisions(mapOf(DecisionQuestion.sameIngredient("superfine sugar", "caster sugar", "en") to "same"))
        assertFalse(PantryMatch.covered("1 cup superfine sugar", "en", pantry))
        assertTrue(PantryMatch.covered("1 cup superfine sugar", "en", pantry, sameFlour))
        // The owner's decisions: "rice flour" and "whole milk" are different; unsure changes nothing.
        val ownerPairs = Decisions(
            mapOf(
                DecisionQuestion.sameIngredient("rice flour", "flour", "en") to "different",
                DecisionQuestion.sameIngredient("whole milk", "milk", "en") to "unsure"
            )
        )
        assertFalse(PantryMatch.covered("1 cup rice flour", "en", pantry, ownerPairs))
        assertFalse(PantryMatch.covered("1 cup whole milk", "en", pantry, ownerPairs))
    }

    @Test fun `a same never makes Have from an item that is out, nor overrides a real match`() {
        val out = listOf(item("caster sugar", inStock = false))
        val same = Decisions(mapOf(DecisionQuestion.sameIngredient("superfine sugar", "caster sugar", "en") to "same"))
        assertFalse(PantryMatch.covered("1 cup superfine sugar", "en", out, same))
        assertNull(PantryMatch.find("superfine sugar", "en", out, same))
        val different = Decisions(mapOf(DecisionQuestion.sameIngredient("unsalted butter", "butter", "en") to "different"))
        assertTrue(PantryMatch.covered("1 cup unsalted butter", "en", listOf(item("butter")), different))
    }

    @Test fun `pairs are asked only for unmatched names and items that would make Have`() {
        val pantry = listOf(item("flour"), item("milk", inStock = false), item("butter"))
        val asked = DecisionCandidates.samePairs(listOf("rice flour", "whole milk", "unsalted butter"), "en", pantry)
        assertEquals(listOf(DecisionQuestion.sameIngredient("rice flour", "flour", "en")), asked)
        assertTrue(DecisionCandidates.samePairs(listOf("rice flour"), "ja", pantry).isEmpty())
    }

    @Test fun `aisle questions only for named lines the table puts in Other`() {
        assertNull(DecisionCandidates.aisle("2 cups whole milk", "en"))
        assertNull(DecisionCandidates.aisle("Salt and pepper", "en"))
        val question = DecisionCandidates.aisle("2 tbsp furikake", "en")
        assertEquals(DecisionQuestion.aisle("furikake", "en"), question)
        val decided = Decisions(mapOf(question!! to "spices"))
        assertEquals(Aisle.SPICES, decided.aisle("Furikake", "en"))
        assertNull(Decisions(mapOf(question to "other")).aisle("furikake", "en"))
        assertNull(Decisions(mapOf(question to "unsure")).aisle("furikake", "en"))
    }
}
