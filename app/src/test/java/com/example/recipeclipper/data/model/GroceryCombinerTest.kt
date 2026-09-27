package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grocery list's rule (#50): lines naming the same ingredient add up only when every amount
 * is exact and in one family of units that convert exactly; otherwise they sit together, each
 * as written. Never a confident wrong number.
 */
class GroceryCombinerTest {

    private val en = LanguageWords.ENGLISH

    private fun combine(vararg lines: String, words: LanguageWords = en) = GroceryCombiner.combine(lines.toList(), words)

    // --- Adding up

    @Test fun sameUnitAddsUp() {
        assertEquals("300 g flour", combine("200 g flour", "100 g flour"))
        assertEquals("3 cups flour", combine("2 cups flour", "1 cup flour"))
    }

    @Test fun metricWeightsAddUpInTheLargestUnitThatShowsItExactly() {
        assertEquals("1.2 kg flour", combine("200 g flour", "1 kg flour"))
        // 1.25 kg would show as "1.3", so it stays in grams.
        assertEquals("1250 g flour", combine("250 g flour", "1 kg flour"))
    }

    @Test fun usVolumesAddUpExactly() {
        assertEquals("1 1/8 cup milk", combine("1 cup milk", "2 tbsp milk"))
        // Only units the lines used: 3 tsp stays "3 tsp", not "1 tbsp".
        assertEquals("3 tsp sugar", combine("2 tsp sugar", "1 tsp sugar"))
        assertEquals("2/3 cup sugar", combine("1/3 cup sugar", "1/3 cup sugar"))
    }

    @Test fun oldStyleAbbreviationsAddUpWithTheWordsTheyStandFor() {
        // #135: "c." is a cup, "T" a tablespoon and "t" a teaspoon, so Delish's lines add to others.
        assertEquals("2 c. heavy cream", combine("1 c. heavy cream", "1 cup heavy cream"))
        assertEquals("2 cups heavy cream", combine("1/2 c. heavy cream", "1 1/2 cups heavy cream"))
        assertEquals("2 T. butter", combine("1 T. butter", "1 tbsp butter"))
        assertEquals("1 1/3 T. salt", combine("1 t. salt", "1 T. salt"))
        // "180 C" is a temperature, not 180 cups.
        assertNull(combine("180 C water", "1 cup water"))
    }

    @Test fun imperialWeightsAddUp() {
        assertEquals("1 1/2 lb ground beef", combine("1 lb ground beef", "8 oz ground beef"))
    }

    @Test fun metricVolumesAddUp() {
        assertEquals("750 ml milk", combine("500 ml milk", "250 ml milk"))
        assertEquals("1.5 l water", combine("1 l water", "500 ml water"))
    }

    @Test fun countsAddUpOnlyWithTheSameWords() {
        assertEquals("5 eggs", combine("2 eggs", "3 eggs"))
        assertEquals("5 large eggs", combine("2 large eggs", "3 Large  eggs"))
        assertNull(combine("2 large eggs", "3 eggs"))
    }

    @Test fun preparationAfterTheNameDoesNotStopATotal() {
        assertEquals("300 g butter", combine("200 g butter, softened", "100 g butter"))
    }

    @Test fun aDecimalCommaIsKept() {
        assertEquals("2,5 kg flour", combine("1,5 kg flour", "1 kg flour"))
        // 1,75 kg would show as "1,8", so it stays in grams.
        assertEquals("1750 g flour", combine("1,5 kg flour", "250 g flour"))
    }

    // --- Never a confident wrong number

    @Test fun gramsNeverAddToOunces() {
        assertNull(combine("200 g flour", "8 oz flour"))
    }

    @Test fun volumeNeverAddsToWeight() {
        assertNull(combine("1 cup flour", "100 g flour"))
        // A bare oz is a weight here, so it doesn't add to a cup of milk either.
        assertNull(combine("8 oz milk", "1 cup milk"))
    }

    @Test fun metricVolumeNeverAddsToCups() {
        assertNull(combine("250 ml milk", "1 cup milk"))
    }

    @Test fun rangesStayAsWritten() {
        assertNull(combine("2-3 cups flour", "1 cup flour"))
    }

