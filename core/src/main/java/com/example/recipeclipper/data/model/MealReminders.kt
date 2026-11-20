package com.example.recipeclipper.data.model

/** The two reminders about the plan (#397): the evening before, and the afternoon of. */
enum class MealReminderKind { EVENING, TONIGHT }

/** A planned recipe's step to start ahead of time, quoted: what the evening reminder adds. */
data class AheadNote(val recipeId: Long, val title: String, val quote: String)

/**
 * One reminder as it is worded: [day] is the day it fires (an epoch day), [titles] the meals it
 * names (recipe titles, or a note's text) in meal type order, [ahead] the planned recipes with a
 * step to start ahead of time (the evening reminder only), and [recipeId] the first planned
 * recipe, which a tap opens (null when every meal is a note).
 */
data class MealReminder(
    val kind: MealReminderKind,
    val day: Long,
    val titles: List<String>,
    val ahead: List<AheadNote>,
    val recipeId: Long?
)

/**
 * The evening-before and "Tonight" reminders (#397). Pure: the platforms schedule what [plan]
 * returns (Android arms one alarm per kind and builds the reminder when it fires, iOS schedules
 * every one) and word it ([MealReminderText]). iOS's `MealReminders` is the same.
 *
 * The evening reminder fires on the day before the meals it names, at the cook's time (default
 * [EVENING_MINUTE]); "Tonight" fires on the day itself (default [TONIGHT_MINUTE]) and names what
 * is left of it. Nothing planned, nothing sent.
 */
object MealReminders {

    /** 19:00, in minutes since local midnight. */
    const val EVENING_MINUTE = 19 * 60

    /** 16:00. */
    const val TONIGHT_MINUTE = 16 * 60

    /** The most days ahead one kind is planned for: iOS keeps only 64 pending notifications per app. */
    const val MAX_DAYS = 7

    /** The day whose meals a reminder firing on [fireDay] names: tomorrow's for the evening, today's for Tonight. */
    fun mealDay(kind: MealReminderKind, fireDay: Long): Long =
        if (kind == MealReminderKind.EVENING) fireDay + 1 else fireDay

    /** The first day a reminder at [atMinute] can still fire: today while it is earlier than that, else tomorrow. */
    fun firstDay(today: Long, minuteOfDay: Int, atMinute: Int): Long =
        if (minuteOfDay < atMinute) today else today + 1

    /**
     * What a reminder firing on [fireDay] names, in meal type order (Breakfast before Dinner, as
     * [types] are sorted), then in the order the plan holds them. A meal with neither a title
     * nor a note (a recipe that's gone) is left out. "Tonight" leaves out Breakfast and Lunch,
     * which are over by the afternoon (by their `builtInKey`, so a rename doesn't matter).
     */
    fun meals(kind: MealReminderKind, fireDay: Long, meals: List<PlannedMeal>, types: List<MealType>): List<PlannedMeal> {
        val day = mealDay(kind, fireDay)
        val order = types.sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .mapIndexed { index, type -> type.id to index }.toMap()
        val over = types.filter { it.builtInKey == "breakfast" || it.builtInKey == "lunch" }.map { it.id }.toSet()
        return meals.filter { it.day == day }
            .filter { kind == MealReminderKind.EVENING || it.mealTypeId !in over }
            .filter { !label(it).isNullOrEmpty() }
            .sortedBy { order[it.mealTypeId] ?: Int.MAX_VALUE }
    }

    /** The recipes [meals] is about, once each: the ones whose steps the evening reminder reads. */
    fun recipeIds(meals: List<PlannedMeal>): List<Long> = meals.mapNotNull { it.recipeId }.distinct()

    private fun label(meal: PlannedMeal): String? = (meal.title ?: meal.note)?.trim()

    /**
     * The reminder firing on [fireDay], or null when it has nothing to name. [aheadFor] answers a
     * planned recipe's step to start ahead of time (the evening reminder only; "Tonight" is too
     * late for one).
     */
    fun build(
        kind: MealReminderKind,
        fireDay: Long,
        meals: List<PlannedMeal>,
        types: List<MealType>,
        aheadFor: (Long) -> AheadStep? = { null }
    ): MealReminder? {
        val named = meals(kind, fireDay, meals, types)
        if (named.isEmpty()) return null
        val ahead = if (kind != MealReminderKind.EVENING) emptyList() else {
            named.filter { it.recipeId != null }
                .distinctBy { it.recipeId }
                .mapNotNull { meal ->
                    aheadFor(meal.recipeId!!)?.let { AheadNote(meal.recipeId!!, label(meal)!!, it.quote) }
                }
        }
        return MealReminder(
            kind, fireDay,
            titles = named.map { label(it)!! }.distinct(),
            ahead = ahead,
            recipeId = named.firstNotNullOfOrNull { it.recipeId }
        )
    }

    /**
     * Every reminder of [kind] still to come, soonest first: the next [MAX_DAYS] days from the
     * first one at [atMinute] can fire (see [firstDay]) that have something to name. [meals] holds
     * the plan over those days' meals.
     */
    fun plan(
        kind: MealReminderKind,
        atMinute: Int,
        today: Long,
        minuteOfDay: Int,
        meals: List<PlannedMeal>,
        types: List<MealType>,
        aheadFor: (Long) -> AheadStep? = { null }
    ): List<MealReminder> {
        val first = firstDay(today, minuteOfDay, atMinute)
        return (first until first + MAX_DAYS).mapNotNull { build(kind, it, meals, types, aheadFor) }
    }
}

/**
 * The words of a reminder, from the platform's strings (the screen picks the words, never the
 * model). Each takes the string as a function of its parts, so the model stays free of resources.
 */
object MealReminderText {

    /** "Tomorrow: Porridge, Lemon chicken" / "Tonight: Lemon chicken". */
    fun title(reminder: MealReminder, tomorrow: (String) -> String, tonight: (String) -> String): String {
        val names = reminder.titles.joinToString(", ")
        return if (reminder.kind == MealReminderKind.EVENING) tomorrow(names) else tonight(names)
    }

    /**
     * What to do ahead, or null when nothing: one recipe's step as `one(quote)`; several as a
     * header line, then a line per recipe, `item(recipe, quote)`.
     */
    fun body(
        reminder: MealReminder,
        one: (String) -> String,
        header: String,
        item: (String, String) -> String
    ): String? = when (reminder.ahead.size) {
        0 -> null
        1 -> one(reminder.ahead[0].quote)
        else -> (listOf(header) + reminder.ahead.map { item(it.title, it.quote) }).joinToString("\n")
    }
}
