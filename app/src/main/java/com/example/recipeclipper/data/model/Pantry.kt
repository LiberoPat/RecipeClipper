package com.example.recipeclipper.data.model

import java.util.Locale

/**
 * One thing in the pantry (#51). [name] is what the user typed (or the ingredient name of a
 * grocery line they ticked off), read with [language]'s words (null: none, so it never
 * matches). [quantity] is free text as written ("half a bag"), never read as a number.
 * [alwaysHave] marks a staple (salt, oil…): it is never on a Buy list. [purchasedDay] and
 * [expiresDay] are epoch days ([PlanDays]). [inStock] and [runningLow] make its [stock] (#194):
 * running low is still in stock, so everything that asks "is it there" reads [inStock].
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
    val expiresDay: Long?,
    val runningLow: Boolean = false
) {
    val stock: PantryStock
        get() = when {
            !inStock -> PantryStock.RUN_OUT
            runningLow -> PantryStock.RUNNING_LOW
            else -> PantryStock.IN_STOCK
        }
}

/**
 * A pantry item's three states (#194). In stock and running low both count as having it
 * (presence only, #51); running low and run out both put its name on the grocery list.
 */
enum class PantryStock { IN_STOCK, RUNNING_LOW, RUN_OUT }

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

/**
 * The pantry as shown: one section per aisle, or (by expiry) one section with no aisle, then
 * [runOut]: the items that have run out, in their own section at the bottom (#194).
 */
data class PantrySection(val aisle: Aisle?, val items: List<PantryItem>, val runOut: Boolean = false)

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
     * the items with no date, A–Z. Items that have run out are left out of those and follow in
     * one last section ([PantrySection.runOut]), in the same order (#194).
     */
    fun arrange(items: List<PantryItem>, query: String, sort: PantrySort): List<PantrySection> {
        val q = query.trim().lowercase(Locale.ROOT)
        val found = items.filter { q.isEmpty() || it.name.lowercase(Locale.ROOT).contains(q) }
        if (found.isEmpty()) return emptyList()
        val byName = compareBy<PantryItem> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
        val order = when (sort) {
            PantrySort.AISLE -> compareBy<PantryItem> { it.aisle.ordinal }.then(byName)
            PantrySort.EXPIRY -> compareBy<PantryItem> { it.expiresDay == null }.thenBy { it.expiresDay }.then(byName)
        }
        val (have, out) = found.partition { it.inStock }
        val sections = when (sort) {
            PantrySort.AISLE -> have.groupBy { it.aisle }.toSortedMap(compareBy { it.ordinal })
                .map { (aisle, inAisle) -> PantrySection(aisle, inAisle.sortedWith(byName)) }
            PantrySort.EXPIRY -> if (have.isEmpty()) emptyList() else listOf(PantrySection(null, have.sortedWith(order)))
        }
        return if (out.isEmpty()) sections else sections + PantrySection(null, out.sortedWith(order), runOut = true)
    }

    /**
     * The unticked grocery lines that are [item] itself: its name as the pantry puts it there
     * (trimmed, case-insensitive, a listed pair's number aside ([IngredientName.same]), in its
     * language), which is what "On list" means (#146). A recipe's "2 cups flour" isn't, so taking
     * the item off the list never loses a recipe's line.
     */
    fun ownLines(item: PantryItem, groceries: List<GroceryItem>): List<GroceryItem> {
        val words = LanguageWords.forTag(item.language)
        val name = IngredientName.key(item.name, words)
        return groceries.filter { !it.checked && it.language == item.language && IngredientName.key(it.text, words) == name }
    }

    /**
     * The item already here with this name (trimmed, case-insensitive, a listed pair's number
     * aside: "onion" finds "Onions") in [language], if any.
     */
    fun sameName(items: List<PantryItem>, name: String, language: String?): PantryItem? {
        val words = LanguageWords.forTag(language)
        val key = IngredientName.key(name, words)
        return items.firstOrNull { it.language == language && IngredientName.key(it.name, words) == key }
    }
}

