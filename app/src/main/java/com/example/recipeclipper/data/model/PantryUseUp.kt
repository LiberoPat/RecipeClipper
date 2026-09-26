package com.example.recipeclipper.data.model

import kotlin.math.max

/**
 * What cooking a recipe did to one pantry item (#147), as the end-of-cooking sheet offers it:
 * [item], the ticked [lines] that used it (as the recipe showed them: scaled and converted),
 * and the [change] worked out from them.
 */
data class UseUpRow(val item: PantryItem, val lines: List<String>, val change: UseUpChange)

sealed class UseUpChange {
    /**
     * The quantity worked out: [before] as stored, less the lines, is [after], written the same
     * way ("2 lb" is "1 lb"). [after] null: used up, so the item goes out of stock and onto the
     * grocery list.
     */
    data class Subtract(val before: String, val after: String?) : UseUpChange()

    /** How much was used can't be worked out: the cook picks keep, low or out. */
    data object Ask : UseUpChange()
}

/**
 * Using up the pantry when a recipe is cooked (#147). Pure: the ticked lines and the pantry in,
 * the changes out; nothing is written here, and nothing is guessed.
 *
 * - A line uses a pantry item when its [IngredientName] matches the item as Have/Buy does
 *   ([PantryMatch.find], with the model's definite "same" when there is one). Staples ("always
 *   have") and items already out are left alone. A line with no name, or matching nothing,
 *   isn't listed.
 * - The lines using one item are one row. Its amount is worked out only when the item's
 *   quantity and every line are one exact amount: no range, "plus", alternative, second amount
 *   after the name, package or piece ("2 cloves garlic"), and a count only when nothing but a
 *   size ("large", the names table's `countSizes`) stands between the number and the name.
 *   Anything else asks ([UseUpChange.Ask]).
 * - Only the same kind subtracts: a weight from a weight, a volume from a volume, a count from a
 *   count ("2 large eggs" from "6"). Volume and weight mix only through [IngredientDensities] or
 *   the line's own second measure ("1 cup (120 g) flour"), as the converter mixes them.
 * - Within one exact family ([GroceryCombiner.Family]: g/kg, oz/lb, the metric ml family, US
 *   tsp/tbsp/fl oz/cup, sticks) the result is exact: in the item's own unit when that shows it
 *   exactly, else in a unit a line used, else in g or ml. Otherwise (lb less grams, or through a
 *   density) it is rounded as the converter rounds its results: g/kg or ml/L for a metric
 *   quantity, oz/lb for an imperial weight; an imperial volume that isn't exact asks.
 * - At zero or below, or too little to show, the item is used up.
 */
object PantryUseUp {

    fun rows(
        lines: List<String>,
        language: String?,
        pantry: List<PantryItem>,
        decisions: Decisions = Decisions.NONE
    ): List<UseUpRow> {
        val words = LanguageWords.forTag(language) ?: return emptyList()
        val used = LinkedHashMap<Long, MutableList<String>>()
        val items = HashMap<Long, PantryItem>()
        for (line in lines) {
            if (!GrocerySources.buyable(line)) continue
            val name = IngredientName.of(line, words) ?: continue
            val item = PantryMatch.find(name, words.language, pantry, decisions) ?: continue
            if (item.alwaysHave || !item.inStock) continue
            items[item.id] = item
            used.getOrPut(item.id) { mutableListOf() } += line.trim()
        }
        return used.map { (id, itemLines) ->
            val item = items.getValue(id)
            UseUpRow(item, itemLines, change(item, itemLines, words))
        }
    }

    /** What [lines] do to [item]'s quantity, both read with [words]. */
    fun change(item: PantryItem, lines: List<String>, words: LanguageWords): UseUpChange {
        val quantity = item.quantity?.trim()?.takeIf { it.isNotEmpty() } ?: return UseUpChange.Ask
        val stock = read(quantity, words, item.name) ?: return UseUpChange.Ask
        val uses = lines.map { read(it, words, null) ?: return UseUpChange.Ask }
        return subtract(quantity, stock, uses) ?: UseUpChange.Ask
    }

