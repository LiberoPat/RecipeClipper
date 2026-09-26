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

    @Test fun asksAboutTrailingTextOnEveryLineThatHasSome() {
        val list = items(
            "2 eggs, beaten" to Aisle.DAIRY, "3 eggs" to Aisle.DAIRY, "200 g butter, soft" to Aisle.DAIRY,
            "1 cup milk, soft" to Aisle.DAIRY, "2 eggs (about 100 g)" to Aisle.DAIRY
        )
        // Once per text; never text holding a figure.
        assertEquals(listOf(trailing(", beaten"), trailing(", soft")), GroceryDecisions.trailingTexts(list, Decisions.NONE))
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

    // --- Junk with no separator (#99): the model names the ingredient, the rest is trailing text

    private fun name(line: String) = DecisionQuestion.ingredientName(line, "en")

    @Test fun asksForTheNameOnlyWhereExtraWordsFollowAKnownOne() {
        assertEquals(name("2 onions dfsafs"), GroceryDecisions.nameQuestion("2 onions dfsafs", en))
        assertNull(GroceryDecisions.nameQuestion("2 onions", en))
        assertNull(GroceryDecisions.nameQuestion("2 onions, dfsafs", en)) // the separator split applies
        assertNull(GroceryDecisions.nameQuestion("1 cup rice flour", en)) // the table knows the whole name
        assertNull(GroceryDecisions.nameQuestion("2 玉ねぎ dfsafs", LanguageWords.forTag("ja")!!))
    }

    @Test fun acceptsANameOnlyVerbatimOnWordBoundaries() {
        assertEquals(GroceryDecisions.Split("2 onions", "dfsafs"), GroceryDecisions.nameSplit("2 onions dfsafs", "Onions", en))
        assertNull(GroceryDecisions.nameSplit("2 onions dfsafs", "onion", en)) // cuts into a word
        assertNull(GroceryDecisions.nameSplit("2 onions dfsafs", "shallots", en)) // not in the line
        assertNull(GroceryDecisions.nameSplit("2 onions dfsafs", "2 onions", en)) // the amount is no name
        assertNull(GroceryDecisions.nameSplit("2 onions dfsafs", "onions dfsafs", en)) // nothing left
        assertNull(GroceryDecisions.nameSplit("2 onions dfs 3", "onions", en)) // never a figure
    }

    @Test fun junkAfterANamedIngredientIsHiddenInGroceries() {
        val list = items("2 onions dfsafs" to Aisle.PRODUCE, "3 onions" to Aisle.PRODUCE)
        val d = decided(name("2 onions dfsafs") to "onions", trailing("dfsafs") to "junk")
        assertEquals("5 onions", (rows(list, d).single() as GroceryCombiner.Row.Combined).text)
        assertEquals(listOf("2 onions", "3 onions"), GroceryCombiner.lines(rows(list, d).single()))
        val alone = rows(items("2 onions dfsafs" to Aisle.PRODUCE), d).single() as GroceryCombiner.Row.Single
        assertEquals("2 onions", alone.item.text)
        // Unsure, or only a name: the line stays exactly as today.
        val unsure = decided(name("2 onions dfsafs") to "onions", trailing("dfsafs") to "unsure")
        assertEquals(GroceryCombiner.sections(list), GroceryCombiner.sections(list, unsure))
        assertEquals(GroceryCombiner.sections(list), GroceryCombiner.sections(list, decided(name("2 onions dfsafs") to "unsure")))
    }

    @Test fun aNoteStillShowsAndJunkAfterASeparatorIsHiddenToo() {
        val list = items("2 eggs, beaten" to Aisle.DAIRY, "3 eggs (dfsafs -" to Aisle.DAIRY)
        val d = decided(trailing(", beaten") to "note", trailing("(dfsafs -") to "junk")
        val row = rows(list, d).single() as GroceryCombiner.Row.Combined
        assertEquals(listOf("2 eggs, beaten", "3 eggs"), GroceryCombiner.lines(row))
        val shared = GroceryShareText.format(GroceryCombiner.sections(items("3 eggs (dfsafs -" to Aisle.DAIRY), d), "List") { it.key }
        assertEquals("List\n\ndairy\n- 3 eggs", shared)
    }

    @Test fun theRestOfANamedLineIsAskedAboutOnceTheNameLands() {
        val list = items("2 onions dfsafs" to Aisle.PRODUCE)
        assertTrue(GroceryDecisions.trailingTexts(list, Decisions.NONE).isEmpty())
        assertEquals(listOf(name("2 onions dfsafs")), GroceryDecisions.ingredientNames(list))
        val named = decided(name("2 onions dfsafs") to "onions")
        assertEquals(listOf(trailing("dfsafs")), GroceryDecisions.trailingTexts(list, named))
    }
}
