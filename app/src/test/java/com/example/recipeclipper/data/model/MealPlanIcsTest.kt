package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPlanIcsTest {

    // 2026-09-23, a Wednesday; stamped 14:25:00 UTC that day.
    private val wednesday = 20_719L
    private val stamp = wednesday * PlanDays.MILLIS_PER_DAY + (14 * 3600 + 25 * 60) * 1000L
    private val types = mapOf(1L to "Lunch", 3L to "Dinner")

    private fun recipe(id: Long, day: Long, title: String, uid: String = "u$id") =
        PlannedMeal(id, day, 3, recipeId = 7, title = title, imageUrl = null, servings = 4, note = null, uid = uid)

    private fun note(id: Long, day: Long, text: String) =
        PlannedMeal(id, day, 1, recipeId = null, title = null, imageUrl = null, servings = null, note = text, uid = "u$id")

    @Test
    fun `a week becomes all-day events, one per meal, with CRLF lines`() {
        val ics = MealPlanIcs.calendar(
            listOf(note(1, wednesday, "Leftovers"), recipe(2, wednesday, "Chicken Adobo")),
            types, stamp
        )
        val expected = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Recipe Clipper//Meal plan//EN",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "BEGIN:VEVENT",
            "UID:u1@recipe-clipper",
            "DTSTAMP:20260923T142500Z",
            "DTSTART;VALUE=DATE:20260923",
            "DTEND;VALUE=DATE:20260924",
            "SUMMARY:Lunch · Leftovers",
            "TRANSP:TRANSPARENT",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "UID:u2@recipe-clipper",
            "DTSTAMP:20260923T142500Z",
            "DTSTART;VALUE=DATE:20260923",
            "DTEND;VALUE=DATE:20260924",
            "SUMMARY:Dinner · Chicken Adobo",
            "TRANSP:TRANSPARENT",
            "END:VEVENT",
            "END:VCALENDAR"
        ).joinToString("\r\n", postfix = "\r\n")
        assertEquals(expected, ics)
    }

    @Test
    fun `the last day of a month and year ends on the next`() {
        val ics = MealPlanIcs.calendar(listOf(recipe(1, PlanDays.epochDay(2026, 12, 31), "Soup")), types, stamp)
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261231\r\nDTEND;VALUE=DATE:20270101\r\n"))
    }

    @Test
    fun `an entry without a uid falls back to its row id`() {
        val ics = MealPlanIcs.calendar(listOf(recipe(9, wednesday, "Soup", uid = "")), types, stamp)
        assertTrue(ics.contains("UID:entry-9@recipe-clipper\r\n"))
    }

    @Test
    fun `an unnamed meal type leaves only the title`() {
        assertEquals("Soup", MealPlanIcs.summary(recipe(1, wednesday, " Soup "), " "))
    }

    @Test
    fun `text is escaped and long lines fold at 75 octets, never inside a character`() {
        assertEquals("SUMMARY:a\\, b\\; c\\\\d\\ne", MealPlanIcs.contentLine("SUMMARY", "a, b; c\\d\r\ne"))
        val long = MealPlanIcs.contentLine("SUMMARY", "é".repeat(60))
        val lines = long.split("\r\n")
        assertTrue(lines.drop(1).all { it.startsWith(" ") })
        assertTrue(lines.all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertEquals("SUMMARY:" + "é".repeat(60), lines.joinToString("") { it.removePrefix(" ") })
    }

    @Test
    fun `the file is named for the week's first day`() {
        assertEquals("meal-plan-2026-09-21.ics", MealPlanIcs.fileName(wednesday - 2))
    }
}