    @Test fun compoundAmountsAndSecondMeasuresStayAsWritten() {
        assertNull(combine("1 cup plus 2 tbsp flour", "1 cup flour"))
        assertNull(combine("1 cup (120 g) flour", "1 cup flour"))
        assertNull(combine("1 cup/120 g flour", "1 cup flour"))
    }

    @Test fun packageSizesStayAsWritten() {
        // The same line twice is honest as it stands, times two; a different size never meets it.
        assertEquals("1 (14 oz) can tomatoes \u00d7 2", combine("1 (14 oz) can tomatoes", "1 (14 oz) can tomatoes"))
        assertNull(combine("1 (14 oz) can tomatoes", "1 (28 oz) can tomatoes"))
    }

    @Test fun anAmountWithoutANumberStaysAsWritten() {
        assertNull(combine("salt to taste", "1 tsp salt"))
        assertNull(combine("a pinch of salt", "1 tsp salt"))
    }

    @Test fun aTotalThatCantBeShownExactlyStaysAsWritten() {
        // 0.3 + 1/3 cup is no fraction or two-place decimal the list could show.
        assertNull(combine("0.3 cup sugar", "1/3 cup sugar"))
    }

    @Test fun unitsWhoseSizeVariesNeverAddUp() {
        val fr = LanguageWords.forTag("fr")!!
        assertNull(combine("1 tasse de farine", "2 tasses de farine", words = fr))
        assertEquals("1 tasse de farine \u00d7 2", combine("1 tasse de farine", "1 tasse de farine", words = fr))
    }

    @Test fun japaneseLinesAreNeverAddedUp() {
        val ja = LanguageWords.forTag("ja")!!
        assertNull(combine("醤油 大さじ1", "醤油 大さじ2", words = ja))
    }

    @Test fun otherLanguagesAddUpWithTheirOwnUnits() {
        val de = LanguageWords.forTag("de")!!
        assertEquals("300 g Mehl", combine("200 g Mehl", "100 g Mehl", words = de))
    }

    // --- The list

    private var nextId = 1L
    private fun item(text: String, checked: Boolean = false, language: String? = "en", aisle: Aisle? = null) =
        GroceryItem(
            id = nextId, text = text, language = language,
            aisle = aisle ?: Aisles.of(text, LanguageWords.forTag(language)), checked = checked, sortOrder = (nextId++).toInt()
        )

    @Test fun sectionsFollowTheAisleOrderAndLeaveEmptyOnesOut() {
        val sections = GroceryCombiner.sections(
            listOf(item("1 tsp salt"), item("2 onions"), item("paper towels"), item("1 cup milk"))
        )
        assertEquals(listOf(Aisle.PRODUCE, Aisle.DAIRY, Aisle.SPICES, Aisle.OTHER), sections.map { it.aisle })
    }

    @Test fun theSameIngredientCombinesOrSitsTogether() {
        val rows = GroceryCombiner.sections(
            listOf(item("200 g flour"), item("100 g flour"), item("1 cup sugar"), item("100 g sugar"))
        ).single().rows
        assertEquals(2, rows.size)
        val flour = rows[0] as GroceryCombiner.Row.Combined
        assertEquals("300 g flour", flour.text)
        assertEquals(2, flour.items.size)
        val sugar = rows[1] as GroceryCombiner.Row.Together
        assertEquals("sugar", sugar.name)
        assertEquals(listOf("1 cup sugar", "100 g sugar"), sugar.items.map { it.text })
    }

    @Test fun checkedLinesComeAfterAndNeverCombineWithUnchecked() {
        val rows = GroceryCombiner.sections(
            listOf(item("200 g flour", checked = true), item("100 g flour"), item("1 tsp baking powder"))
        ).single().rows
        assertEquals(listOf("100 g flour", "1 tsp baking powder", "200 g flour"), rows.map { (it as GroceryCombiner.Row.Single).item.text })
    }

    @Test fun linesWithNoNameOrNoLanguageGroupOnlyWithTheSameLine() {
        val rows = GroceryCombiner.sections(
            listOf(item("salt and pepper"), item("salt and pepper"), item("olive oil and butter"),
                item("2 eggs", language = null), item("2 eggs", language = null))
        ).flatMap { it.rows }
        assertEquals(
            listOf("salt and pepper × 2", "olive oil and butter", "2 eggs × 2"),
            rows.map { (it as? GroceryCombiner.Row.Combined)?.text ?: (it as GroceryCombiner.Row.Single).item.text }
        )
    }

