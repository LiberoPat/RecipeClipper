package com.example.recipeclipper.data.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import kotlin.math.round

/**
 * Converts the leading amount of an ingredient line ("2 cups flour", "8 oz butter") into
 * another [UnitSystem]. Pure: string in, string out.
 *
 * Rules that keep it honest:
 * - Weight to weight (oz, lb, g, kg) is exact, and needs no lookup.
 * - Volume to weight needs [IngredientDensities]; an ingredient not in the table is left
 *   as written rather than guessed.
 * - If the line already carries the target unit in parentheses or after a slash, as in
 *   "1 cup (120 g) flour", the site's own figure is used instead of a calculated one. So is a
 *   range after a slash ("250 - 300 g / 8 - 10 oz pasta" in ounces is "8 - 10 oz pasta").
 * - Pourable liquids are left alone in OUNCES unless [includeLiquids] is set.
 *   METRIC turns them into ml, which is exact and needs no density, so it ignores the flag.
 * - METRIC otherwise gives spoons and cups as ml, except that a line carrying the site's own
 *   weight ("1 tsp (4 g) salt", "1 cup/4 oz walnuts") shows that weight in g/kg, even for an
 *   ingredient missing from the table or listed there as a skip. Known liquids keep ml even
 *   then ("1 cup (245 g) milk" is 240 ml), and a range has no single figure, so it keeps ml.
 * - Bare "oz" is a weight, unless the ingredient is a known liquid, where it means fl oz.
 * - A compound amount ("1 cup plus 2 tbsp flour") is converted as a whole or not at all:
 *   an alternate measure after the second part is used for the total, otherwise both parts
 *   are converted and summed, and if either can't be the line is left as written.
 * - "1,5 kg" is 1.5 kg and converts with a comma ("1,13 kg"); "1,500 g" could be 1.5 g or
 *   1500 g, so a line holding a comma before three digits is left as written.
 */
object UnitConverter {

    private const val GRAMS_PER_OUNCE = 28.3495
    private const val ML_PER_CUP = 236.588

    // Kitchen-metric equivalents for volume -> ml (a cup is 240 ml on US nutrition labels).
    private val KITCHEN_ML = mapOf(
        MeasureUnit.TSP to 5.0,
        MeasureUnit.TBSP to 15.0,
        MeasureUnit.CUP to 240.0,
        MeasureUnit.FL_OZ to 30.0,
        MeasureUnit.ML to 1.0,
        MeasureUnit.L to 1000.0
    )

    private val PAREN_AT_START = Regex("""^\s*\(([^)]*)\)""")

    /** The patterns that read one language's unit and amount words (IngredientName reads them too). */
    internal class Patterns(val words: LanguageWords) {
        val scaler = IngredientScaler.patterns(words)
        private val units = UnitPatterns.of(words)

        val unitAtStart = Regex("""^\s*${units.captured}""", RegexOption.IGNORE_CASE)
        val slashAtStart = Regex(
            """^\s*/\s*(${scaler.qty})(\s*)${units.captured}""",
            RegexOption.IGNORE_CASE
        )

        // "250 - 300 g / 8 - 10 oz pasta": a range written a second way.
        // groups: 1 low, 2 range separator, 3 high, 4 space, 5 unit
        val slashRangeAtStart = Regex(
            """^\s*/\s*(${scaler.qty})(\s*[-–—]\s*|\s+${words.rangeWords}\s+)(${scaler.qty})(\s*)""" + units.captured,
            RegexOption.IGNORE_CASE
        )

        // "plus 1 Tbsp." straight after the first unit. groups: 1 quantity, 2 space, 3 unit
        val continuationAtStart = Regex(
            """^\s*${scaler.continuation}(${scaler.qty})(\s*)${units.captured}""",
            RegexOption.IGNORE_CASE
        )
    }

    internal fun patterns(words: LanguageWords): Patterns = words.compiled(Patterns::class) { Patterns(it) }

