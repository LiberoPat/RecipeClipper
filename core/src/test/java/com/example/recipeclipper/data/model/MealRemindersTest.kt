package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The evening-before and Tonight reminders (#397); iOS's `MealRemindersTests` has the same cases. */
class MealRemindersTest {

    private val types = listOf(
        MealType(id = 3, name = "Dinner", builtInKey = "dinner", sortOrder = 2),
        MealType(id = 1, name = "Breakfast", builtInKey = "breakfast", sortOrder = 0),
        MealType(id = 2, name = "Lunch", builtInKey = "lunch", sortOrder = 1),
        MealType(id = 9, name = "Pudding", builtInKey = null, sortOrder = 3)
    )

    private fun meal(id: Long, day: Long, type: Long, title: String? = null, note: String? = null, recipe: Long? = title?.let { id }) =
        PlannedMeal(id = id, day = day, mealTypeId = type, recipeId = recipe, title = title, imageUrl = null, servings = null, note = note)

    private val ahead = AheadStep(2, "Marinate at least 8 hours or overnight.")

    @Test fun theEveningReminderNamesTomorrowsMealsInMealTypeOrder() {
        val meals = listOf(
            meal(1, 101, 3, "Lemon chicken"),
            meal(2, 101, 1, "Porridge"),
            meal(3, 100, 3, "Today's stew"),
            meal(4, 102, 3, "The day after")
        )
        val reminder = MealReminders.build(MealReminderKind.EVENING, fireDay = 100, meals, types)!!
        assertEquals(listOf("Porridge", "Lemon chicken"), reminder.titles)
        assertEquals(100L, reminder.day)
        assertEquals(2L, reminder.recipeId)
    }

    @Test fun aNoteShowsItsTextAndOpensNoRecipe() {
        val meals = listOf(meal(1, 101, 3, note = " Leftovers "))
        val reminder = MealReminders.build(MealReminderKind.EVENING, 100, meals, types)!!
        assertEquals(listOf("Leftovers"), reminder.titles)
        assertNull(reminder.recipeId)
    }

    @Test fun theFirstRecipeInOrderIsTheOneATapOpens() {
        val meals = listOf(meal(1, 101, 9, note = "Ice cream"), meal(2, 101, 3, "Soup"), meal(3, 101, 2, "Salad"))
        assertEquals(3L, MealReminders.build(MealReminderKind.EVENING, 100, meals, types)!!.recipeId)
    }

    @Test fun nothingPlannedTomorrowIsNoReminder() {
        assertNull(MealReminders.build(MealReminderKind.EVENING, 100, listOf(meal(1, 100, 3, "Today")), types))
        assertNull(MealReminders.build(MealReminderKind.EVENING, 100, emptyList(), types))
        // A recipe that's gone and an empty note name nothing.
        val gone = listOf(meal(1, 101, 3, title = null, recipe = 1), meal(2, 101, 3, note = "  "))
        assertNull(MealReminders.build(MealReminderKind.EVENING, 100, gone, types))
    }

    @Test fun theSameMealTwiceIsNamedOnce() {
        val meals = listOf(meal(1, 101, 3, "Soup", recipe = 7), meal(2, 101, 3, "Soup", recipe = 7))
        val reminder = MealReminders.build(MealReminderKind.EVENING, 100, meals, types) { ahead }!!
        assertEquals(listOf("Soup"), reminder.titles)
        assertEquals(listOf(AheadNote(7, "Soup", ahead.quote)), reminder.ahead)
    }

    @Test fun anAheadStepIsQuotedForEachRecipeThatHasOne() {
        val meals = listOf(meal(1, 101, 1, "Porridge"), meal(2, 101, 3, "Lemon chicken"))
        val reminder = MealReminders.build(MealReminderKind.EVENING, 100, meals, types) { id -> ahead.takeIf { id == 2L } }!!
        assertEquals(listOf(AheadNote(2, "Lemon chicken", ahead.quote)), reminder.ahead)
        // No ahead step: just the meals.
        assertEquals(emptyList<AheadNote>(), MealReminders.build(MealReminderKind.EVENING, 100, meals, types)!!.ahead)
    }

    @Test fun tonightNamesTodaysMealsLeftOutBreakfastAndLunch() {
        val meals = listOf(
            meal(1, 100, 1, "Porridge"), meal(2, 100, 2, "Sandwich"), meal(3, 100, 3, "Lemon chicken"),
            meal(4, 100, 9, note = "Ice cream"), meal(5, 101, 3, "Tomorrow")
        )
        val reminder = MealReminders.build(MealReminderKind.TONIGHT, fireDay = 100, meals, types) { ahead }!!
        assertEquals(listOf("Lemon chicken", "Ice cream"), reminder.titles)
        // Too late to start anything ahead.
        assertEquals(emptyList<AheadNote>(), reminder.ahead)
        assertNull(MealReminders.build(MealReminderKind.TONIGHT, 100, listOf(meal(1, 100, 1, "Porridge")), types))
    }

    @Test fun aRenamedBreakfastIsStillBreakfast() {
        val renamed = types.map { if (it.builtInKey == "breakfast") it.copy(name = "Morning") else it }
        assertNull(MealReminders.build(MealReminderKind.TONIGHT, 100, listOf(meal(1, 100, 1, "Porridge")), renamed))
    }

    @Test fun theFirstDayIsTodayUntilItsTimeHasPassed() {
        assertEquals(100L, MealReminders.firstDay(today = 100, minuteOfDay = 18 * 60 + 59, atMinute = MealReminders.EVENING_MINUTE))
        assertEquals(101L, MealReminders.firstDay(100, MealReminders.EVENING_MINUTE, MealReminders.EVENING_MINUTE))
        assertEquals(101L, MealReminders.firstDay(100, 23 * 60, MealReminders.EVENING_MINUTE))
    }

    @Test fun theDefaultTimes() {
        assertEquals(1140, MealReminders.EVENING_MINUTE)
        assertEquals(960, MealReminders.TONIGHT_MINUTE)
    }

    @Test fun planIsEachDayWithSomethingToNameForAWeek() {
        val meals = listOf(meal(1, 101, 3, "A"), meal(2, 103, 3, "B"), meal(3, 108, 3, "Beyond the week"))
        // Today 100 at 20:00, past 19:00: the first reminder fires tomorrow (101) and names 102's
        // meals, so A (101) has had its reminder; B (103) is named on 102, and 108 on 107.
        val planned = MealReminders.plan(MealReminderKind.EVENING, MealReminders.EVENING_MINUTE, 100, 20 * 60, meals, types)
        assertEquals(listOf(102L, 107L), planned.map { it.day })
        assertEquals(listOf("B", "Beyond the week"), planned.flatMap { it.titles })
        // Before the time, today's reminder is still to come: it fires 100 and names A.
        val early = MealReminders.plan(MealReminderKind.EVENING, MealReminders.EVENING_MINUTE, 100, 8 * 60, meals, types)
        assertEquals(listOf(100L, 102L), early.map { it.day })
        assertEquals(listOf("A", "B"), early.flatMap { it.titles })
    }

    @Test fun thePlanEndsAtMaxDays() {
        val meals = (101L..120L).map { meal(it, it, 3, "Meal $it") }
        val planned = MealReminders.plan(MealReminderKind.EVENING, MealReminders.EVENING_MINUTE, 100, 8 * 60, meals, types)
        assertEquals((100L..106L).toList(), planned.map { it.day })
    }

    @Test fun recipeIdsAreThePlannedRecipesOnce() {
        val meals = listOf(meal(1, 101, 3, "A", recipe = 5), meal(2, 101, 3, "A", recipe = 5), meal(3, 101, 1, note = "x"))
        assertEquals(listOf(5L), MealReminders.recipeIds(meals))
    }

    private fun reminder(vararg ahead: AheadNote, kind: MealReminderKind = MealReminderKind.EVENING) =
        MealReminder(kind, 100, listOf("Porridge", "Lemon chicken"), ahead.toList(), 2)

    @Test fun theTitleNamesTheMealsAfterTheWordForTomorrowOrTonight() {
        assertEquals(
            "Tomorrow: Porridge, Lemon chicken",
            MealReminderText.title(reminder(), { "Tomorrow: $it" }, { "Tonight: $it" })
        )
        assertEquals(
            "Tonight: Porridge, Lemon chicken",
            MealReminderText.title(reminder(kind = MealReminderKind.TONIGHT), { "Tomorrow: $it" }, { "Tonight: $it" })
        )
    }

    @Test fun theBodyQuotesTheAheadStep() {
        val one = AheadNote(2, "Lemon chicken", "Marinate at least 8 hours or overnight.")
        val two = AheadNote(3, "Beans", "Soak the beans for 8 hours.")
        fun body(r: MealReminder) = MealReminderText.body(r, { "To do ahead: “$it”" }, "To do ahead:") { t, q -> "$t: “$q”" }
        assertNull(body(reminder()))
        assertEquals("To do ahead: “Marinate at least 8 hours or overnight.”", body(reminder(one)))
        assertEquals(
            "To do ahead:\nLemon chicken: “Marinate at least 8 hours or overnight.”\nBeans: “Soak the beans for 8 hours.”",
            body(reminder(one, two))
        )
    }
}
