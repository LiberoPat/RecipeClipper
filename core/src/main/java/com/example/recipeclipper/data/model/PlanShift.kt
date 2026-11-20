package com.example.recipeclipper.data.model

/**
 * Skip tonight (#398): what skipping a planned meal does to the plan, as plain moves. Pure
 * integer arithmetic over epoch days ([PlanDays]); the repository applies the result in one
 * transaction, and iOS's `PlanShift` is the same logic line for line.
 *
 * [meals] are the planned meals in plan order (by day, then meal type order, then the order they
 * were added to their slot), as `observeDays` lists them. Notes are meals like any other: they
 * move and count as occupying their meal type.
 */
object PlanShift {

    /** What the cook chose in the Skip sheet. */
    enum class Choice {
        /** The skipped meal and every later planned meal go back a day, meal types kept. */
        PUSH_WEEK,

        /** The skipped meal moves to the first later day with no meal of its meal type. */
        NEXT_FREE_DAY,

        /** The skipped meal leaves the plan (the recipe stays). */
        REMOVE
    }

    /** [mealId] goes from [fromDay] to [toDay], its meal type unchanged. */
    data class Move(val mealId: Long, val fromDay: Long, val toDay: Long)

    /**
     * What a skip does: the meals [moved] (the seam for #396, which looks at where each ended
     * up), the meals [removed] from the plan, and the ids of the moved meals that now sit past
     * the last day the plan shows ([pastEnd], a subset of [moved]'s ids).
     */
    data class Result(
        val moved: List<Move>,
        val removed: List<Long>,
        val pastEnd: List<Long>
    )

    /** A meal can be skipped from today on; one planned in the past is history, never offered. */
    fun canSkip(meal: PlannedMeal, today: Long): Boolean = meal.day >= today

    /**
     * The moves for skipping the meal [skippedId] with [choice]. Null when it isn't among
     * [meals] or is in the past (before [today]). [lastDay] is the last day the plan shows: a
     * meal moved beyond it is reported in [Result.pastEnd]. Nothing before the skipped meal
     * ever moves; nothing else is changed.
     */
    fun plan(meals: List<PlannedMeal>, skippedId: Long, choice: Choice, today: Long, lastDay: Long): Result? {
        val index = meals.indexOfFirst { it.id == skippedId }
        if (index < 0) return null
        val skipped = meals[index]
        if (!canSkip(skipped, today)) return null

        val moved = when (choice) {
            Choice.PUSH_WEEK -> meals.filterIndexed { i, meal ->
                // Later days entirely, and on the skipped meal's own day only what comes at or
                // after it in plan order (the dinner follows a skipped lunch; a breakfast doesn't).
                meal.day > skipped.day || (meal.day == skipped.day && i >= index)
            }.map { Move(it.id, it.day, it.day + 1) }

            Choice.NEXT_FREE_DAY -> {
                val taken = meals.filter { it.mealTypeId == skipped.mealTypeId && it.day > skipped.day }.map { it.day }.toSet()
                var day = skipped.day + 1
                while (day in taken) day++
                listOf(Move(skipped.id, skipped.day, day))
            }

            Choice.REMOVE -> emptyList()
        }
        return Result(
            moved = moved,
            removed = if (choice == Choice.REMOVE) listOf(skipped.id) else emptyList(),
            pastEnd = moved.filter { it.toDay > lastDay }.map { it.mealId }
        )
    }
}
