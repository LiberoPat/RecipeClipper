package com.example.recipeclipper.data.model

/**
 * One To buy row that can wait for a second shop (#396): [row] is the week's ingredient,
 * [group] the perishable group it matched, and [firstDay] the earliest day (an epoch day) any
 * of its lines is planned for. The screen says that day under the row.
 */
data class LaterRow(val row: NeedRow, val group: PerishableGroup, val firstDay: Long)

/** Shop for this week's To buy, in two trips (#396): [now] first, then [later]. */
data class ShopTrips(val now: List<NeedRow>, val later: List<LaterRow>)

/**
 * Splits To buy into "Buy now" and "Buy later in the week" (#396). Pure, and the same on iOS.
 *
 * A row goes **later** only when both hold, and everything else is **now**:
 *  - its ingredient matches the perishables table ([Perishables.groupOf], read with the
 *    recipe's own language), and
 *  - **every** planned line of it is for a meal at least [Perishables.LATER_DAYS] days from
 *    today. A line with no day, a name the app can't read, or one meal sooner keeps the whole
 *    row in Buy now: nothing is guessed.
 *
 * Quantities are never touched: the rows are the ones What I need already shows, in the same
 * order.
 */
object FreshShop {

    fun split(buy: List<NeedRow>, today: Long): ShopTrips {
        val now = ArrayList<NeedRow>()
        val later = ArrayList<LaterRow>()
        for (row in buy) {
            val group = laterGroup(row, today)
            if (group == null) now += row else later += LaterRow(row, group, row.lines.minOf { it.day!! })
        }
        return ShopTrips(now, later)
    }

    private fun laterGroup(row: NeedRow, today: Long): PerishableGroup? {
        val name = row.name ?: return null
        if (row.lines.isEmpty() || row.lines.any { it.day == null || it.day - today < Perishables.LATER_DAYS }) return null
        return Perishables.groupOf(name, LanguageWords.forTag(row.lines.first().language))
    }
}

/**
 * One planned meal as the plan nudge sees it (#396): [key] is whatever the caller swaps by (a plan
 * entry's id, a review row's index), [day] its epoch day, [perishable] the most delicate
 * perishable ingredient of its recipe ([Perishables.mostDelicate]), null for a note or a recipe
 * with none.
 */
data class FreshMeal(val key: Long, val day: Long, val perishable: Perishable?)

/**
 * "[name]: keeps about [keeps] days once bought": the meal [key], on [day], has a perishable
 * ingredient and sits later than that keeps from the plan's first day; swapping it with the meal
 * [swapKey], on [swapDay], puts it back inside. [name] has a capital first letter.
 */
data class FreshNudge(
    val key: Long,
    val name: String,
    val keeps: String,
    val day: Long,
    val swapKey: Long,
    val swapDay: Long
)

/**
 * The plan nudge (#396): a quiet note when a perishable meal is planned later than its group keeps,
 * counted from the plan's first day, with the one swap that fixes it. Pure; **it only says it**: the
 * caller moves nothing unless the cook taps.
 *
 * The first day is the earliest planned day that is not before [today] (the cook shops once,
 * before the plan starts, and can't have bought earlier than today); a meal before today is
 * left out. A meal is **late** when its day is more than its group's `maxDays` after the first
 * day: the nudge uses the group's long end, so the note speaks only when even the generous reading
 * says it will have gone. Its swap partner is the earliest-day other meal (a recipe or a note, in the given
 * order on a tie) whose day is within `maxDays` of the first day, and that can itself take the later day:
 * a partner with a perishable of its own must keep that long. With no such partner there is no nudge,
 * since there would be nothing to tap.
 */
object FreshPlan {

    fun nudges(meals: List<FreshMeal>, today: Long): List<FreshNudge> {
        val live = meals.filter { it.day >= today }
        val first = live.minOfOrNull { it.day } ?: return emptyList()
        val byDay = live.sortedBy { it.day }
        return live.mapNotNull { meal ->
            val perishable = meal.perishable ?: return@mapNotNull null
            val keeps = perishable.group.maxDays
            if (meal.day - first <= keeps) return@mapNotNull null
            val partner = byDay.firstOrNull { other ->
                other.key != meal.key && other.day < meal.day && other.day - first <= keeps &&
                    (other.perishable == null || meal.day - first <= other.perishable.group.maxDays)
            } ?: return@mapNotNull null
            FreshNudge(
                key = meal.key,
                name = perishable.name.replaceFirstChar { it.uppercaseChar() },
                keeps = perishable.group.keeps,
                day = meal.day,
                swapKey = partner.key,
                swapDay = partner.day
            )
        }
    }
}
