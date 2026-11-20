package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshFoodTest {

    private val today = 100L

    private fun row(name: String?, vararg days: Long?, language: String? = "en"): NeedRow = NeedRow(
        name,
        days.mapIndexed { i, day -> NeedLine("1 ${name ?: "x"}", recipeId = i + 1L, title = "Recipe ${i + 1}", day = day, language = language) },
        NeedStatus.BUY,
        null
    )

    // --- The split

    @Test fun `a perishable only for meals three or more days away goes later`() {
        val trips = FreshShop.split(listOf(row("salmon fillets", today + 4), row("onions", today + 5)), today)
        assertEquals(listOf("onions"), trips.now.map { it.name })
        assertEquals(listOf("salmon fillets"), trips.later.map { it.row.name })
        assertEquals("fish", trips.later.single().group.key)
        assertEquals(today + 4, trips.later.single().firstDay)
    }

    @Test fun `two days is still now, and the constant is three`() {
        assertEquals(3, Perishables.LATER_DAYS)
        val trips = FreshShop.split(listOf(row("salmon", today + 2), row("basil", today + 3)), today)
        assertEquals(listOf("salmon"), trips.now.map { it.name })
        assertEquals(listOf("basil"), trips.later.map { it.row.name })
    }

    @Test fun `every meal it is for must be far enough, or the whole row is bought now`() {
        val trips = FreshShop.split(
            listOf(row("salmon", today + 6, today + 2), row("basil", today + 6, today + 5), row("spinach", today + 4, null)),
            today
        )
        assertEquals(listOf("salmon", "spinach"), trips.now.map { it.name })
        assertEquals(listOf("basil"), trips.later.map { it.row.name })
        assertEquals(today + 5, trips.later.single().firstDay)
    }

    @Test fun `an ingredient not in the table, or a line with no name, is bought now however far`() {
        val trips = FreshShop.split(listOf(row("flour", today + 6), row(null, today + 6), row("smoked salmon", today + 6)), today)
        assertEquals(3, trips.now.size)
        assertTrue(trips.later.isEmpty())
    }

    @Test fun `a meal today or already passed is now`() {
        val trips = FreshShop.split(listOf(row("salmon", today), row("basil", today - 2)), today)
        assertEquals(2, trips.now.size)
    }

    @Test fun `the rows keep their order and their quantities untouched`() {
        val rows = listOf(row("onions", today + 5), row("salmon", today + 5), row("garlic", today + 1), row("basil", today + 6))
        val trips = FreshShop.split(rows, today)
        assertEquals(listOf("onions", "garlic"), trips.now.map { it.name })
        assertEquals(listOf("salmon", "basil"), trips.later.map { it.row.name })
        assertEquals(rows[1], trips.later[0].row)
    }

    @Test fun `the recipe's language picks the table`() {
        val trips = FreshShop.split(listOf(row("lachsfilets", today + 5, language = "de"), row("lachsfilets", today + 5, language = "en")), today)
        assertEquals(1, trips.later.size)
        assertEquals("de", trips.later.single().row.lines.single().language)
    }

    // --- The nudge

    private fun fish() = Perishable(PerishableGroup("fish", 1, 2), "salmon fillets")
    private fun herbs() = Perishable(PerishableGroup("herbs", 3, 5), "basil")

    @Test fun `a perishable meal later than its group keeps from the first day gets one swap, with the earliest day that fits`() {
        val meals = listOf(
            FreshMeal(1, today, null),
            FreshMeal(2, today + 1, null),
            FreshMeal(3, today + 5, fish()),
            FreshMeal(4, today + 2, null)
        )
        val nudge = FreshPlan.nudges(meals, today).single()
        assertEquals(3L, nudge.key)
        assertEquals("Salmon fillets", nudge.name)
        assertEquals("1-2", nudge.keeps)
        assertEquals(today + 5, nudge.day)
        assertEquals(1L, nudge.swapKey) // the earliest day that fits (the first day itself)
        assertEquals(today, nudge.swapDay)
    }

    @Test fun `within what the group keeps there is no note, counted from the plan's first day`() {
        assertTrue(FreshPlan.nudges(listOf(FreshMeal(1, today, null), FreshMeal(2, today + 2, fish())), today).isEmpty())
        // The first day is the plan's, not today's: planned from the 5th, a meal on the 6th is a day on.
        assertTrue(FreshPlan.nudges(listOf(FreshMeal(1, today + 5, null), FreshMeal(2, today + 6, fish())), today).isEmpty())
        assertEquals(1, FreshPlan.nudges(listOf(FreshMeal(1, today + 5, null), FreshMeal(2, today + 9, fish())), today).size)
    }

    @Test fun `no note when nothing could be swapped`() {
        // Alone: nothing to swap with.
        assertTrue(FreshPlan.nudges(listOf(FreshMeal(1, today + 6, fish())), today).isEmpty())
        // The only meal that fits holds a more delicate perishable than the later day allows.
        val meals = listOf(FreshMeal(1, today, fish()), FreshMeal(2, today + 6, fish()))
        assertTrue(FreshPlan.nudges(meals, today).isEmpty())
    }

    @Test fun `a partner with a perishable of its own must keep to the later day`() {
        // Herbs (3-5) are fine on day 4, so the fish may take their place...
        val fits = FreshPlan.nudges(listOf(FreshMeal(1, today, herbs()), FreshMeal(2, today + 4, fish())), today).single()
        assertEquals(2L, fits.key)
        assertEquals(1L, fits.swapKey)
        // ...but not on day 6, past the herbs' long end: no swap, so no note.
        assertTrue(FreshPlan.nudges(listOf(FreshMeal(1, today, herbs()), FreshMeal(2, today + 4, null), FreshMeal(3, today + 6, fish())), today).isEmpty())
    }

    @Test fun `the earliest day on a tie is the one listed first, a note counting as a meal to swap with`() {
        val meals = listOf(FreshMeal(1, today, null), FreshMeal(2, today, null), FreshMeal(3, today + 5, fish()))
        assertEquals(1L, FreshPlan.nudges(meals, today).single().swapKey)
    }

    @Test fun `with the first meal late already, the plan counts from it`() {
        val meals = listOf(FreshMeal(1, today + 3, null), FreshMeal(2, today + 7, fish()), FreshMeal(3, today + 8, null))
        val nudge = FreshPlan.nudges(meals, today).single()
        assertEquals(1L, nudge.swapKey)
        assertEquals(today + 3, nudge.swapDay)
    }

    @Test fun `meals before today are left out, and nothing is moved by the note itself`() {
        val meals = listOf(FreshMeal(1, today - 3, null), FreshMeal(2, today, null), FreshMeal(3, today + 5, fish()))
        val nudge = FreshPlan.nudges(meals, today).single()
        assertEquals(2L, nudge.swapKey)
        assertTrue(FreshPlan.nudges(listOf(FreshMeal(1, today - 3, fish())), today).isEmpty())
        assertTrue(FreshPlan.nudges(emptyList(), today).isEmpty())
    }
}
