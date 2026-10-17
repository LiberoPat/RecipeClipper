package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.FractionLookAlike
import com.example.recipeclipper.data.model.LanguageWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iOS suite (`PhotoTextSorterTests.swift`) has the same cases and expectations. */
class PhotoTextSorterTest {

    /** A recipe card as the recogniser reads it: a title, headers, a numbered method. */
    private val card = listOf(
        "Aunt June's Oatmeal Cookies",
        "Ingredients",
        "1 cup butter",
        "1 1/2 cups flour",
        "2 cups rolled oats",
        "Directions",
        "1. Cream the butter.",
        "2. Stir in the flour and oats.",
        "3. Bake at 350°F for 12 minutes."
    )

    private fun lines(texts: List<String>, unsure: Set<String> = emptySet()) =
        texts.map { PhotoLine(it, if (it in unsure) 0.3f else 1f) }

    @Test fun `a card's lines sort into ingredients and steps like a typed post`() {
        val reading = PhotoTextSorter.sort(lines(card))

        assertTrue(reading.sorted)
        assertEquals(listOf("1 cup butter", "1 1/2 cups flour", "2 cups rolled oats"), reading.ingredients)
        assertEquals(
            listOf("Cream the butter.", "Stir in the flour and oats.", "Bake at 350°F for 12 minutes."),
            reading.instructions
        )
        assertEquals(emptyList<String>(), reading.uncertain)
    }

    @Test fun `lines the recogniser was unsure of are marked as they are shown`() {
        val reading = PhotoTextSorter.sort(lines(card, unsure = setOf("1 1/2 cups flour", "3. Bake at 350°F for 12 minutes.")))

        // The step lost its "3." to the splitter and is still marked.
        assertEquals(listOf("1 1/2 cups flour", "Bake at 350°F for 12 minutes."), reading.uncertain)
    }

    @Test fun `a misread amount stays as read, never corrected`() {
        val misread = card.map { if (it == "1 cup butter") "l cup butter" else it }
        val reading = PhotoTextSorter.sort(lines(misread, unsure = setOf("l cup butter")))

        assertEquals("l cup butter", reading.ingredients.first())
        assertEquals(listOf("l cup butter"), reading.uncertain)
    }

    @Test fun `a mixed number that lost its space is checked however sure the recogniser was`() {
        // Read from a real card by Vision, at full confidence: "1 1/2 cups flour".
        val misread = card.map { if (it == "1 1/2 cups flour") "11/2 cups flour" else it }
        val reading = PhotoTextSorter.sort(lines(misread))

        assertEquals("11/2 cups flour", reading.ingredients[1])
        assertEquals(listOf("11/2 cups flour"), reading.uncertain)
    }

    @Test fun `checked lines keep the order they are shown in`() {
        val misread = card.map {
            when (it) {
                "1 cup butter" -> "1 cupbutter"
                "3. Bake at 350°F for 12 minutes." -> "3. Bake at 35O°F for 12 minutes."
                else -> it
            }
        }
        val reading = PhotoTextSorter.sort(lines(misread, unsure = setOf("2 cups rolled oats")))

        assertEquals(
            listOf("1 cupbutter", "2 cups rolled oats", "Bake at 35O°F for 12 minutes."),
            reading.uncertain
        )
    }

    @Test fun `amounts shaped like a misreading are suspect`() {
        listOf(
            "11/2 cups flour", "13/4 cups sugar", "21/2 tsp baking soda", "31/3 cups milk",
            "Bake for 11/2 hours.", "3/2 cup water",
            "l/2 cup sugar", "I/4 tsp salt", "O.5 kg potatoes", "1/Z cup milk", "1/S tsp salt",
            "1O eggs", "Bake at 35o°F.", "l2 eggs", "|/2 cup oil",
            "1 cupraisitos", "1 cupraisins", "2 tbspsugar", "1 teaspoonvanilla"
        ).forEach { assertTrue(it, PhotoTextSorter.suspect(it)) }
    }

    @Test fun `ordinary amounts are not suspect`() {
        listOf(
            "1 1/2 cups flour", "1/2 cup sugar", "3/4 tsp salt", "2/2 cups", "1-1/2 cups oats",
            "1/2-3/4 cup milk", "11/16 inch", "1 cup raisins", "1cup flour", "2 green onions",
            "3 carrots", "12 cupcake liners", "1oz chocolate", "1l milk", "10 oz spinach",
            "2 lb chicken", "350°F", "Bake at 350°F for 12 minutes.", "Cool for 10 min.Serve.",
            "Aunt June's Oatmeal Cookies", "Stir in 2 eggs", "0.5 kg potatoes", "1,5 kg flour"
        ).forEach { assertFalse(it, PhotoTextSorter.suspect(it)) }
    }

    @Test fun `no confidence is not low confidence`() {
        val reading = PhotoTextSorter.sort(card.map { PhotoLine(it, null) })

        assertEquals(emptyList<String>(), reading.uncertain)
    }