    /** The second half of a compound amount, "plus 2 tbsp". */
    private class Part(val quantity: Double, val unit: MeasureUnit)

    /** A second measure written next to the first: [base] is grams or ml, [text] as written. */
    private class Measure(val base: Double, val unit: MeasureUnit, val text: String)

    /** [length] is how much of the text after the unit the alternate measure occupied. */
    private class Alternate(val weight: Measure?, val volume: Measure?, val length: Int)

    private class Amount(val low: Double, val high: Double?, val separator: String)

    /**
     * [separatorFrom] is the line whose decimal separator the result follows: the line as the
     * recipe wrote it, when [line] is that line already scaled ("2,5 lb" doubled is "5 lb",
     * which no longer shows its comma). [words] null: a language the app has no words for, so
     * the line stays as written.
     */
    fun convert(
        line: String,
        system: UnitSystem,
        includeLiquids: Boolean,
        separatorFrom: String = line,
        words: LanguageWords? = LanguageWords.ENGLISH
    ): String {
        if (system == UnitSystem.AS_WRITTEN || words == null) return line
        if (IngredientScaler.AMBIGUOUS_COMMA.containsMatchIn(line)) return line
        val p = patterns(words)

        val lead = p.scaler.leading.find(line) ?: return line
        val afterQty = line.substring(lead.range.last + 1)
        if (p.scaler.notAnAmount.containsMatchIn(afterQty)) return line

        val low = IngredientScaler.parse(lead.groupValues[2]) ?: return line
        val upperText = lead.groupValues[4]
        val high = if (upperText.isEmpty()) null else IngredientScaler.parse(upperText) ?: return line
        val amount = Amount(low, high, lead.groupValues[3])

        val unitMatch = p.unitAtStart.find(afterQty) ?: return line
        val unit = MeasureUnit.fromText(unitMatch.groupValues[1], words) ?: return line
        if (unit in ownUnits(system)) return line

        var after = afterQty.substring(unitMatch.range.last + 1)

        // "1½ cups plus 1 Tbsp.": converting only the first part would be confidently wrong.
        var extra: Part? = null
        p.continuationAtStart.find(after)?.let { c ->
            if (amount.high != null) return line // a range plus a part: leave it
            val unit2 = MeasureUnit.fromText(c.groupValues[3], words) ?: return line
            val quantity2 = IngredientScaler.parse(c.groupValues[1]) ?: return line
            extra = Part(quantity2, unit2)
            after = after.substring(c.range.last + 1)
        }

        // "/ 8 - 10 oz": the site's range in another unit. It is consumed like "/120 g", and
        // shown instead of a calculated range when it is already in the target unit.
        var siteRange: Pair<MeasureUnit, String>? = null
        if (extra == null) {
            p.slashRangeAtStart.find(after)?.let { r ->
                val rangeUnit = MeasureUnit.fromText(r.groupValues[5], words) ?: return line
                if (IngredientScaler.parse(r.groupValues[1]) == null ||
                    IngredientScaler.parse(r.groupValues[3]) == null
                ) return line
                siteRange = rangeUnit to r.value.substringAfter('/').trim()
                after = after.substring(r.range.last + 1)
            }
        }

        val alternate = if (siteRange == null) findAlternate(p, after) else null
        if (alternate != null) after = after.substring(alternate.length)

        val density = IngredientDensities.find(after, words)
        val isLiquid = density?.liquid == true
        val effective = if (unit == MeasureUnit.OZ && isLiquid) MeasureUnit.FL_OZ else unit
        val extraPart = extra?.let {
            Part(it.quantity, if (it.unit == MeasureUnit.OZ && isLiquid) MeasureUnit.FL_OZ else it.unit)
        }
        if (effective == MeasureUnit.STICK && density?.stickable != true) return line
        if (extraPart?.unit == MeasureUnit.STICK && density?.stickable != true) return line

        val anyVolume = effective.kind == MeasureKind.VOLUME || extraPart?.unit?.kind == MeasureKind.VOLUME
        val allVolume = effective.kind == MeasureKind.VOLUME &&
                (extraPart == null || extraPart.unit.kind == MeasureKind.VOLUME)
        if (system != UnitSystem.METRIC && anyVolume && isLiquid && !includeLiquids) return line

        // METRIC keeps volumes as ml unless the ingredient is a known solid, which is weighed,
        // or the site wrote its own weight beside a non-liquid ("1 tsp (4 g) salt"). A range has
        // no single figure to borrow, the same guard weightAmount applies.
        val siteWeight = alternate?.weight != null && amount.high == null
        val asWeight = system != UnitSystem.METRIC || !allVolume ||
                (!isLiquid && (density?.gramsPerCup != null || siteWeight))

        val site = siteRange?.takeIf { (rangeUnit, _) ->
            rangeUnit in ownUnits(system) && if (asWeight) {
                rangeUnit.kind == MeasureKind.WEIGHT && !(rangeUnit == MeasureUnit.OZ && isLiquid)
            } else {
                rangeUnit == MeasureUnit.ML || rangeUnit == MeasureUnit.L
            }
        }?.second

        val calculated = if (asWeight) {
            weightAmount(amount, effective, extraPart, density, alternate?.weight, isLiquid, system)
        } else {
            volumeAmount(amount, effective, extraPart, alternate?.volume)
        }
        val converted = site ?: calculated ?: return line

        val comma = IngredientScaler.DECIMAL_COMMA.containsMatchIn(separatorFrom)
        return lead.groupValues[1] + IngredientScaler.withSeparator(converted, comma) + after
    }

