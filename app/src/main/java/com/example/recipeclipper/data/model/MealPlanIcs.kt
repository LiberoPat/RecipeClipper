package com.example.recipeclipper.data.model

/**
 * A week of the meal plan as an iCalendar file (RFC 5545), for "Share as calendar file" (#52).
 * Pure: meals in, text out; identical to iOS's `MealPlanIcs`, and pinned by the differential
 * corpus's `Ics` rows.
 *
 * **Every meal is an all-day event** on its day: meal types have no times (and the user's own
 * types can be anything), so a timed event would invent one. The summary is the meal type and
 * the recipe title or the note ("Dinner · Chicken Adobo"). Events are `TRANSPARENT`, so a
 * calendar doesn't show the day as busy. The UID is the entry's stable uid, so sharing the same
 * week again updates the events in a calendar that honours UIDs instead of adding twins.
 */
object MealPlanIcs {

    const val MIME_TYPE = "text/calendar"
    const val PRODUCT_ID = "-//Recipe Clipper//Meal plan//EN"
    private const val CRLF = "\r\n"

    /** RFC 5545's limit on a content line, in octets, before it is folded. */
    private const val LINE_OCTETS = 75

    /** The calendar for [meals] (in plan order), named by [mealTypeNames]; [stampMillis] is
     *  when it was made (each event's DTSTAMP). */
    fun calendar(meals: List<PlannedMeal>, mealTypeNames: Map<Long, String>, stampMillis: Long): String {
        val stamp = timestamp(stampMillis)
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:$PRODUCT_ID",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH"
        )
        for (meal in meals) {
            lines += "BEGIN:VEVENT"
            lines += contentLine("UID", uid(meal))
            lines += "DTSTAMP:$stamp"
            lines += "DTSTART;VALUE=DATE:${date(meal.day)}"
            lines += "DTEND;VALUE=DATE:${date(meal.day + 1)}"
            lines += contentLine("SUMMARY", summary(meal, mealTypeNames[meal.mealTypeId].orEmpty()))
            lines += "TRANSP:TRANSPARENT"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString(CRLF, postfix = CRLF)
    }

    /** "meal-plan-2026-09-21.ics", for the week starting on [weekStart]. */
    fun fileName(weekStart: Long): String {
        val (year, month, day) = PlanDays.civil(weekStart)
        return "meal-plan-${pad(year, 4)}-${pad(month)}-${pad(day)}.ics"
    }

    /** "Dinner · Chicken Adobo", or just the title or note if the type has no name. */
    fun summary(meal: PlannedMeal, mealTypeName: String): String =
        listOf(mealTypeName.trim(), (meal.title ?: meal.note).orEmpty().trim())
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    /** `NAME:value`, the value escaped as TEXT and the line folded at 75 octets. */
    fun contentLine(name: String, value: String): String = fold("$name:${escape(value)}")

    private fun uid(meal: PlannedMeal): String =
        meal.uid.ifEmpty { "entry-${meal.id}" } + "@recipe-clipper"

    /** RFC 5545 TEXT: backslash, semicolon, comma and newline are escaped; a CR is dropped. */
    private fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(c)
            }
        }
    }

    /**
     * Splits [line] into lines of at most 75 UTF-8 octets, each continuation starting with a
     * space (which counts toward its 75). Never inside a character: a code point is whole.
     */
    private fun fold(line: String): String {
        val out = StringBuilder()
        var octets = 0
        var i = 0
        while (i < line.length) {
            val codePoint = line.codePointAt(i)
            val size = utf8Size(codePoint)
            if (octets + size > LINE_OCTETS) {
                out.append(CRLF).append(' ')
                octets = 1
            }
            out.appendCodePoint(codePoint)
            octets += size
            i += Character.charCount(codePoint)
        }
        return out.toString()
    }

    private fun utf8Size(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    /** Zero-padded ASCII digits, never the locale's (String.format would use them). */
    private fun pad(n: Number, width: Int = 2): String = n.toString().padStart(width, '0')

    /** 20260923 */
    private fun date(day: Long): String {
        val (year, month, dayOfMonth) = PlanDays.civil(day)
        return pad(year, 4) + pad(month) + pad(dayOfMonth)
    }

    /** 20260923T142500Z, in UTC. */
    private fun timestamp(millis: Long): String {
        val day = Math.floorDiv(millis, PlanDays.MILLIS_PER_DAY)
        val seconds = Math.floorMod(millis, PlanDays.MILLIS_PER_DAY) / 1000
        return date(day) + "T" + pad(seconds / 3600) + pad(seconds / 60 % 60) + pad(seconds % 60) + "Z"
    }
}