    @Test fun anItemMovedToAnotherAisleLeavesItsGroup() {
        val rows = GroceryCombiner.sections(
            listOf(item("200 g flour"), item("100 g flour", aisle = Aisle.OTHER))
        )
        assertEquals(listOf(Aisle.BAKING, Aisle.OTHER), rows.map { it.aisle })
    }

    @Test fun theSharedTextListsWhatIsLeftToBuy() {
        val sections = GroceryCombiner.sections(
            listOf(item("2 onions"), item("200 g flour"), item("100 g flour"), item("1 cup sugar"), item("100 g sugar"),
                item("1 cup milk", checked = true))
        )
        assertEquals(
            "Groceries\n\nProduce\n- 2 onions\n\nBaking\n- 300 g flour\n- 1 cup sugar\n- 100 g sugar",
            GroceryShareText.format(sections, "Groceries") { it.name.lowercase().replaceFirstChar(Char::uppercase) }
        )
    }

    // --- Adding the same recipe more than once (the owner's report: three "2 corn" rows)

    @Test fun theSameLineAddedThreeTimesIsOneRow() {
        for ((line, total) in listOf("2 corn" to "6 corn", "1 cup milk" to "3 cup milk", "2 eggs" to "6 eggs")) {
            val row = GroceryCombiner.sections(List(3) { item(line).copy(recipeId = 7) }).flatMap { it.rows }.single()
            assertEquals(total, (row as GroceryCombiner.Row.Combined).text)
            assertEquals(listOf("$line × 3"), GroceryCombiner.lines(row))
        }
    }

    @Test fun aNoteInBracketsStillAddsUp() {
        assertEquals("6 corn (dfsafs -", combine("2 corn (dfsafs -", "2 corn (dfsafs -", "2 corn (dfsafs -"))
        assertEquals("6 corn, shucked", combine("2 corn, shucked", "2 corn, shucked", "2 corn, shucked"))
        // recipetineats.com's Greek zucchini tots, as WP Recipe Maker writes its notes.
        assertEquals("4 garlic cloves (, minced)", combine("2 garlic cloves (, minced)", "2 garlic cloves (, minced)"))
        // Two names either side of the slash: not one ingredient to add up, but the same line twice.
        assertEquals("1/4 tsp cooking salt / kosher salt \u00d7 2", combine("1/4 tsp cooking salt / kosher salt", "1/4 tsp cooking salt / kosher salt"))
    }

    @Test fun aBracketThatCouldBeAnotherAmountIsNeverSummed() {
        // Summed, the "1 lb" or "2 teaspoon" beside the total would be wrong: the line, times three.
        assertEquals("2 corn (about 1 lb) × 3", combine("2 corn (about 1 lb)", "2 corn (about 1 lb)", "2 corn (about 1 lb)"))
        assertEquals("1 tbsp dried oregano ((or 2 teaspoon other dried herbs)) × 2",
            combine("1 tbsp dried oregano ((or 2 teaspoon other dried herbs))", "1 tbsp dried oregano ((or 2 teaspoon other dried herbs))"))
        assertEquals("2 corn (about a pound) × 2", combine("2 corn (about a pound)", "2 corn (about a pound)"))
        assertEquals("1 lb / 500 g zucchinis ((courgettes)) × 2",
            combine("1 lb / 500 g zucchinis ((courgettes))", "1 lb / 500 g zucchinis ((courgettes))"))
        // Different lines with a possible second amount aren't the same line: they sit together.
        assertNull(combine("2 corn (about 1 lb)", "3 corn"))
    }

    @Test fun linesThatCannotBeSummedAreOneRowWithTheirLinesUnderIt() {
        val row = GroceryCombiner.sections(
            listOf(item("1 cup sugar"), item("100 g sugar"), item("1 cup sugar"))
        ).single().rows.single() as GroceryCombiner.Row.Together
        assertEquals(listOf("1 cup sugar × 2", "100 g sugar"), GroceryCombiner.lines(row))
    }
}