    /**
     * One exact amount: [value] in [unit] (null: a count), [unitText] as written, the number and
     * unit at [start] until [end] of the text, [density] the ingredient's, and [site] a second
     * measure of the same amount the line wrote ("(120 g)"): its unit and quantity.
     */
    private class Amount(
        val value: Double,
        val unit: MeasureUnit?,
        val unitText: String,
        val start: Int,
        val end: Int,
        val comma: Boolean,
        val density: Density?,
        val site: Pair<MeasureUnit, Double>? = null
    )

    /**
     * The one exact amount [text] holds, or null. [pantryName] set: [text] is that pantry item's
     * quantity ("2 lb", "6", "6 eggs"), where a bare number counts the item; else a recipe line.
     */
    private fun read(text: String, words: LanguageWords, pantryName: String?): Amount? {
        val p = IngredientScaler.patterns(words)
        if (p.amountAfterName || p.unreadable(text)) return null
        // "1 cup butter or 1/2 cup oil", "2 eggs plus 3 yolks": more than one amount.
        if (IngredientScaler.sides(p, text)?.size != 1) return null
        val c = UnitConverter.patterns(words)
        val lead = p.leading.find(text) ?: return null
        if (lead.groupValues[4].isNotEmpty()) return null // a range is no one figure
        val afterNumber = lead.range.last + 1
        val rest = text.substring(afterNumber)
        if (p.notAnAmount.containsMatchIn(rest) || p.temperature(lead, rest)) return null
        val value = p.parse(lead.groupValues[2])?.takeIf { it > 0 } ?: return null
        val start = lead.groups[2]!!.range.first
        val comma = IngredientScaler.DECIMAL_COMMA.containsMatchIn(text)

        val unitMatch = c.unitAtStart.find(rest)
        if (unitMatch == null) {
            if (!GroceryCombiner.notesOnly(rest, c) || !countsItself(text, rest, words, pantryName)) return null
            return Amount(value, null, "", start, afterNumber, comma, null)
        }
        var unit = MeasureUnit.fromText(unitMatch.groupValues[1], words) ?: return null
        if (unit == MeasureUnit.VARIES) return null
        val end = afterNumber + unitMatch.range.last + 1
        var after = text.substring(end)
        // "1 cup plus 2 tbsp" is two amounts.
        if (c.continuationAtStart.containsMatchIn(after)) return null
        var site: Pair<MeasureUnit, Double>? = null
        if (pantryName == null) {
            secondMeasure(after, p, c)?.let { (measure, length) ->
                site = measure
                after = after.substring(length)
            }
        }
        if (!GroceryCombiner.notesOnly(after, c)) return null
        val density = IngredientDensities.find(pantryName ?: after, words)
        // A bare "oz" of a known liquid is fl oz, as the converter reads it.
        if (unit == MeasureUnit.OZ && density?.liquid == true) unit = MeasureUnit.FL_OZ
        if (unit == MeasureUnit.STICK && density?.stickable != true) return null
        return Amount(value, unit, unitMatch.groupValues[1].trim(), start, end, comma, density, site)
    }

    /**
     * "1 cup (120 g) flour", "1 cup/120 g flour": the same amount measured again, straight after
     * the unit. Its unit and quantity (null for a measure with no size, "(1 tasse)"), and how
     * much of [after] it took; null when there is none.
     */
    private fun secondMeasure(
        after: String,
        p: IngredientScaler.Patterns,
        c: UnitConverter.Patterns
    ): Pair<Pair<MeasureUnit, Double>?, Int>? {
        val open = after.indexOfFirst { !it.isWhitespace() }
        if (open >= 0 && after[open] == '(') {
            val close = after.indexOf(')', open)
            if (close < 0) return null
            val match = p.qtyUnit.matchEntire(after.substring(open + 1, close).trim()) ?: return null
            return measure(match.groupValues[1], match.groupValues[3], p) to close + 1
        }
        val slash = c.slashAtStart.find(after) ?: return null
        return measure(slash.groupValues[1], slash.groupValues[3], p) to slash.range.last + 1
    }

