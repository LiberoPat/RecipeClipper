package com.example.recipeclipper.data.model

/**
 * How ingredient amounts are shown. AS_WRITTEN leaves the recipe's own units untouched.
 * OUNCES expresses everything as weight. METRIC is EU-style: weights in g/kg, volumes
 * (spoons, cups, liquids) in ml/L, and dry goods with a known density in g.
 */
enum class UnitSystem {
    AS_WRITTEN, METRIC, OUNCES;

    companion object {
        /**
         * The system a stored enum name stands for. GRAMS was a fourth option until #17; the
         * people who chose it wanted weights, so it reads as METRIC rather than falling back
         * to AS_WRITTEN. Anything unknown (or nothing stored) is AS_WRITTEN.
         */
        fun fromStoredName(name: String?): UnitSystem =
            if (name == "GRAMS") METRIC else values().firstOrNull { it.name == name } ?: AS_WRITTEN
    }
}

/** NONE: a unit whose size varies from cook to cook ([MeasureUnit.VARIES]). */
internal enum class MeasureKind { VOLUME, WEIGHT, NONE }

/**
 * [base] is millilitres for volume units and grams for weight units. [VARIES] is a unit word
 * whose size differs between cooks and countries (a French "tasse", a German "Tasse", an Italian
 * "tazza", a Portuguese "colher (café)"): its amount scales, so "250 ml (1 tasse)" doubles as a
 * whole, but it is never converted.
 */
internal enum class MeasureUnit(
    val kind: MeasureKind,
    val base: Double,
    val metric: Boolean = false
) {
    TSP(MeasureKind.VOLUME, 4.92892),
    TBSP(MeasureKind.VOLUME, 14.7868),
    CUP(MeasureKind.VOLUME, 236.588),
    FL_OZ(MeasureKind.VOLUME, 29.5735),
    STICK(MeasureKind.VOLUME, 118.294), // US butter stick = 8 tbsp
    ML(MeasureKind.VOLUME, 1.0, metric = true),
    L(MeasureKind.VOLUME, 1000.0, metric = true),
    CL(MeasureKind.VOLUME, 10.0, metric = true),
    DL(MeasureKind.VOLUME, 100.0, metric = true),
    G(MeasureKind.WEIGHT, 1.0, metric = true),
    KG(MeasureKind.WEIGHT, 1000.0, metric = true),
    OZ(MeasureKind.WEIGHT, 28.3495),
    LB(MeasureKind.WEIGHT, 453.592),
    VARIES(MeasureKind.NONE, 0.0);

    companion object {
        private val WHITESPACE = Regex("""\s+""")

        // shared/tables/<language>/units.json "names": the first rule the text satisfies wins.
        private class Name(val unit: MeasureUnit, val exact: List<String>, val prefixes: List<String>)

        private class Names(words: LanguageWords) {
            val names: List<Name> = SharedTables.objects(words.table("units").getJSONArray("names")).map {
                Name(
                    valueOf(it.getString("unit")),
                    SharedTables.strings(it.optJSONArray("exact")),
                    SharedTables.strings(it.optJSONArray("prefixes"))
                )
            }
        }

        fun fromText(text: String, words: LanguageWords = LanguageWords.ENGLISH): MeasureUnit? {
            val s = text.lowercase().replace(".", "").replace(WHITESPACE, " ")
            return words.compiled(Names::class) { Names(it) }.names.firstOrNull { name ->
                s in name.exact || name.prefixes.any { s.startsWith(it) }
            }?.unit
        }
    }
}

/**
 * Regex fragments matching a unit word. The trailing lookahead makes them match whole
 * words only, so "g" doesn't match the start of "garlic" or "l" the start of "large". It
 * looks for any letter, not just A-Z, so "g" isn't read in "gélatine" either (#15).
 */
internal class UnitPatterns private constructor(words: LanguageWords) {
    // The unit words are shared with iOS: shared/tables/<language>/units.json "patterns", in order.
    // (No units at all never matches, rather than matching an empty unit.)
    private val alternatives = words.strings("units", "patterns").ifEmpty { listOf("(?!)") }.joinToString("|")

    // The alternation is wrapped in its own group so the optional trailing period applies to
    // every unit ("tsp.", "Tbsp.", "oz.", "lb."), not just the last alternative.

    /** One capturing group holding the unit text. */
    val captured = """((?:$alternatives)\.?)(?!\p{L})"""

    /** Same match, no capturing group. */
    val plain = """(?:(?:$alternatives)\.?)(?!\p{L})"""

    companion object {
        fun of(words: LanguageWords = LanguageWords.ENGLISH): UnitPatterns =
            words.compiled(UnitPatterns::class) { UnitPatterns(it) }
    }
}