    private fun ownUnits(system: UnitSystem): Set<MeasureUnit> = when (system) {
        UnitSystem.OUNCES -> setOf(MeasureUnit.OZ, MeasureUnit.LB)
        UnitSystem.METRIC -> setOf(MeasureUnit.G, MeasureUnit.KG, MeasureUnit.ML, MeasureUnit.L)
        UnitSystem.AS_WRITTEN -> emptySet()
    }

    private fun weightAmount(
        amount: Amount,
        unit: MeasureUnit,
        extra: Part?,
        density: Density?,
        altWeight: Measure?,
        isLiquid: Boolean,
        system: UnitSystem
    ): String? {
        // A range has no single figure to borrow, and "(8 oz)" beside a liquid means fl oz.
        val alt = altWeight?.takeIf { amount.high == null && !(it.unit == MeasureUnit.OZ && isLiquid) }
        if (alt != null && alt.unit in ownUnits(system)) return alt.text.trim()

        val gramsLow: Double
        val gramsHigh: Double?
        if (alt != null) {
            gramsLow = alt.base
            gramsHigh = null
        } else {
            fun grams(quantity: Double, u: MeasureUnit): Double? =
                if (u.kind == MeasureKind.WEIGHT) quantity * u.base
                else density?.gramsPerCup?.let { quantity * u.base * (it / ML_PER_CUP) }
            val extraGrams = if (extra == null) 0.0 else grams(extra.quantity, extra.unit) ?: return null
            gramsLow = (grams(amount.low, unit) ?: return null) + extraGrams
            gramsHigh = amount.high?.let { grams(it, unit) ?: return null }
        }
        return if (system == UnitSystem.OUNCES) {
            ounceText(gramsLow, gramsHigh, amount.separator)
        } else {
            metricText(gramsLow, gramsHigh, amount.separator, small = "g", large = "kg")
        }
    }

    private fun volumeAmount(amount: Amount, unit: MeasureUnit, extra: Part?, altVolume: Measure?): String? {
        val alt = altVolume?.takeIf { amount.high == null }
        if (alt != null) return alt.text.trim()
        val ml = KITCHEN_ML[unit] ?: return null
        val extraMl = if (extra == null) 0.0 else extra.quantity * (KITCHEN_ML[extra.unit] ?: return null)
        return metricText(
            amount.low * ml + extraMl, amount.high?.let { it * ml }, amount.separator, small = "ml", large = "L"
        )
    }

