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

internal enum class MeasureKind { VOLUME, WEIGHT }

/** [base] is millilitres for volume units and grams for weight units. */
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
    G(MeasureKind.WEIGHT, 1.0, metric = true),
    KG(MeasureKind.WEIGHT, 1000.0, metric = true),
    OZ(MeasureKind.WEIGHT, 28.3495),
    LB(MeasureKind.WEIGHT, 453.592);

    companion object {
        fun fromText(text: String): MeasureUnit? {
            val s = text.lowercase().replace(".", "").replace(Regex("""\s+"""), " ")
            return when {
                s.startsWith("fl") -> FL_OZ
                s.startsWith("tsp") || s.startsWith("teaspoon") -> TSP
                s.startsWith("tbs") || s.startsWith("tablespoon") -> TBSP
                s.startsWith("cup") -> CUP
                s == "ml" || s.startsWith("millil") -> ML
                s == "kg" || s.startsWith("kilo") -> KG
                s == "g" || s.startsWith("gram") -> G
                s == "l" || s.startsWith("lit") -> L
                s.startsWith("stick") -> STICK
                s == "oz" || s.startsWith("ounce") -> OZ
                s == "lb" || s == "lbs" || s.startsWith("pound") -> LB
                else -> null
            }
        }
    }
}

/**
 * Regex fragments matching a unit word. The trailing lookahead makes them match whole
 * words only, so "g" doesn't match the start of "garlic" or "l" the start of "large".
 */
internal object UnitPatterns {
    private const val ALTERNATIVES =
        """fl\.?\s*oz|fluid\s+ounces?|tsps?|teaspoons?|tbsps?|tbs|tablespoons?|cups?|""" +
                """millilit(?:er|re)s?|ml|kilograms?|kilos?|kg|grams?|g|lit(?:er|re)s?|l|""" +
                """sticks?|ounces?|oz|lbs?|pounds?"""

    // The alternation is wrapped in its own group so the optional trailing period applies to
    // every unit ("tsp.", "Tbsp.", "oz.", "lb."), not just the last alternative.

    /** One capturing group holding the unit text. */
    const val CAPTURED = """((?:$ALTERNATIVES)\.?)(?![A-Za-z])"""

    /** Same match, no capturing group. */
    const val PLAIN = """(?:(?:$ALTERNATIVES)\.?)(?![A-Za-z])"""
}