    @Test fun `a short unsure piece marks only a line that is exactly it`() {
        val reading = PhotoTextSorter.sort(lines(card + "1", unsure = setOf("1")))

        assertFalse(reading.uncertain.contains("1 cup butter"))
    }

    @Test fun `lines that don't sort come back unsorted, every one as read`() {
        val prose = listOf("Mix the butter and sugar,", "then the flour. Bake till golden.")
        val reading = PhotoTextSorter.sort(lines(prose, unsure = setOf("then the flour. Bake till golden.")))

        assertFalse(reading.sorted)
        assertEquals(prose, reading.ingredients)
        assertEquals(emptyList<String>(), reading.instructions)
        assertEquals(listOf("then the flour. Bake till golden."), reading.uncertain)
    }

    @Test fun `a photo with no text reads as empty`() {
        val reading = PhotoTextSorter.sort(listOf(PhotoLine("  "), PhotoLine("")))

        assertFalse(reading.sorted)
        assertTrue(reading.isEmpty)
    }

    @Test fun `a gallery's pictures read as one text, in order`() {
        // The front has the ingredients, the back the method.
        val front = card.take(5)
        val back = card.drop(5)
        val reading = PhotoTextSorter.sort(lines(front) + lines(back))

        assertTrue(reading.sorted)
        assertEquals(3, reading.ingredients.size)
        assertEquals(3, reading.instructions.size)
    }
    // Fraction glyphs (#245): what ML Kit and Vision really gave for ½ ¼ ¾ ⅓ ⅔ ⅛ on the
    // card-fractions fixtures, and the percentages that must stay unflagged.

    @Test fun `a fraction glyph read as a look-alike in an amount's place is flagged`() {
        listOf(
            "2% cups oats", "2 % cups oats", "1 % cups flour", "% cup sugar", "& tsp nutmeg",
            "/2 cup sugar", "/4 tsp salt", "1 Y4 cups flour", "Ys cup mik", "Vs tsp nutmeg",
            "is tsp nutmeg", "1 a cups flour", "%"
        ).forEach { assertTrue(it, PhotoTextSorter.suspect(it)) }
    }

    @Test fun `a real percentage, or a look-alike out of an amount's place, is not flagged`() {
        listOf(
            "2% milk", "1 cup 2% milk", "70% chocolate", "2 oz 70% chocolate", "200 g 70 % chocolate",
            "a cup of milk", "1 apple", "salt & pepper", "Reduce to 50% power.", "2 % Milch",
            "1 isn't", "½ cup sugar", "1¼ cups flour", "2 ⅔ cups oats"
        ).forEach { assertFalse(it, PhotoTextSorter.suspect(it)) }
    }

    @Test fun `a fraction glyph run into a digit or another glyph is flagged`() {
        assertTrue(PhotoTextSorter.suspect("3¾4 tsp salt"))
        assertTrue(PhotoTextSorter.suspect("½⅓ cup milk"))
    }

    @Test fun `a chip replaces a glued glyph run whole, keeping a whole number set apart`() {
        assertEquals("¾ tsp salt", PhotoTextSorter.replaceFraction("3¾4 tsp salt", "¾"))
        assertEquals("⅓ cup milk", PhotoTextSorter.replaceFraction("½⅓ cup milk", "⅓"))
        assertEquals("2 ⅔ cups oats", PhotoTextSorter.replaceFraction("2 ½⅔ cups oats", "⅔"))
        assertEquals("Add ½ cup milk.", PhotoTextSorter.replaceFraction("Add ½⅓ cup milk.", "½"))
        assertEquals("⅓ cup milk", PhotoTextSorter.replaceFraction("1½⅓ cup milk", "⅓")) // glued: part of the run
        // No run: an ordinary glyph, a mixed number, a glyph after digits.
        listOf("½ cup sugar", "1¼ cups flour", "2 ⅔ cups oats", "12½ g", "1 ½ cups").forEach {
            assertNull(it, PhotoTextSorter.fractionSlot(it))
        }
    }

    @Test fun `the glued run is every digit and glyph touching it`() {
        assertEquals(0..2, FractionLookAlike.gluedRun("3¾4 tsp"))
        assertEquals(0..1, FractionLookAlike.gluedRun("½⅓ cup"))
        assertEquals(2..3, FractionLookAlike.gluedRun("2 ½⅓ cups"))
        assertEquals(0..3, FractionLookAlike.gluedRun("1½⅓4"))
        assertNull(FractionLookAlike.gluedRun("1¼ cups"))
        assertNull(FractionLookAlike.gluedRun("2 ⅔ cups"))
    }

    // Unit misspellings (#276): a near-miss of a unit where the unit goes, from splitter.json's
    // short unitLookAlikes, never edit distance.