    private fun measure(quantity: String, unitText: String, p: IngredientScaler.Patterns): Pair<MeasureUnit, Double>? {
        val unit = MeasureUnit.fromText(unitText, p.words)?.takeIf { it != MeasureUnit.VARIES } ?: return null
        return unit to (p.parse(quantity) ?: return null)
    }

    private class CountSizes(words: LanguageWords) {
        val sizes = words.strings("names", "countSizes").toSet()
    }

    private val WHITESPACE = Regex("""\s+""")

    /**
     * True when a count counts the ingredient itself: nothing but sizes between the number and
     * the name ("2 large eggs"), never a part or a package ("2 cloves garlic", "1 can
     * tomatoes"). A pantry quantity may be a bare number, which counts its item.
     */
    private fun countsItself(text: String, rest: String, words: LanguageWords, pantryName: String?): Boolean {
        if (pantryName != null && rest.isBlank()) return true
        val name = IngredientName.of(text, words) ?: return false
        if (pantryName != null && !IngredientName.matches(name, pantryName, words)) return false
        val restWords = rest.trim().split(WHITESPACE).map { it.trimEnd(',', '.', ';', ':').lowercase() }
        val nameWords = name.split(' ')
        val at = (0..restWords.size - nameWords.size)
            .firstOrNull { restWords.subList(it, it + nameWords.size) == nameWords } ?: return false
        val sizes = words.compiled(CountSizes::class) { CountSizes(it) }.sizes
        return restWords.take(at).all { it in sizes }
    }

    private const val EPSILON = 1e-9

    private fun usedUp(left: Double, total: Double) = left <= EPSILON * max(1.0, total)

    /** [stock] less [uses], as a change to [quantity]; null when it can't be worked out. */
    private fun subtract(quantity: String, stock: Amount, uses: List<Amount>): UseUpChange? {
        val stockUnit = stock.unit
        if (stockUnit == null) {
            if (uses.any { it.unit != null }) return null
            val left = stock.value - uses.sumOf { it.value }
            if (usedUp(left, stock.value)) return UseUpChange.Subtract(quantity, null)
            val text = GroceryCombiner.exactly(left, metric = false, comma = stock.comma) ?: return null
            return UseUpChange.Subtract(quantity, replace(quantity, stock, text))
        }
        if (uses.any { it.unit == null }) return null

        // One family: exact arithmetic, in the family's smallest unit.
        val family = GroceryCombiner.sizeOf(stockUnit)?.first
        if (family != null && uses.all { GroceryCombiner.sizeOf(it.unit!!)?.first == family }) {
            val size = { unit: MeasureUnit -> GroceryCombiner.sizeOf(unit)!!.second }
            val total = stock.value * size(stockUnit)
            val left = total - uses.sumOf { it.value * size(it.unit!!) }
            if (usedUp(left, total)) return UseUpChange.Subtract(quantity, null)
            exact(left, family, stock, uses)?.let { return UseUpChange.Subtract(quantity, replace(quantity, stock, it)) }
            return rounded(quantity, stock, left * stockUnit.base / size(stockUnit))
        }

        // Across families, or through a density: grams or millilitres.
        val kind = stockUnit.kind
        val total = stock.value * stockUnit.base
        val left = total - uses.sumOf { base(it, kind, stock.density) ?: return null }
        if (usedUp(left, total)) return UseUpChange.Subtract(quantity, null)
        return rounded(quantity, stock, left)
    }

