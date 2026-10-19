package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.FractionLookAlike
import com.example.recipeclipper.data.model.IngredientScaler
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.SharedTables
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    /** Whether [PhotoTextSorter.check] lists [line] as an ingredient line, however sure the recogniser was. */
    private fun flagged(line: String, words: LanguageWords = LanguageWords.ENGLISH) =
        PhotoTextSorter.check(line, words).isNotEmpty()

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

    @Test fun `amounts shaped like a misreading are flagged`() {
        listOf(
            "11/2 cups flour", "13/4 cups sugar", "21/2 tsp baking soda", "31/3 cups milk",
            "Bake for 11/2 hours.", "3/2 cup water",
            "l/2 cup sugar", "I/4 tsp salt", "O.5 kg potatoes", "1/Z cup milk", "1/S tsp salt",
            "1O eggs", "Bake at 35o°F.", "l2 eggs", "|/2 cup oil",
            "1 cupraisitos", "1 cupraisins", "2 tbspsugar", "1 teaspoonvanilla"
        ).forEach { assertTrue(it, flagged(it)) }
    }

    @Test fun `ordinary amounts are not flagged`() {
        listOf(
            "1 1/2 cups flour", "1/2 cup sugar", "3/4 tsp salt", "2/2 cups", "1-1/2 cups oats",
            "1/2-3/4 cup milk", "11/16 inch", "1 cup raisins", "1cup flour", "2 green onions",
            "3 carrots", "12 cupcake liners", "1oz chocolate", "1l milk", "10 oz spinach",
            "2 lb chicken", "350°F", "Bake at 350°F for 12 minutes.", "Cool for 10 min.Serve.",
            "Aunt June's Oatmeal Cookies", "Stir in 2 eggs", "0.5 kg potatoes", "1,5 kg flour"
        ).forEach { assertFalse(it, flagged(it)) }
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
        ).forEach { assertTrue(it, flagged(it)) }
    }

    @Test fun `a real percentage, or a look-alike out of an amount's place, is not flagged`() {
        listOf(
            "2% milk", "1 cup 2% milk", "70% chocolate", "2 oz 70% chocolate", "200 g 70 % chocolate",
            "a cup of milk", "1 apple", "salt & pepper", "Reduce to 50% power.", "2 % Milch",
            "1 isn't", "½ cup sugar", "1¼ cups flour", "2 ⅔ cups oats"
        ).forEach { assertFalse(it, flagged(it)) }
    }

    @Test fun `a fraction glyph run into a digit or another glyph is flagged`() {
        assertTrue(flagged("3¾4 tsp salt"))
        assertTrue(flagged("½⅓ cup milk"))
    }

    @Test fun `a chip replaces a glued glyph run whole, keeping the whole number`() {
        assertEquals("3 ¾ tsp salt", PhotoTextSorter.replaceFraction("3¾4 tsp salt", "¾")) // glued: kept, set apart
        assertEquals("⅓ cup milk", PhotoTextSorter.replaceFraction("½⅓ cup milk", "⅓"))
        assertEquals("2 ⅔ cups oats", PhotoTextSorter.replaceFraction("2 ½⅔ cups oats", "⅔"))
        assertEquals("Add ½ cup milk.", PhotoTextSorter.replaceFraction("Add ½⅓ cup milk.", "½"))
        assertEquals("1 ⅓ cup milk", PhotoTextSorter.replaceFraction("1½⅓ cup milk", "⅓"))
        assertEquals("12 ½", PhotoTextSorter.replaceFraction("12½⅓", "½"))
        // No run: an ordinary glyph, a mixed number, a glyph after digits.
        listOf("½ cup sugar", "1¼ cups flour", "2 ⅔ cups oats", "12½ g", "1 ½ cups").forEach {
            assertNull(it, PhotoTextSorter.fractionSlot(it))
        }
    }

    @Test fun `the glued run is every glyph and digit from its first glyph on`() {
        assertEquals(1..2, FractionLookAlike.gluedRun("3¾4 tsp"))
        assertEquals(0..1, FractionLookAlike.gluedRun("½⅓ cup"))
        assertEquals(2..3, FractionLookAlike.gluedRun("2 ½⅓ cups"))
        assertEquals(1..3, FractionLookAlike.gluedRun("1½⅓4"))
        assertNull(FractionLookAlike.gluedRun("1¼ cups"))
        assertNull(FractionLookAlike.gluedRun("2 ⅔ cups"))
    }

    // Unit misspellings (#276): a near-miss of a unit where the unit goes, from splitter.json's
    // short unitLookAlikes, never edit distance.

    @Test fun `a near-miss of a unit after the amount is flagged`() {
        listOf(
            "½ oup sugar", "1 oups flour", "2 cuo milk", "1 c up milk", "1 C UPS milk", "2 tsb salt",
            "1 tps vanilla", "2 tbps butter", "1 tbsb oil", "1 isp salt", "1 ½ oup sugar", "2 tbps. butter"
        ).forEach { assertTrue(it, flagged(it)) }
        assertTrue(flagged("2 e l Zucker", LanguageWords.forTag("de")!!))
        assertTrue(flagged("1 oucharada de azúcar", LanguageWords.forTag("es")!!))
        assertTrue(flagged("2 ouillères de sucre", LanguageWords.forTag("fr")!!))
        assertTrue(flagged("1 oucchiaino di sale", LanguageWords.forTag("it")!!))
        assertTrue(flagged("2 oolheres de açúcar", LanguageWords.forTag("pt")!!))
    }

    @Test fun `real units, words and ingredient names are not flagged as misspelt units`() {
        listOf(
            "1 cup milk", "2 cups flour", "1 c milk", "1 c. sugar", "2 tsp salt", "1 tbsp oil", "2 tbs butter",
            "2 T sugar", "1 t salt", "1 oz butter", "2 g salt", "100 ml milk", "1 l water", "1 lb beef",
            "2 lbs beef", "1 kg flour", "3 teaspoons sugar", "2 tablespoons oil", "1 stick butter",
            "1 cupcake", "2 c upside-down cakes", "Bake for 1 hour.",
            "4 tsp salt", "½ cup mik", "2 tsps sugar", "1 oupa", "Add 1 oup sugar" // not after a leading amount
        ).forEach { assertFalse(it, flagged(it)) }
        listOf(
            "de" to "2 EL Zucker", "de" to "1 TL Salz", "de" to "1 Ei", "es" to "1 cucharada de azúcar",
            "fr" to "2 cuillères à soupe de sucre", "it" to "1 cucchiaino di sale", "pt" to "2 colheres de açúcar",
            "es" to "1 taza de harina", "ja" to "大さじ1 砂糖"
        ).forEach { (lang, line) -> assertFalse(line, flagged(line, LanguageWords.forTag(lang)!!)) }
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
        // "23 cups oats" (2 ⅔) is too many cups (#249): flagged, but with no chips.
        assertEquals(listOf("½⅓ cup milk", "23 cups oats", "is tsp nutmeg"), reading.uncertain)
        assertEquals(listOf("½⅓ cup milk", "is tsp nutmeg"), reading.uncertain.filter { PhotoTextSorter.fractionSlot(it) != null })
        // Only its size is wrong, so it gets its own reason (#281).
        assertEquals(listOf("23 cups oats"), reading.large)
    }

    @Test fun `a fraction read as digits is flagged when the amount is implausible for its unit`() {
        // Vision's readings of ¾, 2 ⅔ and ⅛ (#245, #249), and ML Kit's "2 23" for 2 ⅔.
        listOf(
            "34 tsp salt", "23 cups oats", "18 tsp nutmeg", "2 23 cups oats", "14 tsp. cinnamon",
            "12 tsp sugar", "34 Tbsp butter", "13 c milk", "10–14 tsp salt", "12 kg flour", "30 lb potatoes",
            "6000 g Mehl", "13 sticks butter", "14 cups milk"
        ).forEach {
            assertTrue(it, PhotoTextSorter.implausible(it))
            assertTrue(it, flagged(it))
        }
        assertTrue(PhotoTextSorter.implausible("34 EL Zucker", LanguageWords.forTag("de")!!))
        assertTrue(PhotoTextSorter.implausible("18 cucharaditas de sal", LanguageWords.forTag("es")!!))
    }

    @Test fun `a plausible amount, a count or a temperature is not flagged`() {
        listOf(
            // At a unit's maximum, a big batch, decimals, ranges, and misreads too plausible to catch.
            "10 tsp baking powder", "11 cups chicken stock", "16 tablespoons (227g) unsalted butter",
            "2–3 tbsp olive oil", "2 to 3 tbsp olive oil", "1.5 cups flour", "0.75 tsp salt", "1 1/2 cups sugar",
            "4 tsp salt", "8 tsp nutmeg", "32 oz chicken stock", "1000 g flour", "2 14 oz cans tomatoes",
            // Counts and no units are never checked; a bare Celsius temperature is no amount of cups.
            "12 eggs", "48 wonton wrappers", "180 C water", "350°F", "Bake for 25 minutes."
        ).forEach {
            assertFalse(it, PhotoTextSorter.implausible(it))
        }
        assertFalse(PhotoTextSorter.implausible("1,5 kg Mehl", LanguageWords.forTag("de")!!))
        assertFalse(PhotoTextSorter.implausible("1.500 g Mehl", LanguageWords.forTag("de")!!))
    }

    @Test fun `an implausible amount is flagged, never changed, and the scaler still reads it`() {
        val reading = PhotoTextSorter.sort(lines(listOf("INGREDIENTS", "34 tsp salt", "1 cup flour", "METHOD", "1. Mix.")))
        assertEquals(listOf("34 tsp salt", "1 cup flour"), reading.ingredients)
        assertEquals(listOf("34 tsp salt"), reading.uncertain)
        assertEquals("68 tsp salt", IngredientScaler.scale("34 tsp salt", 2.0))
    }

    @Test fun `an amount shaped like a fraction's digits is held to its unit's lower bar`() {
        // ½, ¼ and ⅓ read as digits (#281): under the unit's maximum, over its fraction-shaped bar.
        listOf(
            "12 tbsp vinegar", "13 tbsp. honey", "12 cup sugar", "12 cups chicken stock", "12 lb ground beef",
            "1 12 cups flour", "2 14 tbsp butter"
        ).forEach {
            assertTrue(it, PhotoTextSorter.implausible(it))
        }
        // Not shaped like a fraction, or a unit with no lower bar ("12 oz" and "15 g" are everyday).
        listOf(
            "16 tablespoons (227g) unsalted butter", "10 tbsp butter", "11 cups water", "10 cups flour",
            "12 oz spaghetti", "14 oz can coconut milk", "12 fl oz beer", "15 g yeast", "2 14 oz cans tomatoes"
        ).forEach {
            assertFalse(it, PhotoTextSorter.implausible(it))
        }
    }

    @Test fun `either end of a range is held to its unit's own maximum, never the fraction-shaped bar`() {
        // The owner's call (#283): "12–14 lb turkey" is a real range, not ½ and ¼ read as digits.
        listOf("16–18 lb turkey", "12–14 lb turkey", "10–12 cups water", "12 to 14 tbsp butter", "2 to 3 tbsp olive oil").forEach {
            assertFalse(it, PhotoTextSorter.implausible(it))
        }
        listOf("30–40 lb potatoes", "10–14 tsp salt", "12–14 cups water").forEach {
            assertTrue(it, PhotoTextSorter.implausible(it))
        }
    }

    @Test fun `a fraction's digits are never a temperature`() {
        // ⅝ and ⅞ read as digits fall in a plausible Celsius range (#283): "1 58 C sugar" is 1 ⅝ cups.
        listOf("1 58 C sugar", "2 78 c. flour", "2 78 c flour", "58 C sugar", "78 c. milk").forEach {
            assertTrue(it, PhotoTextSorter.implausible(it))
        }
        listOf("180 C water", "2 180 C water", "3 180 c water", "40 C water").forEach {
            assertFalse(it, PhotoTextSorter.implausible(it))
        }
    }

    @Test fun `a second number is judged only after a plain whole one, by its own temperature`() {
        // After a range, a fraction or a decimal, the lead's amounts aren't dropped for it (#281).
        listOf("1–2 34 tsp salt", "½ 34 tsp salt", "1.5 34 tsp salt").forEach {
            assertFalse(it, PhotoTextSorter.implausible(it))
        }
        assertTrue(PhotoTextSorter.implausible("2 34 tsp salt"))
        // The temperature exemption tests the number judged: 180 is one, 14 isn't.
        assertFalse(PhotoTextSorter.implausible("2 180 C water"))
        assertTrue(PhotoTextSorter.implausible("180 14 c milk"))
    }

    @Test fun `a step shaped like an ingredient line is size-checked, as a glaze after the method is`() {
        // #283: the splitter files a section after the method under the steps.
        val glaze = PhotoTextSorter.sort(lines(listOf(
            "Lemon Loaf", "INGREDIENTS", "1 1/2 cups flour", "1 cup sugar", "DIRECTIONS", "1. Mix and bake 50 minutes.",
            "GLAZE", "1 cup powdered sugar", "34 tsp vanilla"
        )))
        assertEquals(listOf("Mix and bake 50 minutes.", "GLAZE", "1 cup powdered sugar", "34 tsp vanilla"), glaze.instructions)
        assertEquals(listOf("34 tsp vanilla"), glaze.uncertain)
        assertEquals(listOf("34 tsp vanilla"), glaze.large)
        listOf("Frosting:", "For the sauce:").forEach { heading ->
            val reading = PhotoTextSorter.sort(lines(listOf(
                "INGREDIENTS", "1 cup flour", "2 eggs", "METHOD", "1. Mix and bake.", heading, "34 tsp vanilla", "2 cups powdered sugar"
            )))
            assertEquals(heading, listOf("34 tsp vanilla"), reading.large)
        }
    }

    @Test fun `a step's amount mid-sentence, a conversion and a Step N line are never size-checked`() {
        val sorted = PhotoTextSorter.sort(lines(listOf(
            "INGREDIENTS", "1 cup flour", "48 tsp = 1 cup", "METHOD", "Stir in 34 tsp salt.", "48 tsp = 1 cup",
            "Step 3: 34 tsp salt"
        )))
        assertTrue(sorted.sorted)
        assertEquals(listOf("1 cup flour", "48 tsp = 1 cup"), sorted.ingredients)
        assertEquals(listOf("Stir in 34 tsp salt.", "48 tsp = 1 cup", "34 tsp salt"), sorted.instructions)
        assertEquals(emptyList<String>(), sorted.uncertain)
        assertEquals(emptySet<CheckReason>(), PhotoTextSorter.check("34 tsp salt", labelled = true))
        assertEquals(setOf(CheckReason.LARGE), PhotoTextSorter.check("34 tsp salt"))
        assertEquals(emptySet<CheckReason>(), PhotoTextSorter.check("Stir in 34 tsp salt."))
    }

    @Test fun `unsorted, list markup goes before the size check, and steps and conversions are skipped`() {
        val reading = PhotoTextSorter.sort(lines(listOf(
            "Grandma's card", "▢ 34 tsp salt", "• 12 tbsp vinegar", "- 23 cups oats", "3) 18 tsp nutmeg",
            "48 tsp = 1 cup", "Step 4: 34 tsp salt"
        )))
        assertFalse(reading.sorted)
        val flagged = listOf("▢ 34 tsp salt", "• 12 tbsp vinegar", "- 23 cups oats", "3) 18 tsp nutmeg")
        assertEquals(flagged, reading.uncertain)
        assertEquals(flagged, reading.large)
        // Listed without their markup, so the list doesn't show "• ▢ 34 tsp salt" (#283).
        assertEquals(listOf("34 tsp salt", "12 tbsp vinegar", "23 cups oats", "18 tsp nutmeg"), reading.flags.map { it.shown })
    }

    @Test fun `unsorted, every check runs on the line as cleaned, and a chip keeps the line's markup`() {
        // #283: the look-alike, glued and misspelt-unit checks start where the amount does.
        val reading = PhotoTextSorter.sort(lines(listOf(
            "Grandma's card", "▢ 34 tsp salt", "▢ 2% cups oats", "• 1 oup sugar", "- 1 cupraisins"
        )))
        assertFalse(reading.sorted)
        assertEquals(
            listOf(
                LineFlag("▢ 34 tsp salt", "34 tsp salt", setOf(CheckReason.LARGE)),
                LineFlag("▢ 2% cups oats", "2% cups oats", setOf(CheckReason.MISREAD, CheckReason.FRACTION)),
                LineFlag("• 1 oup sugar", "1 oup sugar", setOf(CheckReason.MISREAD)),
                LineFlag("- 1 cupraisins", "1 cupraisins", setOf(CheckReason.MISREAD))
            ),
            reading.flags
        )
        val oats = reading.flags[1]
        assertEquals(
            LineFlag("▢ 2⅔ cups oats", "2⅔ cups oats", setOf(CheckReason.MISREAD)),
            PhotoTextSorter.replaceFraction(oats, "⅔")
        )
        assertNull(PhotoTextSorter.replaceFraction(reading.flags[0], "½"))
    }

    @Test fun `a line's confidence is its own, never that of a line quoting it`() {
        // #283: the unsure step quotes the ingredient line, which was read clearly and is only too large.
        val reading = PhotoTextSorter.sort(lines(
            listOf("INGREDIENTS", "34 tsp salt", "1 cup flour", "DIRECTIONS", "Stir in 34 tsp salt."),
            unsure = setOf("Stir in 34 tsp salt.")
        ))
        assertEquals(listOf("34 tsp salt", "Stir in 34 tsp salt."), reading.uncertain)
        assertEquals(listOf("34 tsp salt"), reading.large)
        assertEquals(setOf(CheckReason.UNSURE), reading.flags[1].reasons)
    }

    @Test fun `a line with another reason to check it isn't listed as only large`() {
        // Both misread ("11/2") and too large ("34 tsp"): listed, but not as only large (#283).
        assertEquals(setOf(CheckReason.MISREAD, CheckReason.LARGE), PhotoTextSorter.check("34 tsp salt, 11/2 tsp pepper"))
        val reading = PhotoTextSorter.sort(lines(
            listOf("INGREDIENTS", "34 tsp salt", "34 tsp salt, 11/2 tsp pepper", "12 tbsp vinegar", "METHOD", "Mix."),
            unsure = setOf("34 tsp salt")
        ))
        assertEquals(listOf("34 tsp salt", "34 tsp salt, 11/2 tsp pepper", "12 tbsp vinegar"), reading.uncertain)
        assertEquals(listOf("12 tbsp vinegar"), reading.large)
        assertEquals(setOf(CheckReason.UNSURE, CheckReason.LARGE), reading.flags[0].reasons)
    }

    @Test fun `a line read twice is listed once`() {
        val reading = PhotoTextSorter.sort(lines(listOf(
            "INGREDIENTS", "34 tsp salt", "1 cup flour", "34 tsp salt", "METHOD", "34 tsp salt", "Mix."
        )))
        assertEquals(listOf("34 tsp salt"), reading.uncertain)
    }

    @Test fun `a malformed plausible-amounts table throws, as SharedTables does`() {
        PlausibleAmounts.from(SharedTables.read("plausible-amounts"))
        PlausibleAmounts.from(JSONObject("""{"maximums": {"TSP": 10}, "fractionShapedMaximums": {}, "fractionShapes": ["12"]}"""))
        listOf(
            """{"maximums": {"TSP": "ten"}, "fractionShapedMaximums": {}, "fractionShapes": []}""",
            """{"maximums": {"TEASPOON": 10}, "fractionShapedMaximums": {}, "fractionShapes": []}""",
            """{"fractionShapedMaximums": {}, "fractionShapes": []}""",
            """{"maximums": {"TSP": 10}, "fractionShapes": []}""",
            """{"maximums": {"TSP": 10}, "fractionShapedMaximums": {"TSP": 12}, "fractionShapes": []}""",
            """{"maximums": {"TSP": 10}, "fractionShapedMaximums": {}, "fractionShapes": ["½"]}""",
            // Strict, as iOS reads it (#283): a number written as a string, NaN, a boolean, nothing
            // above zero, and a fraction shape written as a number are all refused.
            """{"maximums": {"TSP": "10"}, "fractionShapedMaximums": {}, "fractionShapes": ["12"]}""",
            """{"maximums": {"TSP": "NaN"}, "fractionShapedMaximums": {}, "fractionShapes": ["12"]}""",
            """{"maximums": {"TSP": true}, "fractionShapedMaximums": {}, "fractionShapes": ["12"]}""",
            """{"maximums": {"TSP": 0}, "fractionShapedMaximums": {}, "fractionShapes": ["12"]}""",
            """{"maximums": {"TSP": -1}, "fractionShapedMaximums": {}, "fractionShapes": ["12"]}""",
            """{"maximums": {"TBSP": 16}, "fractionShapedMaximums": {"TBSP": "10"}, "fractionShapes": ["12"]}""",
            """{"maximums": {"TSP": 10}, "fractionShapedMaximums": {}, "fractionShapes": [12]}"""
        ).forEach { json ->
            assertThrows(json, Exception::class.java) { PlausibleAmounts.from(JSONObject(json)) }
        }
    }
}