    @Test fun `a near-miss of a unit after the amount is flagged`() {
        listOf(
            "½ oup sugar", "1 oups flour", "2 cuo milk", "1 c up milk", "1 C UPS milk", "2 tsb salt",
            "1 tps vanilla", "2 tbps butter", "1 tbsb oil", "1 isp salt", "1 ½ oup sugar", "2 tbps. butter"
        ).forEach { assertTrue(it, PhotoTextSorter.suspect(it)) }
        assertTrue(PhotoTextSorter.suspect("2 e l Zucker", LanguageWords.forTag("de")!!))
        assertTrue(PhotoTextSorter.suspect("1 oucharada de azúcar", LanguageWords.forTag("es")!!))
        assertTrue(PhotoTextSorter.suspect("2 ouillères de sucre", LanguageWords.forTag("fr")!!))
        assertTrue(PhotoTextSorter.suspect("1 oucchiaino di sale", LanguageWords.forTag("it")!!))
        assertTrue(PhotoTextSorter.suspect("2 oolheres de açúcar", LanguageWords.forTag("pt")!!))
    }

    @Test fun `real units, words and ingredient names are not flagged as misspelt units`() {
        listOf(
            "1 cup milk", "2 cups flour", "1 c milk", "1 c. sugar", "2 tsp salt", "1 tbsp oil", "2 tbs butter",
            "2 T sugar", "1 t salt", "1 oz butter", "2 g salt", "100 ml milk", "1 l water", "1 lb beef",
            "2 lbs beef", "1 kg flour", "3 teaspoons sugar", "2 tablespoons oil", "1 stick butter",
            "1 cupcake", "2 c upside-down cakes", "Bake for 1 hour.",
            "4 tsp salt", "½ cup mik", "2 tsps sugar", "1 oupa", "Add 1 oup sugar" // not after a leading amount
        ).forEach { assertFalse(it, PhotoTextSorter.suspect(it)) }
        listOf(
            "de" to "2 EL Zucker", "de" to "1 TL Salz", "de" to "1 Ei", "es" to "1 cucharada de azúcar",
            "fr" to "2 cuillères à soupe de sucre", "it" to "1 cucchiaino di sale", "pt" to "2 colheres de açúcar",
            "es" to "1 taza de harina", "ja" to "大さじ1 砂糖"
        ).forEach { (lang, line) -> assertFalse(line, PhotoTextSorter.suspect(line, LanguageWords.forTag(lang)!!)) }
    }

    @Test fun `a chip replaces the look-alike in place and nothing else`() {
        assertEquals("2⅔ cups oats", PhotoTextSorter.replaceFraction("2% cups oats", "⅔"))
        assertEquals("2 ⅔ cups oats", PhotoTextSorter.replaceFraction("2 % cups oats", "⅔"))
        assertEquals("⅛ tsp nutmeg", PhotoTextSorter.replaceFraction("& tsp nutmeg", "⅛"))
        assertEquals("¾ tsp salt", PhotoTextSorter.replaceFraction("/4 tsp salt", "¾"))
        assertEquals("1 ¼ cups flour", PhotoTextSorter.replaceFraction("1 a cups flour", "¼"))
        assertEquals("⅓ cup mik", PhotoTextSorter.replaceFraction("Ys cup mik", "⅓"))
        assertNull(PhotoTextSorter.replaceFraction("2% milk", "½"))
        assertNull(PhotoTextSorter.replaceFraction("½ cup sugar", "½"))
        assertEquals(listOf("½", "¼", "¾", "⅓", "⅔", "⅛"), PhotoTextSorter.FRACTIONS)
    }

    @Test fun `the fraction cards' lines are listed to check`() {
        // ML Kit's reading of card-fractions-hand.jpg on the emulator (#245).
        val reading = PhotoTextSorter.sort(lines(listOf(
            "Oat Cookies", "INGREDIENTS", "½ oup sugar", "1 a cups flour", "4 tsp salt", "Ys cup mik",
            "2 % cups oats", "& tsp nutmeg", "2 oz 70% chocolate", "METHOD", "Mix the dry ingredients."
        )))
        // "½ oup sugar"'s unit is misspelt (#276); "4 tsp salt" (¾ lost) and "mik" stay #249's.
        assertEquals(listOf("½ oup sugar", "1 a cups flour", "Ys cup mik", "2 % cups oats", "& tsp nutmeg"), reading.uncertain)
    }

    @Test fun `Vision's glued reading of the fraction card gets chips`() {
        // Vision's reading of card-fractions-hand.jpg on the iOS simulator (#245, #276).
        val reading = PhotoTextSorter.sort(lines(listOf(
            "Oat Cookies", "INGREDIENTS", "½ cup sugar", "1¼ cups flour", "3/4 tsp salt", "½⅓ cup milk",
            "23 cups oats", "is tsp nutmeg", "2 oz 70% chocolate", "METHOD", "Mix the dry ingredients."
        )))
        assertEquals(listOf("½⅓ cup milk", "is tsp nutmeg"), reading.uncertain)
        assertEquals(reading.uncertain, reading.uncertain.filter { PhotoTextSorter.fractionSlot(it) != null })
    }
}