    /**
     * [left] (in [family]'s smallest unit) written exactly: in the stock's unit, else a unit a
     * line used (largest first), else g or ml; null when none shows it exactly.
     */
    private fun exact(left: Double, family: GroceryCombiner.Family, stock: Amount, uses: List<Amount>): String? {
        val metricBase = when (family) {
            GroceryCombiner.Family.METRIC_WEIGHT -> MeasureUnit.G
            GroceryCombiner.Family.METRIC_VOLUME -> MeasureUnit.ML
            else -> null
        }
        val used = uses.mapNotNull { it.unit }.distinct().sortedByDescending { GroceryCombiner.sizeOf(it)!!.second }
        val candidates = (listOf(stock.unit!!) + used + listOfNotNull(metricBase)).distinct()
        for (unit in candidates) {
            val value = left / GroceryCombiner.sizeOf(unit)!!.second
            // "700 g" rather than "0.7 kg"; a fraction below one ("1/2 lb") reads fine.
            if (unit.metric && value < 1 && unit != candidates.last()) continue
            val number = GroceryCombiner.exactly(value, unit.metric, stock.comma) ?: continue
            return "$number ${unitText(unit, value, stock, uses)}"
        }
        return null
    }

    // The unit as the quantity or a line wrote it, one agreeing in number with [value] ("cup"
    // for 1, "cups" for 2) when there is one; g or ml when neither wrote it.
    private fun unitText(unit: MeasureUnit, value: Double, stock: Amount, uses: List<Amount>): String {
        val written = (listOf(stock) + uses).filter { it.unit == unit }
        if (written.isEmpty()) return if (unit == MeasureUnit.G) "g" else "ml"
        return (written.firstOrNull { (it.value > 1.0) == (value > 1.0) } ?: written.first()).unitText
    }

    /**
     * [left] grams or millilitres written as the converter writes them in the stock's system: g/kg
     * or ml/L for a metric quantity, oz/lb for an imperial weight. Too little to show is used up;
     * an imperial volume can't be written without an exact figure, so it asks (null).
     */
    private fun rounded(quantity: String, stock: Amount, left: Double): UseUpChange? {
        val unit = stock.unit!!
        val text = when {
            unit.metric && unit.kind == MeasureKind.WEIGHT -> UnitConverter.metricText(left, null, "", "g", "kg")
            unit.metric -> UnitConverter.metricText(left, null, "", "ml", "L")
            unit == MeasureUnit.OZ || unit == MeasureUnit.LB -> UnitConverter.ounceText(left, null, "")
            else -> return null
        } ?: return UseUpChange.Subtract(quantity, null)
        return UseUpChange.Subtract(quantity, replace(quantity, stock, IngredientScaler.withSeparator(text, stock.comma)))
    }

    /** One line's amount in grams ([MeasureKind.WEIGHT]) or millilitres, or null if it can't be. */
    private fun base(use: Amount, kind: MeasureKind, fallback: Density?): Double? {
        val unit = use.unit!!
        if (unit.kind == kind) return use.value * unit.base
        // The line's own second measure first: "1 cup (120 g) flour" is 120 g.
        use.site?.let { (siteUnit, quantity) ->
            val effective = if (siteUnit == MeasureUnit.OZ && use.density?.liquid == true) MeasureUnit.FL_OZ else siteUnit
            if (effective.kind == kind) return quantity * effective.base
        }
        val gramsPerMl = (use.density ?: fallback)?.gramsPerCup?.let { it / MeasureUnit.CUP.base } ?: return null
        return when (kind) {
            MeasureKind.WEIGHT -> use.value * unit.base * gramsPerMl
            MeasureKind.VOLUME -> use.value * unit.base / gramsPerMl
            MeasureKind.NONE -> null
        }
    }

    private fun replace(quantity: String, stock: Amount, text: String): String =
        quantity.substring(0, stock.start) + text + quantity.substring(stock.end)
}
