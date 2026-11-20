package com.example.recipeclipper.data.model

import com.example.recipeclipper.data.model.PlanShift.Choice
import com.example.recipeclipper.data.model.PlanShift.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanShiftTest {

    private val lunch = 2L
    private val dinner = 3L
    private val today = 100L
    private val far = 1_000L

    private fun meal(id: Long, day: Long, type: Long = dinner, note: String? = null) = PlannedMeal(
        id = id, day = day, mealTypeId = type,
        recipeId = if (note == null) id else null,
        title = if (note == null) "Recipe $id" else null,
        imageUrl = null, servings = null, note = note
    )

    private fun plan(meals: List<PlannedMeal>, id: Long, choice: Choice, lastDay: Long = far) =
        PlanShift.plan(meals, id, choice, today, lastDay)!!

    // --- Push the rest of the week

    @Test fun `push moves the skipped meal and every later one by a day`() {
        val meals = listOf(meal(1, 100), meal(2, 101), meal(3, 103))
        val result = plan(meals, 1, Choice.PUSH_WEEK)
        assertEquals(listOf(Move(1, 100, 101), Move(2, 101, 102), Move(3, 103, 104)), result.moved)
        assertTrue(result.removed.isEmpty())
    }

    @Test fun `push never moves a meal before the skipped one`() {
        val meals = listOf(meal(1, 100), meal(2, 101), meal(3, 102), meal(4, 103))
        val result = plan(meals, 3, Choice.PUSH_WEEK)
        assertEquals(listOf(Move(3, 102, 103), Move(4, 103, 104)), result.moved)
    }

    @Test fun `push on a day with several meals moves the ones after the skipped meal, not the ones before`() {
        // Breakfast (type 1), lunch, dinner on day 100, in plan order.
        val meals = listOf(meal(1, 100, 1), meal(2, 100, lunch), meal(3, 100, dinner), meal(4, 101, dinner))
        val result = plan(meals, 2, Choice.PUSH_WEEK)
        assertEquals(listOf(Move(2, 100, 101), Move(3, 100, 101), Move(4, 101, 102)), result.moved)
    }

    @Test fun `push moves two meals of one slot together and keeps their meal types`() {
        val meals = listOf(meal(1, 100), meal(2, 101, lunch), meal(3, 101, dinner), meal(4, 101, dinner))
        val result = plan(meals, 1, Choice.PUSH_WEEK)
        assertEquals(listOf(1L, 2L, 3L, 4L), result.moved.map { it.mealId })
        assertTrue(result.moved.all { it.toDay == it.fromDay + 1 })
    }

    @Test fun `push moves planned notes too`() {
        val meals = listOf(meal(1, 100), meal(2, 101, note = "Eat out"), meal(3, 104, dinner, note = "Leftovers"))
        val result = plan(meals, 1, Choice.PUSH_WEEK)
        assertEquals(listOf(Move(1, 100, 101), Move(2, 101, 102), Move(3, 104, 105)), result.moved)
    }

    @Test fun `push keeps the gaps, it doesn't close them`() {
        val meals = listOf(meal(1, 100), meal(2, 105))
        assertEquals(listOf(Move(1, 100, 101), Move(2, 105, 106)), plan(meals, 1, Choice.PUSH_WEEK).moved)
    }

    @Test fun `push alone on the plan moves just that meal`() {
        assertEquals(listOf(Move(7, 100, 101)), plan(listOf(meal(7, 100)), 7, Choice.PUSH_WEEK).moved)
    }

    @Test fun `push reports the meals that end past the last day shown`() {
        val meals = listOf(meal(1, 100), meal(2, 150), meal(3, 151))
        val result = plan(meals, 1, Choice.PUSH_WEEK, lastDay = 151)
        assertEquals(listOf(3L), result.pastEnd)
        assertEquals(emptyList<Long>(), plan(meals, 1, Choice.PUSH_WEEK, lastDay = 152).pastEnd)
    }

    @Test fun `push of a meal in the far future leaves earlier ones alone`() {
        val meals = listOf(meal(1, 100), meal(2, 140))
        assertEquals(listOf(Move(2, 140, 141)), plan(meals, 2, Choice.PUSH_WEEK).moved)
    }

    // --- Move to the next free day

    @Test fun `next free day is the day after when its meal type is free`() {
        val meals = listOf(meal(1, 100), meal(2, 101, lunch))
        val result = plan(meals, 1, Choice.NEXT_FREE_DAY)
        assertEquals(listOf(Move(1, 100, 101)), result.moved)
    }

    @Test fun `next free day skips days that already hold a meal of that type, notes included`() {
        val meals = listOf(meal(1, 100), meal(2, 101), meal(3, 102, note = "Pizza night"), meal(4, 104))
        assertEquals(listOf(Move(1, 100, 103)), plan(meals, 1, Choice.NEXT_FREE_DAY).moved)
    }

    @Test fun `free is defined per meal type, so a lunch does not block a dinner`() {
        val meals = listOf(meal(1, 100, dinner), meal(2, 101, lunch), meal(3, 102, lunch))
        assertEquals(listOf(Move(1, 100, 101)), plan(meals, 1, Choice.NEXT_FREE_DAY).moved)
        val lunchSkipped = listOf(meal(1, 100, lunch), meal(2, 101, lunch), meal(3, 102, dinner))
        assertEquals(listOf(Move(1, 100, 102)), plan(lunchSkipped, 1, Choice.NEXT_FREE_DAY).moved)
    }

    @Test fun `next free day ignores meals before it and other meals on the skipped day`() {
        val meals = listOf(meal(1, 99), meal(2, 100, lunch), meal(3, 100, dinner), meal(4, 100, dinner))
        assertEquals(listOf(Move(3, 100, 101)), plan(meals, 3, Choice.NEXT_FREE_DAY).moved)
    }

    @Test fun `next free day moves nothing else`() {
        val meals = listOf(meal(1, 100), meal(2, 101), meal(3, 103))
        assertEquals(listOf(Move(1, 100, 102)), plan(meals, 1, Choice.NEXT_FREE_DAY).moved)
    }

    @Test fun `next free day past the last day shown is reported`() {
        val meals = listOf(meal(1, 100), meal(2, 101))
        assertEquals(listOf(1L), plan(meals, 1, Choice.NEXT_FREE_DAY, lastDay = 101).pastEnd)
    }

    // --- Remove

    @Test fun `remove drops only the skipped meal`() {
        val meals = listOf(meal(1, 100), meal(2, 101))
        val result = plan(meals, 1, Choice.REMOVE)
        assertEquals(listOf(1L), result.removed)
        assertTrue(result.moved.isEmpty())
        assertTrue(result.pastEnd.isEmpty())
    }

    // --- What is offered

    @Test fun `a meal in the past is not offered and has no plan`() {
        val meals = listOf(meal(1, 99), meal(2, 100))
        assertEquals(false, PlanShift.canSkip(meals[0], today))
        assertEquals(true, PlanShift.canSkip(meals[1], today))
        for (choice in Choice.entries) assertNull(PlanShift.plan(meals, 1, choice, today, far))
    }

    @Test fun `an unknown meal has no plan`() {
        assertNull(PlanShift.plan(listOf(meal(1, 100)), 9, Choice.PUSH_WEEK, today, far))
    }

    @Test fun `a past meal in the list is never moved by a push of a later one`() {
        val meals = listOf(meal(1, 90), meal(2, 100), meal(3, 101))
        assertEquals(listOf(Move(2, 100, 101), Move(3, 101, 102)), plan(meals, 2, Choice.PUSH_WEEK).moved)
    }
}
