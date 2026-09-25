package com.example.recipeclipper.data.model

import java.util.Locale

/**
 * One thing in the pantry (#51). [name] is what the user typed (or the ingredient name of a
 * grocery line they ticked off), read with [language]'s words (null: none, so it never
 * matches). [quantity] is free text as written ("half a bag"), never read as a number.
 * [alwaysHave] marks a staple (salt, oil…): it is never on a Buy list. [purchasedDay] and
 * [expiresDay] are epoch days ([PlanDays]).
 */
data class PantryItem(
    val id: Long,
    val name: String,
    val quantity: String?,
    val language: String?,
    val aisle: Aisle,
    val inStock: Boolean,
    val alwaysHave: Boolean,
    val purchasedDay: Long?,
    val expiresDay: Long?
)

/** A new pantry item, before it has an id. It starts in stock, bought on [purchasedDay]. */
data class NewPantryItem(
    val name: String,
    val language: String?,
    val aisle: Aisle? = null,
    val quantity: String? = null,
    val purchasedDay: Long? = null
)

/** What the edit sheet can change. */
data class PantryEdit(
    val name: String,
    val quantity: String?,
    val alwaysHave: Boolean,
    val expiresDay: Long?
)

/** The expiry badge: no notifications, just a word on the row. */
enum class ExpiryBadge { EXPIRED, SOON }

enum class PantrySort { AISLE, EXPIRY }

/** The pantry as shown: one section per aisle, or (by expiry) one section with no aisle. */
data class PantrySection(val aisle: Aisle?, val items: List<PantryItem>)

/** Sorting, searching and the expiry badge. Pure. */
object PantryList {

    /** "Soon" is today and the next [SOON_DAYS] days. */
    const val SOON_DAYS = 3

    fun badge(expiresDay: Long?, today: Long): ExpiryBadge? = when {
        expiresDay == null -> null
        expiresDay < today -> ExpiryBadge.EXPIRED
        expiresDay <= today + SOON_DAYS -> ExpiryBadge.SOON
        else -> null
    }

    /**
     * [items] matching [query] (a case-insensitive part of the name; blank matches all), sorted:
     * by aisle, aisles in [Aisle] order and names A–Z within; or by expiry, soonest first, then
     * the items with no date, A–Z.
     */
    fun arrange(items: List<PantryItem>, query: String, sort: PantrySort): List<PantrySection> {
        val q = query.trim().lowercase(Locale.ROOT)
        val found = items.filter { q.isEmpty() || it.name.lowercase(Locale.ROOT).contains(q) }
        if (found.isEmpty()) return emptyList()
        val byName = compareBy<PantryItem> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
        return when (sort) {
            PantrySort.AISLE -> found.groupBy { it.aisle }.toSortedMap(compareBy { it.ordinal })
                .map { (aisle, inAisle) -> PantrySection(aisle, inAisle.sortedWith(byName)) }
            PantrySort.EXPIRY -> listOf(
                PantrySection(
                    null,
                    found.sortedWith(compareBy<PantryItem> { it.expiresDay == null }.thenBy { it.expiresDay }.then(byName))
                )
            )
        }
    }

    /** The item already here with this name (trimmed, case-insensitive) in [language], if any. */
    fun sameName(items: List<PantryItem>, name: String, language: String?): PantryItem? {
        val key = name.trim().lowercase(Locale.ROOT)
        return items.firstOrNull { it.language == language && it.name.trim().lowercase(Locale.ROOT) == key }
    }
}

/** Have or Buy, for one ingredient of the week (#51). */
enum class NeedStatus {
    /** An in-stock pantry item has this name. Presence only: "you have flour", never "enough". */
    HAVE,

    /** A staple ("always have"): never on the Buy list, in stock or not. */
    STAPLE,

    BUY
}

/** One planned recipe's line, as the reading view renders it at the planned servings. */
data class NeedLine(
    val text: String,
    val recipeId: Long,
    val title: String,
    val day: Long?,
    val language: String?
)

/**
 * One ingredient of the week: every line naming it (exactly the same [IngredientName], in the
 * same language), and whether the pantry has it. [name] is null for a line the app can't name
 * (a heading, "salt and pepper"): it stands alone and is always Buy, since nothing is guessed.
 * [pantryName] is the matched pantry item's name.
 */
data class NeedRow(
    val name: String?,
    val lines: List<NeedLine>,
    val status: NeedStatus,
    val pantryName: String?
)

data class WeekNeeds(val buy: List<NeedRow>, val have: List<NeedRow>) {
    val isEmpty: Boolean get() = buy.isEmpty() && have.isEmpty()
}

/**
 * The pantry against a recipe's lines (#51). A line's name ([IngredientName.of]) matches a
 * pantry item's by [IngredientName.matches] ("unsalted butter" is "butter"; "rice flour" is
 * never "flour", either way), and only in the same language. It answers "is it there", never
 * "is there enough".
 */
object PantryMatch {

    /**
     * The pantry item tracking [name] in [language], or null when none is: a matching staple
     * first, else one in stock, else one that's out. [status] turns it into Have or Buy.
     */
    fun find(name: String, language: String?, pantry: List<PantryItem>): PantryItem? {
        val words = LanguageWords.forTag(language) ?: return null
        val matching = pantry.filter { it.language == words.language && IngredientName.matches(name, it.name, words) }
        return matching.firstOrNull { it.alwaysHave } ?: matching.firstOrNull { it.inStock } ?: matching.firstOrNull()
    }

    fun status(item: PantryItem?): NeedStatus = when {
        item == null -> NeedStatus.BUY
        item.alwaysHave -> NeedStatus.STAPLE
        item.inStock -> NeedStatus.HAVE
        else -> NeedStatus.BUY
    }

    /** True when [line] needn't be bought: its ingredient is in stock, or a staple. */
    fun covered(line: String, language: String?, pantry: List<PantryItem>): Boolean {
        val words = LanguageWords.forTag(language) ?: return false
        val name = IngredientName.of(line, words) ?: return false
        return status(find(name, words.language, pantry)) != NeedStatus.BUY
    }

    /**
     * The week's "What I need": every planned recipe's lines ([sources], already rendered),
     * grouped by ingredient in the order first met, split into Buy and Have. Staples are with
     * Have, never Buy.
     */
    fun weekNeeds(sources: List<GrocerySource>, pantry: List<PantryItem>): WeekNeeds {
        val groups = LinkedHashMap<Any, MutableList<NeedLine>>()
        val names = HashMap<Any, String?>()
        var unnamed = 0
        for (source in sources) {
            val words = LanguageWords.forTag(source.language)
            for (text in source.lines) {
                val line = NeedLine(text, source.recipeId, source.title, source.day, source.language)
                val name = words?.let { IngredientName.of(text, it) }
                val key: Any = if (name == null) unnamed++ else (source.language to name)
                groups.getOrPut(key) { mutableListOf() }.add(line)
                names[key] = name
            }
        }
        val rows = groups.map { (key, lines) ->
            val name = names[key]
            val item = name?.let { find(it, lines.first().language, pantry) }
            NeedRow(name, lines, status(item), item?.name)
        }
        return WeekNeeds(
            buy = rows.filter { it.status == NeedStatus.BUY },
            have = rows.filter { it.status != NeedStatus.BUY }
        )
    }
}
