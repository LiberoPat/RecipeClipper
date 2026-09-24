package com.example.recipeclipper.data.model

/**
 * [gramsPerCup] is per US cup (236.6 ml); null means "recognised, but deliberately not
 * converted" (ingredients whose weight varies too much to state a number honestly).
 * [liquid] means pourable, and is what the "convert liquids too" option controls.
 * [stickable] allows the "stick" unit (butter and margarine only).
 */
internal data class Density(
    val gramsPerCup: Double?,
    val liquid: Boolean,
    val stickable: Boolean = false
)

/**
 * Approximate weights of common baking ingredients. Dry goods follow King Arthur Baking's
 * published ingredient weight chart (spooned-and-levelled cups); liquids and fats use
 * physical densities (USDA), so a cup of milk is 245 g rather than a rounded 227 g.
 *
 * Only ingredients that weigh roughly the same every time belong here. Salt (table vs
 * kosher differ ~2x), chopped produce, shredded cheese, nuts, rolled oats and rice
 * (cooked vs raw) are left out on purpose, so those lines stay as written.
 */
internal object IngredientDensities {

    private class Entry(val aliases: List<String>, val density: Density)

    private fun dry(gramsPerCup: Double, vararg aliases: String) =
        Entry(aliases.toList(), Density(gramsPerCup, liquid = false))

    private fun liquid(gramsPerCup: Double, vararg aliases: String) =
        Entry(aliases.toList(), Density(gramsPerCup, liquid = true))

    private fun butter(gramsPerCup: Double, vararg aliases: String) =
        Entry(aliases.toList(), Density(gramsPerCup, liquid = false, stickable = true))

    /** Matches by name but converts nothing; beats a shorter alias like plain "flour". */
    private fun skip(vararg aliases: String) =
        Entry(aliases.toList(), Density(null, liquid = false))

    private val ENTRIES = listOf(
        // Flours and starches
        dry(120.0, "all purpose flour", "ap flour", "plain flour", "bread flour", "flour"),
        dry(114.0, "cake flour"),
        dry(106.0, "pastry flour"),
        dry(113.0, "whole wheat flour", "wholemeal flour"),
        dry(96.0, "almond flour", "almond meal", "ground almonds"),
        dry(92.0, "oat flour"),
        dry(138.0, "cornmeal"),
        dry(112.0, "cornstarch", "corn starch"),
        skip(
            "rice flour", "coconut flour", "corn flour", "cornflour", "chickpea flour",
            "gram flour", "tapioca flour", "potato flour", "gluten free flour",
            "gluten free all purpose flour"
        ),

        // Sugars
        dry(200.0, "granulated sugar", "white sugar", "caster sugar", "castor sugar", "superfine sugar", "sugar"),
        dry(213.0, "brown sugar"), // packed, the convention in recipes
        dry(113.0, "powdered sugar", "confectioners sugar", "icing sugar"),

        // Baking staples
        dry(84.0, "cocoa powder", "cocoa", "unsweetened cocoa"),
        dry(192.0, "baking powder"),
        dry(288.0, "baking soda", "bicarbonate of soda"),
        dry(170.0, "chocolate chips", "chocolate chunks"),

        // Fats and spreads
        butter(227.0, "butter", "margarine"),
        dry(260.0, "peanut butter", "almond butter", "cashew butter", "nut butter"),
        skip("apple butter", "cocoa butter", "shea butter"),

        // Dairy that isn't pourable
        dry(230.0, "sour cream"),
        dry(245.0, "yogurt", "greek yogurt", "plain yogurt"),

        // Pourable
        liquid(237.0, "water"),
        liquid(245.0, "milk", "whole milk", "skim milk", "buttermilk"),
        liquid(
            238.0, "heavy cream", "heavy whipping cream", "whipping cream", "double cream",
            "cream", "light cream", "single cream"
        ),
        // Bare "cream" is pourable; these end in "cream" but are not, or vary too much.
        skip("ice cream", "whipped cream", "coconut cream", "clotted cream"),
        liquid(242.0, "half and half"),
        liquid(
            218.0, "oil", "olive oil", "vegetable oil", "canola oil", "sunflower oil",
            "avocado oil", "coconut oil"
        ),
        liquid(340.0, "honey"),
        liquid(315.0, "maple syrup"),
        liquid(240.0, "broth", "stock", "coffee", "beer"),
        liquid(239.0, "vinegar"),
        liquid(236.0, "wine"),
        liquid(245.0, "juice"),
        skip(
            "condensed milk", "sweetened condensed milk", "milk powder", "powdered milk",
            "dry milk"
        )
    )

    // Longest alias first, so "brown sugar" wins over "sugar" and "peanut butter" over "butter".
    private val ALIASES: List<Pair<String, Density>> = ENTRIES
        .flatMap { entry -> entry.aliases.map { it to entry.density } }
        .sortedByDescending { it.first.length }

    private val TRAILING_MODIFIERS = setOf(
        "packed", "sifted", "unsifted", "softened", "melted", "divided", "cold", "chilled",
        "warm", "lukewarm", "hot", "room", "temperature", "at", "optional"
    )

    /**
     * Looks the ingredient up by the *end* of its name, so "unsalted butter" and "light
     * brown sugar" match while "butter beans" and "flour tortillas" don't.
     */
    fun find(ingredientText: String): Density? {
        val phrase = headPhrase(ingredientText)
        return ALIASES.firstOrNull { (alias, _) -> phrase == alias || phrase.endsWith(" $alias") }?.second
    }

    private val INNERMOST_PARENS = Regex("""\([^()]*\)""")

    /**
     * Removes parenthesised text, including nested or doubled parentheses ("((all-purpose
     * flour))"), innermost first until nothing changes, then drops any unmatched paren.
     */
    private fun stripParentheses(text: String): String {
        var current = text
        while (true) {
            val next = INNERMOST_PARENS.replace(current, " ")
            if (next == current) break
            current = next
        }
        return current.replace('(', ' ').replace(')', ' ')
    }

    /** The ingredient name: text before the first comma, without parentheses or modifiers. */
    private fun headPhrase(text: String): String {
        val words = stripParentheses(text)
            .substringBefore(',')
            .lowercase()
            .replace("'", "")
            .replace("’", "")
            .replace('-', ' ')
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
        return words.dropLastWhile { it in TRAILING_MODIFIERS }.joinToString(" ")
    }
}
