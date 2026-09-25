package com.example.recipeclipper.data.model

import java.util.Locale

/** Which sentence a reminder says: what expires on its day, the next day, or both. */
enum class ExpiryReminderText { TODAY, TOMORROW, TODAY_AND_TOMORROW }

/**
 * One morning's pantry reminder (#52): on [day] (an epoch day) at [ExpiryReminders.HOUR], the
 * in-stock items expiring that day ([today]) and the day after ([tomorrow]), each A–Z.
 */
data class ExpiryReminder(val day: Long, val today: List<String>, val tomorrow: List<String>) {
    val text: ExpiryReminderText
        get() = when {
            today.isEmpty() -> ExpiryReminderText.TOMORROW
            tomorrow.isEmpty() -> ExpiryReminderText.TODAY
            else -> ExpiryReminderText.TODAY_AND_TOMORROW
        }
}

/**
 * When the pantry's expiry reminders are due, and what they list (#52). Pure: the platforms
 * schedule what [plan] returns (Android one alarm for the first, iOS every one) and word it.
 *
 * One reminder per morning at most, never one per item. Only items in stock, not a staple
 * ("Always have") and with a use-by date count: an item that's out has nothing to use up, a
 * staple is restocked without thinking, and an undated one has no date to warn about.
 */
object ExpiryReminders {

    /** The reminder's local time: 9:00. */
    const val HOUR = 9

    /** At most this many are scheduled ahead: iOS keeps only 64 pending notifications per app,
     *  which step timers share. A month of mornings is plenty; the next plan extends it. */
    const val MAX_REMINDERS = 30

    fun counts(item: PantryItem): Boolean = item.inStock && !item.alwaysHave && item.expiresDay != null

    /** The reminder for the morning of [day], or null when nothing expires that day or the next. */
    fun forDay(items: List<PantryItem>, day: Long): ExpiryReminder? {
        val counted = items.filter(::counts)
        val today = names(counted.filter { it.expiresDay == day })
        val tomorrow = names(counted.filter { it.expiresDay == day + 1 })
        if (today.isEmpty() && tomorrow.isEmpty()) return null
        return ExpiryReminder(day, today, tomorrow)
    }

    /**
     * Every reminder still to come, soonest first: [today]'s only while [minuteOfDay] (minutes
     * since local midnight) is before [HOUR], then each later morning with something expiring
     * that day or the next. An item already past its date has no reminder left.
     */
    fun plan(items: List<PantryItem>, today: Long, minuteOfDay: Int): List<ExpiryReminder> {
        val first = if (minuteOfDay < HOUR * 60) today else today + 1
        return items.filter(::counts)
            .flatMap { listOf(it.expiresDay!! - 1, it.expiresDay) }
            .filter { it >= first }
            .distinct()
            .sorted()
            .take(MAX_REMINDERS)
            .mapNotNull { forDay(items, it) }
    }

    /** "milk", "milk and eggs", "milk, eggs and yogurt": [and] is the translated "%1$s and %2$s". */
    fun joinNames(names: List<String>, and: (String, String) -> String): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> and(names.dropLast(1).joinToString(", "), names.last())
    }

    /** The sentence as it opens a notification: its first letter in upper case. */
    fun capitalized(sentence: String, locale: Locale): String =
        sentence.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    private fun names(items: List<PantryItem>): List<String> =
        items.map { it.name.trim() }.distinct().sortedWith(compareBy({ it.lowercase(Locale.ROOT) }, { it }))
}
