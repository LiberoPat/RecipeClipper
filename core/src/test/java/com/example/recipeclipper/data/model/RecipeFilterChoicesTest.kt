package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter sheet with the views folded in (#454): each control asks for the view screens' own filter or
 * #378's, nothing new; the pills and the badge follow the sheet's order; a pill's × undoes only its control.
 */
class RecipeFilterChoicesTest {

    @Test fun `nothing chosen asks for nothing and shows no pill`() {
        val none = RecipeFilterChoices.NONE
        assertTrue(none.isEmpty)
        assertEquals(emptySet<RecipeFilter>(), none.views)
        assertEquals(emptySet<RecipeQuickFilter>(), none.quick)
        assertEquals(emptyList<RecipeFilterPill>(), none.pills)
    }

    @Test fun `Any and Anywhere are no filter`() {
        val choices = RecipeFilterChoices(cooked = CookedChoice.ANY, origin = OriginChoice.ANYWHERE)
        assertTrue(choices.isEmpty)
        assertTrue(choices.pills.isEmpty())
    }

    @Test fun `each control asks for exactly the view or the quick filter it stands for`() {
        fun check(choices: RecipeFilterChoices, views: Set<RecipeFilter>, quick: Set<RecipeQuickFilter>, pill: RecipeFilterPill) {
            assertEquals(views, choices.views)
            assertEquals(quick, choices.quick)
            assertEquals(listOf(pill), choices.pills)
            assertFalse(choices.isEmpty)
        }
        check(RecipeFilterChoices(thirtyOrLess = true), emptySet(), setOf(RecipeQuickFilter.THIRTY_OR_LESS), RecipeFilterPill.THIRTY_OR_LESS)
        // Cooked is the Cooked view (its EXISTS); Not yet is #378's Not cooked yet, that view turned round.
        check(RecipeFilterChoices(cooked = CookedChoice.COOKED), setOf(RecipeFilter.COOKED), emptySet(), RecipeFilterPill.COOKED)
        check(RecipeFilterChoices(cooked = CookedChoice.NOT_YET), emptySet(), setOf(RecipeQuickFilter.NOT_COOKED), RecipeFilterPill.NOT_COOKED)
        check(RecipeFilterChoices(usesPantry = true), emptySet(), setOf(RecipeQuickFilter.USES_PANTRY), RecipeFilterPill.USES_PANTRY)
        check(RecipeFilterChoices(notInAList = true), setOf(RecipeFilter.NOT_IN_A_LIST), emptySet(), RecipeFilterPill.NOT_IN_A_LIST)
        check(RecipeFilterChoices(origin = OriginChoice.TYPED), setOf(RecipeFilter.TYPED), emptySet(), RecipeFilterPill.TYPED)
        check(RecipeFilterChoices(origin = OriginChoice.CHAT), setOf(RecipeFilter.CHAT), emptySet(), RecipeFilterPill.CHAT)
    }

    @Test fun `every filter on combines, and the pills keep the sheet's order`() {
        val all = RecipeFilterChoices(
            thirtyOrLess = true, cooked = CookedChoice.COOKED, usesPantry = true, notInAList = true, origin = OriginChoice.CHAT
        )
        assertEquals(setOf(RecipeFilter.COOKED, RecipeFilter.NOT_IN_A_LIST, RecipeFilter.CHAT), all.views)
        assertEquals(setOf(RecipeQuickFilter.THIRTY_OR_LESS, RecipeQuickFilter.USES_PANTRY), all.quick)
        assertEquals(
            listOf(
                RecipeFilterPill.THIRTY_OR_LESS, RecipeFilterPill.COOKED, RecipeFilterPill.USES_PANTRY,
                RecipeFilterPill.NOT_IN_A_LIST, RecipeFilterPill.CHAT
            ),
            all.pills
        )
    }

    @Test fun `a pill's x undoes its own control only, a choice back to Any`() {
        val all = RecipeFilterChoices(
            thirtyOrLess = true, cooked = CookedChoice.NOT_YET, usesPantry = true, notInAList = true, origin = OriginChoice.TYPED
        )
        assertEquals(all.copy(thirtyOrLess = false), all.without(RecipeFilterPill.THIRTY_OR_LESS))
        assertEquals(all.copy(cooked = CookedChoice.ANY), all.without(RecipeFilterPill.NOT_COOKED))
        assertEquals(all.copy(usesPantry = false), all.without(RecipeFilterPill.USES_PANTRY))
        assertEquals(all.copy(notInAList = false), all.without(RecipeFilterPill.NOT_IN_A_LIST))
        assertEquals(all.copy(origin = OriginChoice.ANYWHERE), all.without(RecipeFilterPill.TYPED))
        assertEquals(
            RecipeFilterChoices(cooked = CookedChoice.ANY),
            RecipeFilterChoices(cooked = CookedChoice.COOKED).without(RecipeFilterPill.COOKED)
        )
        assertTrue(
            all.pills.fold(all) { choices, pill -> choices.without(pill) }.isEmpty
        )
    }

    @Test fun `Not yet narrows the database's answer as Not cooked yet does`() {
        val cooked = RecipeSummary(1, "Cooked", null, null, 0, isSaved = false, lastCookedDay = 20_000)
        val fresh = RecipeSummary(2, "Fresh", null, null, 0, isSaved = false)
        val notYet = RecipeFilterChoices(cooked = CookedChoice.NOT_YET)
        assertEquals(listOf(fresh), RecipeQuickFilters.apply(listOf(cooked, fresh), notYet.quick))
    }
}