    // --- Alternate measures: "1 cup (120 g) flour", "1 cup/120 grams flour" ---

    private fun findAlternate(p: Patterns, after: String): Alternate? {
        PAREN_AT_START.find(after)?.let { m ->
            val pairs = p.scaler.qtyUnit.findAll(m.groupValues[1]).toList()
            if (pairs.isEmpty()) return null // e.g. "(packed)": not a measure, leave it
            return Alternate(
                weight = pairs.firstNotNullOfOrNull { measureOf(p, it, MeasureKind.WEIGHT) },
                volume = pairs.firstNotNullOfOrNull { measureOf(p, it, MeasureKind.VOLUME) },
                length = m.value.length
            )
        }
        p.slashAtStart.find(after)?.let { m ->
            val text = m.value.substringAfter('/').trim()
            val pair = p.scaler.qtyUnit.matchEntire(text) ?: return null
            return Alternate(
                weight = measureOf(p, pair, MeasureKind.WEIGHT),
                volume = measureOf(p, pair, MeasureKind.VOLUME),
                length = m.value.length
            )
        }
        return null
    }

    /** A weight (g, kg, oz, lb) or a metric volume (ml, l) from a "quantity unit" match. */
    private fun measureOf(p: Patterns, match: MatchResult, kind: MeasureKind): Measure? {
        val unit = MeasureUnit.fromText(match.groupValues[3], p.words) ?: return null
        val wanted = when (kind) {
            MeasureKind.WEIGHT -> unit.kind == MeasureKind.WEIGHT
            MeasureKind.VOLUME -> unit == MeasureUnit.ML || unit == MeasureUnit.L
        }
        if (!wanted) return null
        val quantity = IngredientScaler.parse(match.groupValues[1]) ?: return null
        return Measure(quantity * unit.base, unit, match.value)
    }

    // --- Formatting ---

    /** Grams as g/kg, or millilitres as ml/L: one decimal under 10, whole numbers up to 100, then 5s. */
    private fun metricText(low: Double, high: Double?, separator: String, small: String, large: String): String? {
        val top = maxOf(low, high ?: low)
        if (top < 0.5) return null
        val useLarge = top >= 1000
        fun part(v: Double) = if (useLarge) formatThousands(v) else formatSmall(v)
        val body = if (high == null) part(low) else part(low) + separator + part(high)
        return "$body ${if (useLarge) large else small}"
    }

    private fun formatSmall(v: Double): String {
        val rounded = when {
            v < 10 -> round(v * 2) / 2
            v < 100 -> round(v)
            else -> round(v / 5) * 5
        }
        return BigDecimal(rounded).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private fun formatThousands(v: Double): String =
        BigDecimal(v / 1000).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun ounceText(gramsLow: Double, gramsHigh: Double?, separator: String): String? {
        val low = gramsLow / GRAMS_PER_OUNCE
        val high = gramsHigh?.div(GRAMS_PER_OUNCE)
        if (maxOf(low, high ?: low) < 0.125) return null // a pinch in ounces means nothing
        if (high != null) {
            return IngredientScaler.format(roundOunces(low)) + separator +
                    IngredientScaler.format(roundOunces(high)) + " oz"
        }
        val rounded = roundOunces(low)
        if (rounded >= 16) {
            val pounds = floor(rounded / 16).toInt()
            val remainder = rounded - pounds * 16
            return if (remainder == 0.0) "$pounds lb"
            else "$pounds lb ${IngredientScaler.format(remainder)} oz"
        }
        return "${IngredientScaler.format(rounded)} oz"
    }

    // Eighths under 4 oz, quarters above: every result is a fraction IngredientScaler.format prints.
    private fun roundOunces(oz: Double): Double =
        if (oz < 4) round(oz * 8) / 8 else round(oz * 4) / 4
}
