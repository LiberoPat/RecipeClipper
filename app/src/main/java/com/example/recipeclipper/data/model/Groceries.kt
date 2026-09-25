package com.example.recipeclipper.data.model

import kotlin.math.abs
import kotlin.math.max

/**
 * The aisles the grocery list is grouped by (#50), in the order it shows them. [key] is what
 * `grocery_items.aisle` stores and what `shared/tables/<language>/aisles.json` names; the words
 * shown for each are UI strings. An unknown stored key reads as [OTHER].
 */
enum class Aisle(val key: String) {
    PRODUCE("produce"),
    MEAT("meat"),
    SEAFOOD("seafood"),
    DAIRY("dairy"),
    BAKERY("bakery"),
    BAKING("baking"),
    GRAINS("grains"),
    CANNED("canned"),
    CONDIMENTS("condiments"),
    SPICES("spices"),
    FROZEN("frozen"),
    SNACKS("snacks"),
    DRINKS("drinks"),
    OTHER("other");

    companion object {
        fun fromKey(key: String?): Aisle = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/**
 * Which aisle a grocery line belongs in: its ingredient name ([IngredientName.of]) matched on
 * its end against the language's `aisles.json`, like the density table, so "unsalted butter"
 * is dairy, "peanut butter" condiments and "butter beans" canned. A line with no name, a name
 * nothing matches, or a language with no words is [Aisle.OTHER].
 */
object Aisles {

    private class Table(words: LanguageWords) {
        // Longest alias first, so the most specific one wins.
        val aliases: List<Pair<String, Aisle>> = words.table("aisles").getJSONObject("aisles").let { aisles ->
            aisles.keys().asSequence().flatMap { key ->
                val aisle = Aisle.fromKey(key)
                SharedTables.strings(aisles.getJSONArray(key)).map { it to aisle }
            }.toList()
        }.sortedByDescending { it.first.length }
    }

    private fun table(words: LanguageWords): Table = words.compiled(Table::class) { Table(it) }

    fun of(line: String, words: LanguageWords?): Aisle {
        if (words == null) return Aisle.OTHER
        val name = IngredientName.of(line, words) ?: return Aisle.OTHER
        return ofName(name, words)
    }

    /** The aisle for a name as [IngredientName.of] gives it. */
    fun ofName(name: String, words: LanguageWords): Aisle =
        table(words).aliases.firstOrNull { (alias, _) -> IngredientDensities.endsWithName(name, alias, words.spaced) }
            ?.second ?: Aisle.OTHER
}

/**
 * One line on the grocery list (#50): [text] as written (a recipe's line as the reading view
 * showed it, scaled and converted, or what was typed), read with [language]'s words (null: a
 * language the app has none for, so it is never named or combined). [recipeId] and
 * [plannedDay] say where it came from, when it came from a recipe or the week.
 */
data class GroceryItem(
    val id: Long,
    val text: String,
    val language: String?,
    val aisle: Aisle,
    val checked: Boolean,
    val sortOrder: Int,
    val recipeId: Long? = null,
    val plannedDay: Long? = null
)

/**
 * How the grocery list is shown: grouped by aisle, and within an aisle, lines naming the same
 * ingredient kept together. Pure, and the rule behind "never a confident wrong number":
 *
 * - Lines with the same [IngredientName] (exactly, in the same language, checked or not alike)
 *   are one ingredient.
 * - They combine into one row ([Row.Combined]) only when every one is a single exact amount
 *   (no range, no "plus", no second measure, no package size) and all are in one family of
 *   units that convert exactly into each other: metric weight (g, kg), imperial weight (oz, lb),
 *   metric volume (ml, cl, dl, l, and the Japanese 200 ml cup and 180 ml rice cup), US volume
 *   (tsp, tbsp, fl oz, cup), sticks, or plain counts whose words after the number are identical
 *   ("2 eggs" and "3 eggs"). Grams never add to ounces, nor cups to grams.
 * - The total is written in one of the units the lines used, the largest that shows it exactly
 *   ("1 cup" + "2 tbsp" is "1 1/8 cup"); if none can, nothing is combined.
 * - Otherwise they sit together under their name ([Row.Together]), each as written.
 *
 * Japanese lines (amount after the name) are never combined. A bare "oz" is a weight here,
 * never fl oz, so "8 oz milk" and "1 cup milk" stay apart.
 */
object GroceryCombiner {

    sealed class Row {
        abstract val items: List<GroceryItem>

        /** A line on its own. */
        data class Single(val item: GroceryItem) : Row() {
            override val items: List<GroceryItem> get() = listOf(item)
        }

        /** Several lines added up into [text]. */
        data class Combined(val name: String, val text: String, override val items: List<GroceryItem>) : Row()

        /** Several lines naming [name] that can't be added up honestly, shown each as written. */
        data class Together(val name: String, override val items: List<GroceryItem>) : Row()
    }

    data class Section(val aisle: Aisle, val rows: List<Row>)

    /**
     * The list as shown: aisles in [Aisle] order, empty ones left out; within one, unchecked
     * rows before checked ones, each in the order its first line was added.
     */
    fun sections(items: List<GroceryItem>): List<Section> {
        val sorted = items.sortedWith(compareBy({ it.sortOrder }, { it.id }))
        return Aisle.entries.mapNotNull { aisle ->
            val inAisle = sorted.filter { it.aisle == aisle }
            if (inAisle.isEmpty()) return@mapNotNull null
            val rows = listOf(false, true).flatMap { checked ->
                group(inAisle.filter { it.checked == checked })
            }
            Section(aisle, rows)
        }
    }

    private fun group(items: List<GroceryItem>): List<Row> {
        // Keyed by language and name; a line with no name is its own group.
        val groups = LinkedHashMap<Any, MutableList<GroceryItem>>()
        val names = HashMap<Long, String?>()
        for (item in items) {
            val name = LanguageWords.forTag(item.language)?.let { IngredientName.of(item.text, it) }
            names[item.id] = name
            val key: Any = if (name == null) item.id else (item.language to name)
            groups.getOrPut(key) { mutableListOf() } += item
        }
        return groups.values.map { lines ->
            val first = lines.first()
            val name = names[first.id]
            when {
                lines.size == 1 || name == null -> Row.Single(first)
                else -> {
                    val words = LanguageWords.forTag(first.language)
                    val total = words?.let { combine(lines.map { it.text }, it) }
                    if (total != null) Row.Combined(name, total, lines) else Row.Together(name, lines)
                }
            }
        }
    }

    // --- Adding up

    /** Units that convert exactly into each other, and each unit's size in the family's smallest. */
    private enum class Family { METRIC_WEIGHT, IMPERIAL_WEIGHT, METRIC_VOLUME, US_VOLUME, STICK, COUNT }

    private fun sizeOf(unit: MeasureUnit): Pair<Family, Double>? = when (unit) {
        MeasureUnit.G -> Family.METRIC_WEIGHT to 1.0
        MeasureUnit.KG -> Family.METRIC_WEIGHT to 1000.0
        MeasureUnit.OZ -> Family.IMPERIAL_WEIGHT to 1.0
        MeasureUnit.LB -> Family.IMPERIAL_WEIGHT to 16.0
        MeasureUnit.ML -> Family.METRIC_VOLUME to 1.0
        MeasureUnit.CL -> Family.METRIC_VOLUME to 10.0
        MeasureUnit.DL -> Family.METRIC_VOLUME to 100.0
        MeasureUnit.L -> Family.METRIC_VOLUME to 1000.0
        MeasureUnit.CUP_200 -> Family.METRIC_VOLUME to 200.0
        MeasureUnit.RICE_CUP -> Family.METRIC_VOLUME to 180.0
        MeasureUnit.TSP -> Family.US_VOLUME to 1.0
        MeasureUnit.TBSP -> Family.US_VOLUME to 3.0
        MeasureUnit.FL_OZ -> Family.US_VOLUME to 6.0
        MeasureUnit.CUP -> Family.US_VOLUME to 48.0
        MeasureUnit.STICK -> Family.STICK to 1.0
        MeasureUnit.VARIES -> null
    }

    /** One line's amount: [value] in [unit] ([unitText] as written), or a count of [rest]. */
    private class Amount(
        val family: Family,
        val value: Double,
        val unit: MeasureUnit?,
        val unitText: String,
        val rest: String
    )

    private val WHITESPACE = Regex("""\s+""")

    private fun amount(line: String, words: LanguageWords): Amount? {
        val p = IngredientScaler.patterns(words)
        if (p.amountAfterName || p.unreadable(line)) return null
        val c = UnitConverter.patterns(words)
        val lead = p.leading.find(line) ?: return null
        if (lead.groupValues[4].isNotEmpty()) return null // a range is no one figure
        val rest = line.substring(lead.range.last + 1)
        if (p.notAnAmount.containsMatchIn(rest)) return null
        val value = p.parse(lead.groupValues[2])?.takeIf { it > 0 } ?: return null

        val unitMatch = c.unitAtStart.find(rest)
        if (unitMatch == null) {
            // A count: "2 eggs". Only the very same words add up, and never a package size.
            val tail = rest.trim()
            if (tail.isEmpty() || !tail.first().isLetter() || '(' in tail || '/' in tail) return null
            return Amount(Family.COUNT, value, null, "", tail)
        }
        val unit = MeasureUnit.fromText(unitMatch.groupValues[1], words) ?: return null
        val (family, size) = sizeOf(unit) ?: return null
        val after = rest.substring(unitMatch.range.last + 1)
        // "1 cup plus 2 tbsp", "1 cup (120 g)", "1 cup/120 g": more than one figure.
        if (c.continuationAtStart.containsMatchIn(after)) return null
        val next = after.trimStart()
        if (next.startsWith("(") || next.startsWith("/")) return null
        return Amount(family, value * size, unit, unitMatch.groupValues[1].trim(), after.trim())
    }

    /**
     * The lines added up as one line ("300 g flour"), or null when they can't be added up
     * exactly, or don't all name the same ingredient in [words]' language. The words after the
     * total are the shortest any line wrote after its unit, as written ("200 g butter, softened"
     * and "100 g butter" are "300 g butter").
     */
    fun combine(lines: List<String>, words: LanguageWords): String? {
        if (lines.size < 2) return null
        val name = IngredientName.of(lines.first(), words) ?: return null
        if (lines.any { IngredientName.of(it, words) != name }) return null
        val amounts = lines.map { amount(it, words) ?: return null }
        val family = amounts.first().family
        if (amounts.any { it.family != family }) return null
        val comma = lines.any { IngredientScaler.DECIMAL_COMMA.containsMatchIn(it) }
        val total = amounts.sumOf { it.value }

        if (family == Family.COUNT) {
            val rest = amounts.first().rest
            if (amounts.any { normalize(it.rest) != normalize(rest) }) return null
            val text = exactly(total, metric = false, comma = comma) ?: return null
            return "$text $rest"
        }

        // The largest unit the lines used that shows the total exactly.
        val units = amounts.mapNotNull { it.unit }.distinct().sortedByDescending { sizeOf(it)!!.second }
        val rest = amounts.minBy { it.rest.length }.rest
        for (unit in units) {
            val value = total / sizeOf(unit)!!.second
            val text = exactly(value, unit.metric, comma) ?: continue
            return "$text ${unitText(amounts, unit, value)} $rest"
        }
        return null
    }

    /** [value] formatted as the scaler writes amounts, or null if that would round it. */
    private fun exactly(value: Double, metric: Boolean, comma: Boolean): String? {
        val plain = if (metric) IngredientScaler.formatMetric(value) else IngredientScaler.format(value)
        val shown = IngredientScaler.parse(plain) ?: return null
        if (abs(shown - value) > 1e-9 * max(1.0, abs(value))) return null
        return IngredientScaler.withSeparator(plain, comma)
    }

    // The unit as one of the lines wrote it: one whose amount agrees in number with the total
    // ("cups" for 3, "cup" for 1), else the first line in that unit.
    private fun unitText(amounts: List<Amount>, unit: MeasureUnit, total: Double): String {
        val size = sizeOf(unit)!!.second
        val same = amounts.filter { it.unit == unit }
        return (same.firstOrNull { (it.value / size > 1.0) == (total > 1.0) } ?: same.first()).unitText
    }

    /** A count's words, collapsed and lowercase, for comparing counts. */
    private fun normalize(text: String): String = WHITESPACE.replace(text.trim(), " ").lowercase()
}

/**
 * The grocery list as plain text for sharing (#50): the title, then each aisle's name and its
 * unchecked rows, as shown. A combined row is its total; lines kept together are each listed.
 * Checked items are left out: they're already in the basket. [aisleName] and [title] are the
 * screen's words.
 */
object GroceryShareText {

    fun format(sections: List<GroceryCombiner.Section>, title: String, aisleName: (Aisle) -> String): String {
        val lines = mutableListOf(title)
        for (section in sections) {
            val rows = section.rows.filter { row -> row.items.none { it.checked } }
            if (rows.isEmpty()) continue
            lines += ""
            lines += aisleName(section.aisle)
            for (row in rows) {
                when (row) {
                    is GroceryCombiner.Row.Single -> lines += "- ${row.item.text}"
                    is GroceryCombiner.Row.Combined -> lines += "- ${row.text}"
                    is GroceryCombiner.Row.Together -> row.items.forEach { lines += "- ${it.text}" }
                }
            }
        }
        return lines.joinToString("\n")
    }
}

/** A line to put on the list, and where it came from. [language] is the tag its words are read with. */
data class NewGroceryLine(
    val text: String,
    val language: String?,
    val recipeId: Long? = null,
    val plannedDay: Long? = null
)

/** A recipe planned on [day] at [servings] (null: its own yield), with what it needs. */
data class PlannedIngredients(
    val entryId: Long,
    val day: Long,
    val servings: Int?,
    val recipeId: Long,
    val title: String,
    val ingredients: List<String>,
    val yield: String?,
    val language: String?
)

/**
 * One recipe's lines in the "Add to groceries" sheet: [lines] as the reading view shows them,
 * read with [language]'s words, and from the week, the [day] it's planned on. [key] tells two
 * plannings of the same recipe apart.
 */
data class GrocerySource(
    val key: String,
    val recipeId: Long,
    val title: String,
    val day: Long?,
    val language: String?,
    val lines: List<String>
)

/** Builds the add sheet's sources (#50). Pure: lines in, lines out. */
object GrocerySources {

    /** A blank line or a heading ("For the sauce:") is nothing to buy, so it isn't offered. */
    fun buyable(line: String): Boolean = line.isNotBlank() && !line.trim().endsWith(":")

    /** A recipe on the reading view: [rendered] are its lines exactly as shown there. */
    fun fromRecipe(recipeId: Long, title: String, language: String?, rendered: List<String>): GrocerySource =
        GrocerySource("recipe-$recipeId", recipeId, title, null, language, rendered.filter(::buyable))

    /**
     * The week's planned recipes, each rendered as the reading view would show it at its planned
     * servings (scaled from the recipe's yield, then converted to [system]). A recipe with no
     * usable yield, or no planned servings, shows as written. One with nothing to buy is left out.
     */
    fun fromPlan(planned: List<PlannedIngredients>, system: UnitSystem, convertLiquids: Boolean): List<GrocerySource> =
        planned.mapNotNull { p ->
            val tag = p.language ?: LanguageWords.resolve(null, null) { LanguageWords.detectionText(p.title, p.ingredients) }
            val words = LanguageWords.forTag(tag)
            val base = Servings.parse(p.yield, words)
            val factor = if (base != null && p.servings != null) p.servings.toDouble() / base else 1.0
            val lines = IngredientRendering.render(p.ingredients, factor, system, convertLiquids, words).filter(::buyable)
            if (lines.isEmpty()) null
            else GrocerySource("plan-${p.entryId}", p.recipeId, p.title, p.day, words?.language, lines)
        }
}
