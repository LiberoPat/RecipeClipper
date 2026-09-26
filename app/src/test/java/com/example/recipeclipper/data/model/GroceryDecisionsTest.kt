package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model's help merging grocery lines (#99): given each decision, what the list shows. The
 * model never writes a number; its answers only regroup lines or drop trailing text, and the
 * combiner's exact rules still decide every total.
 */
class GroceryDecisionsTest {

    private val en = LanguageWords.ENGLISH

    private fun items(vararg lines: Pair<String, Aisle>) =
        lines.mapIndexed { i, (text, aisle) -> GroceryItem(i + 1L, text, "en", aisle, false, i) }

    private fun decided(vararg answers: Pair<DecisionQuestion, String>) = Decisions(answers.toMap())

    private fun same(a: String, b: String) = DecisionQuestion.sameGrocery(a, b, "en")
    private fun trailing(text: String) = DecisionQuestion.trailingText(text, "en")

    private fun rows(items: List<GroceryItem>, d: Decisions) = GroceryCombiner.sections(items, d).flatMap { it.rows }

    // --- Splitting off the trailing text

    @Test fun splitsAtTheFirstCutAfterAName() {
        assertEquals(GroceryDecisions.Split("2 eggs", "(dfsafs -"), GroceryDecisions.split("2 eggs (dfsafs -", en))
        assertEquals(GroceryDecisions.Split("2 ears of corn", ", shucked"), GroceryDecisions.split("2 ears of corn, shucked", en))
        assertEquals(GroceryDecisions.Split("2 onions", "-- sdf"), GroceryDecisions.split("2 onions -- sdf", en))
    }

    @Test fun neverSplitsOffAFigureOrAPackageSize() {
        assertNull(GroceryDecisions.split("2 eggs (about 100 g)", en))
        assertNull(GroceryDecisions.split("3 eggs, plus 2 yolks", en))
        assertNull(GroceryDecisions.split("2 eggs", en))
        assertNull(GroceryDecisions.split("1 (14 oz) can tomatoes", en))
    }

    // --- What each answer changes

    @Test fun withoutAnswersTheListIsAsToday() {
        val list = items("2 ears of corn" to Aisle.OTHER, "2 corn" to Aisle.OTHER)
        assertEquals(GroceryCombiner.sections(list), GroceryCombiner.sections(list, Decisions.NONE))
        assertEquals(2, rows(list, Decisions.NONE).size)
    }

    @Test fun sameGroupsButCountsWithDifferentWordsStayAsWritten() {
        val list = items("2 ears of corn" to Aisle.OTHER, "2 corn" to Aisle.OTHER)
        val row = rows(list, decided(same("ears of corn", "corn") to "same")).single()
        assertTrue(row is GroceryCombiner.Row.Together)
        assertEquals("ears of corn", (row as GroceryCombiner.Row.Together).name)
    }

    @Test fun sameAddsUpOnlyWhatTheExactRulesAllow() {
        val list = items("200 g sweetcorn" to Aisle.OTHER, "100 g corn" to Aisle.OTHER)
        val row = rows(list, decided(same("sweetcorn", "corn") to "same")).single()
        assertEquals("300 g corn", (row as GroceryCombiner.Row.Combined).text)
    }

    @Test fun differentOrUnsureChangesNothing() {
        val list = items("2 ears of corn" to Aisle.OTHER, "2 corn" to Aisle.OTHER)
        assertEquals(2, rows(list, decided(same("ears of corn", "corn") to "different")).size)
        assertEquals(2, rows(list, decided(same("ears of corn", "corn") to "unsure")).size)
    }

    @Test fun aNoteOrJunkNoLongerBlocksAddingUp() {
        val list = items("2 eggs, beaten" to Aisle.DAIRY, "3 eggs" to Aisle.DAIRY)
        assertTrue(rows(list, Decisions.NONE).single() is GroceryCombiner.Row.Together)
        for (answer in listOf("note", "junk")) {
            val row = rows(list, decided(trailing(", beaten") to answer)).single()
            assertEquals("5 eggs", (row as GroceryCombiner.Row.Combined).text)
        }
    }

    @Test fun aSecondAmountOrUnsureStillBlocksIt() {
        val list = items("2 eggs, beaten" to Aisle.DAIRY, "3 eggs" to Aisle.DAIRY)
        for (answer in listOf("second_amount", "unsure")) {
            assertTrue(rows(list, decided(trailing(", beaten") to answer)).single() is GroceryCombiner.Row.Together)
        }
    }

    // --- What is asked

    @Test fun asksAboutCloseNamesThatCouldShareARow() {
        val list = items("2 ears of corn" to Aisle.OTHER, "2 corn" to Aisle.OTHER, "1 cup rice flour" to Aisle.BAKING)
        assertEquals(listOf(same("ears of corn", "corn")), GroceryDecisions.samePairs(list, Decisions.NONE))
        // Two aisles the table or the user chose never meet.
        val apart = items("3 garlic cloves" to Aisle.SPICES, "2 cloves garlic" to Aisle.PRODUCE)
        assertTrue(GroceryDecisions.samePairs(apart, Decisions.NONE).isEmpty())
    }

    @Test fun asksAboutTrailingTextOnlyWhereItBlocksAPartner() {
        val list = items("2 eggs, beaten" to Aisle.DAIRY, "3 eggs" to Aisle.DAIRY, "200 g butter, soft" to Aisle.DAIRY)
        assertEquals(listOf(trailing(", beaten")), GroceryDecisions.trailingTexts(list, Decisions.NONE))
    }

    @Test fun aFreshAnswerFilesALineOutOfOtherBesideItsPartner() {
        val list = items("3 garlic cloves" to Aisle.OTHER, "2 cloves garlic" to Aisle.PRODUCE, "2 eggs (dfsafs -" to Aisle.OTHER)
        val q1 = same("garlic cloves", "garlic")
        val q2 = trailing("(dfsafs -")
        val d = decided(q1 to "same", q2 to "junk")
        assertEquals(mapOf(Aisle.PRODUCE to listOf(1L), Aisle.DAIRY to listOf(3L)), GroceryDecisions.filing(list, setOf(q1, q2), d))
        // A cached answer (not fresh) never moves anything: the user's aisle stands.
        assertTrue(GroceryDecisions.filing(list, emptySet(), d).isEmpty())
    }
}
