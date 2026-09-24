package com.example.recipeclipper.ui.common

/**
 * How long ago something happened, as a shape rather than a sentence. The wording lives in
 * `strings.xml` and is applied by [text]; keeping the decision here and the words there is
 * what makes this translatable, the same split `Servings.bareCount` uses.
 */
sealed class Elapsed {
    object JustNow : Elapsed()
    data class Minutes(val count: Int) : Elapsed()
    data class Hours(val count: Int) : Elapsed()
    object Yesterday : Elapsed()

    /** Two to six days. One day is [Yesterday], a week or more is [OnDate]. */
    data class Days(val count: Int) : Elapsed()

    /** A week or more ago, shown as a date rather than a count. */
    data class OnDate(val millis: Long) : Elapsed()
}

/** Pure: no Context, no wording, no formatting. */
object TimeAgo {

    fun since(then: Long, now: Long): Elapsed {
        val seconds = ((now - then) / 1000).coerceAtLeast(0)
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 60 -> Elapsed.JustNow
            minutes < 60 -> Elapsed.Minutes(minutes.toInt())
            hours < 24 -> Elapsed.Hours(hours.toInt())
            days == 1L -> Elapsed.Yesterday
            days < 7 -> Elapsed.Days(days.toInt())
            else -> Elapsed.OnDate(then)
        }
    }
}