/**
 * The pantry as plain text for sending (#149): what's in stock, for someone at the shops to see
 * what's at home. The title, then each section as the screen arranges it (an aisle's name, or
 * no heading when sorted by expiry) and its in-stock items, one per line after "- ": the name,
 * then the quantity as written in brackets ("- basmati rice (half a bag)"). Items that are out
 * are left out: running out already put them on the grocery list (#146), which sends its own.
 * [aisleName] and [title] are the screen's words.
 */
object PantryShareText {

    fun format(sections: List<PantrySection>, title: String, aisleName: (Aisle) -> String): String {
        val lines = mutableListOf(title)
        for (section in sections) {
            val items = section.items.filter { it.inStock }
            if (items.isEmpty()) continue
            lines += ""
            section.aisle?.let { lines += aisleName(it) }
            for (item in items) {
                val quantity = item.quantity?.trim().orEmpty()
                lines += if (quantity.isEmpty()) "- ${item.name.trim()}" else "- ${item.name.trim()} ($quantity)"
            }
        }
        return lines.joinToString("\n")
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
 * One ingredient of the week: every line naming it (exactly the same [IngredientName], a listed
 * pair's number aside, in the same language), and whether the pantry has it. [name] is the first
 * line's; it is null for a line the app can't name
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
     * first, else one in stock, else one that's out. [status] turns it into Have or Buy. Only
     * when no name matches, a staple or in-stock item the model said is the same (#104,
     * [decisions]) counts: its "same" can only turn Buy into Have, never the other way.
     */
    fun find(name: String, language: String?, pantry: List<PantryItem>, decisions: Decisions = Decisions.NONE): PantryItem? {
        val words = LanguageWords.forTag(language) ?: return null
        val sameLanguage = pantry.filter { it.language == words.language }
        val matching = sameLanguage.filter { IngredientName.matches(name, it.name, words) }
        if (matching.isNotEmpty()) {
            return matching.firstOrNull { it.alwaysHave } ?: matching.firstOrNull { it.inStock } ?: matching.first()
        }
        val same = sameLanguage.filter {
            (it.alwaysHave || it.inStock) && decisions.sameIngredient(name, it.name, words.language)
        }
        return same.firstOrNull { it.alwaysHave } ?: same.firstOrNull()
    }

    fun status(item: PantryItem?): NeedStatus = when {
        item == null -> NeedStatus.BUY
        item.alwaysHave -> NeedStatus.STAPLE
        item.inStock -> NeedStatus.HAVE
        else -> NeedStatus.BUY
    }

    /** True when [line] needn't be bought: its ingredient is in stock, or a staple. */
    fun covered(line: String, language: String?, pantry: List<PantryItem>, decisions: Decisions = Decisions.NONE): Boolean {
        val words = LanguageWords.forTag(language) ?: return false
        val name = IngredientName.of(line, words) ?: return false
        return status(find(name, words.language, pantry, decisions)) != NeedStatus.BUY
    }

    /**
     * The week's "What I need": every planned recipe's lines ([sources], already rendered),
     * grouped by ingredient in the order first met, split into Buy and Have. Staples are with
     * Have, never Buy.
     */
    fun weekNeeds(sources: List<GrocerySource>, pantry: List<PantryItem>, decisions: Decisions = Decisions.NONE): WeekNeeds {
        val groups = LinkedHashMap<Any, MutableList<NeedLine>>()
        val names = HashMap<Any, String?>()
        var unnamed = 0
        for (source in sources) {
            val words = LanguageWords.forTag(source.language)
            for (text in source.lines) {
                val line = NeedLine(text, source.recipeId, source.title, source.day, source.language)
                val name = words?.let { IngredientName.of(text, it) }
                // One ingredient whatever the number of a listed pair's words ("onion", "onions").
                val key: Any = if (name == null) unnamed++ else (source.language to IngredientName.key(name, words))
                groups.getOrPut(key) { mutableListOf() }.add(line)
                if (key !in names) names[key] = name
            }
        }
        val rows = groups.map { (key, lines) ->
            val name = names[key]
            val item = name?.let { find(it, lines.first().language, pantry, decisions) }
            NeedRow(name, lines, status(item), item?.name)
        }
        return WeekNeeds(
            buy = rows.filter { it.status == NeedStatus.BUY },
            have = rows.filter { it.status != NeedStatus.BUY }
        )
    }
}
