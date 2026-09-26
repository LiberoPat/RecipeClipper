package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngredientScalerTest {

    private fun scale(line: String, factor: Double) = IngredientScaler.scale(line, factor)

    @Test fun `whole numbers`() {
        assertEquals("4 cups all-purpose flour", scale("2 cups all-purpose flour", 2.0))
        assertEquals("2 cups flour", scale("4 cups flour", 0.5))
    }

    @Test fun `factor of one leaves the line alone`() {
        assertEquals("1 1/2 cups sugar", scale("1 1/2 cups sugar", 1.0))
    }

    @Test fun `mixed numbers and fractions`() {
        assertEquals("3 cups sugar", scale("1 1/2 cups sugar", 2.0))
        assertEquals("1 1/2 cup milk", scale("1/2 cup milk", 3.0))
        assertEquals("2/3 cup oil", scale("1/3 cup oil", 2.0))
        assertEquals("1/3 cup flour", scale("1 cup flour", 1 / 3.0))
    }

    @Test fun `unicode fractions`() {
        assertEquals("2 tsp salt", scale("½ tsp salt", 4.0))
        assertEquals("3 tsp salt", scale("1½ tsp salt", 2.0))
        assertEquals("3 tsp salt", scale("1 ½ tsp salt", 2.0))
    }

    @Test fun `decimals`() {
        assertEquals("1 cup milk", scale("0.5 cup milk", 2.0))
        assertEquals("1 1/2 eggs", scale("3 eggs", 0.5))
    }

    @Test fun `ranges scale both ends and keep the separator`() {
        assertEquals("2-4 tbsp oil", scale("1-2 tbsp oil", 2.0))
        assertEquals("2–4 tbsp oil", scale("1–2 tbsp oil", 2.0))
        assertEquals("2 to 4 tbsp oil", scale("1 to 2 tbsp oil", 2.0))
        assertEquals("4-6 tbsp oil", scale("2-3 tbsp oil", 2.0))
        assertEquals("1-1 1/2 cup milk", scale("1/2-3/4 cup milk", 2.0))
        assertEquals("1-2 cup milk", scale("½-1 cup milk", 2.0))
        assertEquals("2 - 4 cups water", scale("1 - 2 cups water", 2.0))
        assertEquals("2-3 cups water", scale("1-1 1/2 cups water", 2.0))
    }

    // Taste of Home writes "1-1/2 cups": a whole number, a dash and a proper fraction, with no
    // spaces, are a mixed number, never a range running down to the fraction (#125).
    @Test fun `a hyphenated mixed number is one amount, not a range`() {
        assertEquals("3 cups sugar", scale("1-1/2 cups sugar", 2.0))
        assertEquals("3 1/2 cups all-purpose flour", scale("1-3/4 cups all-purpose flour", 2.0))
        assertEquals("3 cups sugar", scale("1–1/2 cups sugar", 2.0))
        assertEquals("5 tsp salt", scale("2-½ tsp salt", 2.0))
        assertEquals("3 to 4 cups milk", scale("1-1/2 to 2 cups milk", 2.0))
        assertEquals("3-4 cups milk", scale("1-1/2-2 cups milk", 2.0))
        // Not a proper fraction: neither a mixed number nor a range anyone writes, so as written.
        assertEquals("1-3/2 cups sugar", scale("1-3/2 cups sugar", 2.0))
    }

    @Test fun `every reader of the leading amount takes a hyphenated mixed number whole`() {
        val en = LanguageWords.ENGLISH
        assertEquals("300 g sugar", UnitConverter.convert("1-1/2 cups sugar", UnitSystem.METRIC, false))
        assertEquals("2 cups sugar", GroceryCombiner.combine(listOf("1-1/2 cups sugar", "1/2 cup sugar"), en))
        assertEquals("all purpose flour", IngredientName.of("1-3/4 cups all-purpose flour"))
        assertEquals(
            "Stir in ⟦1-1/2 cups⟧ sugar.",
            StepAmounts.marked(StepAmounts.annotate(listOf("Stir in the sugar."), listOf("1-1/2 cups sugar"), en).single())
        )
        assertEquals(5400, StepTimers.parse("Bake for 1-1/2 hours."))
    }

    @Test fun `only the leading quantity is scaled`() {
        assertEquals("2 (14 oz) can tomatoes", scale("1 (14 oz) can tomatoes", 2.0))
        // A count's bracket may be each clove's size, and a total beside it would contradict it (#63).
        assertEquals("3 cloves garlic, minced (about 1 tbsp)", scale("3 cloves garlic, minced (about 1 tbsp)", 2.0))
    }

    @Test fun `alternate measures are scaled with the leading amount`() {
        assertEquals("2 cup (240 g) flour", scale("1 cup (120 g) flour", 2.0))
        assertEquals("2 cup/240 grams flour", scale("1 cup/120 grams flour", 2.0))
        assertEquals("1 1/2 cup (360 ml) milk", scale("1 cup (240 ml) milk", 1.5))
        assertEquals("1 cup (2 stick, 226 g) butter", scale("1/2 cup (1 stick, 113 g) butter", 2.0))
    }

    @Test fun `a period after the unit does not stop the alternate measure scaling`() {
        assertEquals("2 tsp. (8 g) x", IngredientScaler.scale("1 tsp. (4 g) x", 2.0))
        assertEquals("2 lb. (910 g) chicken", IngredientScaler.scale("1 lb. (455 g) chicken", 2.0))
    }

    @Test fun `old-style c, T and t are units, so their alternate measures scale too`() {
        assertEquals("1 c. (2 stick) butter, melted", scale("1/2 c. (1 stick) butter, melted", 2.0))
        assertEquals("2 T. (30 ml) olive oil", scale("1 T. (15 ml) olive oil", 2.0))
        assertEquals("1 t (5 ml) vanilla", scale("1/2 t (2.5 ml) vanilla", 2.0))
        assertEquals("3 c. cherry tomatoes", scale("1 1/2 c. cherry tomatoes", 2.0))
        // A temperature is not an amount; a T in a word is no unit.
        assertEquals("180 C water", scale("180 C water", 2.0))
        assertEquals("4 T-bone steaks", scale("2 T-bone steaks", 2.0))
    }

    @Test fun `compound amounts scale both parts and the alternate measure`() {
        assertEquals("2 cup plus 4 tbsp (280 g) flour", IngredientScaler.scale("1 cup plus 2 tbsp (140 g) flour", 2.0))
        assertEquals(
            "3/4 cups plus 1/2 Tbsp. (100 g) all-purpose flour",
            IngredientScaler.scale("1½ cups plus 1 Tbsp. (200 g) all-purpose flour", 0.5)
        )
        assertEquals("3 cup + 6 tbsp sugar", IngredientScaler.scale("1 cup + 2 tbsp sugar", 3.0))
        assertEquals("2 cup plus 2 egg", IngredientScaler.scale("1 cup plus 1 egg", 2.0)) // a count scales too (#62)
    }

    @Test fun `package sizes and non-measures in parentheses are not scaled`() {
        assertEquals("2 can (14 oz) tomatoes", scale("1 can (14 oz) tomatoes", 2.0))
        assertEquals("2 cup (packed) brown sugar", scale("1 cup (packed) brown sugar", 2.0))
    }

    @Test fun `lines without a leading quantity are unchanged`() {
        assertEquals("Salt and pepper to taste", scale("Salt and pepper to taste", 2.0))
        assertEquals("A pinch of nutmeg", scale("A pinch of nutmeg", 2.0))
        assertEquals("", scale("", 2.0))
    }

    @Test fun `sizes and percentages are not amounts`() {
        assertEquals("1-inch piece ginger", scale("1-inch piece ginger", 2.0))
        assertEquals("2 inch piece ginger", scale("2 inch piece ginger", 2.0))
        assertEquals("2% milk", scale("2% milk", 2.0))
    }

    @Test fun `leading whitespace is preserved`() {
        assertEquals("  4 cups flour", scale("  2 cups flour", 2.0))
    }

    @Test fun `zero denominator is left alone`() {
        assertEquals("1/0 cup flour", scale("1/0 cup flour", 2.0))
    }

    @Test fun `format rounds to cooking fractions`() {
        assertEquals("1/4", IngredientScaler.format(0.26))
        assertEquals("1 1/3", IngredientScaler.format(1.33))
        assertEquals("2", IngredientScaler.format(1.99))
        assertEquals("3 3/4", IngredientScaler.format(3.75))
        assertEquals("0.06", IngredientScaler.format(0.0625))
    }

    @Test fun `yield prefers the entry that states a range`() {
        assertEquals("4 to 6 servings", Servings.pickYield(listOf("4", "4 to 6 servings")))
        assertEquals("4-6", Servings.pickYield(listOf("4", "4-6", "4 servings")))
        assertEquals("4–6 servings", Servings.pickYield(listOf("4", "4–6 servings")))
    }

    @Test fun `yield without a range keeps the first entry`() {
        assertEquals("4", Servings.pickYield(listOf("4", "4 servings")))
        assertNull(Servings.pickYield(emptyList()))
    }

    @Test fun `a bare number yield reports its count`() {
        assertEquals(6, Servings.bareCount("6"))
        assertEquals(6, Servings.bareCount(" 6 "))
        assertEquals(1, Servings.bareCount("1"))
    }

    @Test fun `a yield that already says what it is is not bare`() {
        assertNull(Servings.bareCount("4 to 6 servings"))
        assertNull(Servings.bareCount("4-6"))
        assertNull(Servings.bareCount("24 cookies"))
        assertNull(Servings.bareCount(""))
    }

    @Test fun `yield kind reads serves or makes from the yield text`() {
        val cases = listOf(
            "6" to YieldKind.SERVES,
            "4 servings" to YieldKind.SERVES,
            "Serves 4-6" to YieldKind.SERVES,
            "4 to 6 servings" to YieldKind.SERVES,
            "Feeds 8" to YieldKind.SERVES,
            "8 people" to YieldKind.SERVES,
            "" to YieldKind.SERVES,
            "a crowd" to YieldKind.SERVES,
            "4 to 6" to YieldKind.SERVES,
            "Makes 16" to YieldKind.MAKES,
            "16 cookies" to YieldKind.MAKES,
            "1 loaf" to YieldKind.MAKES,
            "12 muffins" to YieldKind.MAKES,
            "2 dozen" to YieldKind.MAKES,
            "1 (9-inch) pie" to YieldKind.MAKES,
            "1 9-inch pie" to YieldKind.MAKES,
            "Yield: 24 cookies" to YieldKind.MAKES,
            "about 24 cookies" to YieldKind.MAKES,
            "Makes 4 servings" to YieldKind.SERVES
        )
        for ((yield, expected) in cases) {
            assertEquals("kind(\"$yield\")", expected, Servings.kind(yield))
        }
        assertEquals(YieldKind.SERVES, Servings.kind(null))
    }

    @Test fun `servings parsing`() {
        assertEquals(4, Servings.parse("4 servings"))
        assertEquals(4, Servings.parse("Serves 4-6"))
        assertEquals(24, Servings.parse("Makes 24 cookies"))
        assertNull(Servings.parse("a crowd"))
        assertNull(Servings.parse(null))
        assertNull(Servings.parse("0 servings"))
        assertNull(Servings.parse("2024"))
    }

    // --- Decimal commas (#12): "1,5" is 1.5; "1,500" could be 1500, so it is left alone ---

    @Test fun `a decimal comma is read as a decimal`() {
        assertEquals("3 kg flour", IngredientScaler.scale("1,5 kg flour", 2.0))
        assertEquals("3-4 kg potatoes", IngredientScaler.scale("1,5-2 kg potatoes", 2.0))
        assertEquals("2,5 dl water", IngredientScaler.scale("1,25 dl water", 2.0))
    }

    @Test fun `a decimal comma line keeps its comma`() {
        assertEquals("2,25 kg flour", IngredientScaler.scale("1,5 kg flour", 1.5))
        assertEquals("0,17 l milk", IngredientScaler.scale("0,5 l milk", 1 / 3.0))
        assertEquals("3 kg (6,6 lb) potatoes", IngredientScaler.scale("2 kg (4,4 lb) potatoes", 1.5))
        // A decimal point keeps the fractions it always had.
        assertEquals("2 1/4 kg flour", IngredientScaler.scale("1.5 kg flour", 1.5))
    }

    @Test fun `a comma before three digits is ambiguous and left as written`() {
        assertEquals("1,500 g flour", IngredientScaler.scale("1,500 g flour", 2.0))
        assertEquals("1,000 ml water", IngredientScaler.scale("1,000 ml water", 0.5))
        assertEquals("2 cups (1,250 g) flour", IngredientScaler.scale("2 cups (1,250 g) flour", 2.0))
    }

    // --- Shapes found in real ingredient lines (#33) ---

    @Test fun `a fraction written with the fraction slash scales as a whole`() {
        // BBC Good Food writes "1⁄2" with U+2044; "1⁄2 lemon" doubled once read "2⁄2 lemon".
        assertEquals("1 lemon zested and juiced", IngredientScaler.scale("1⁄2 lemon zested and juiced", 2.0))
        assertEquals("1/4 tsp olive oil", IngredientScaler.scale("1⁄2 tsp olive oil", 0.5))
        assertEquals("3 cups flour", IngredientScaler.scale("1 1⁄2 cups flour", 2.0))
    }

    @Test fun `a mixed number joined by and scales as a whole`() {
        // "2 and 1/2 cups" doubled once read "4 and 1/2 cups".
        assertEquals("5 cups flour", IngredientScaler.scale("2 and 1/2 cups flour", 2.0))
        assertEquals("3/4 cups milk", IngredientScaler.scale("1 and ½ cups milk", 0.5))
        // "and" between two amounts with units is still a compound, not a mixed number.
        assertEquals("2 cups and 4 tbsp flour", IngredientScaler.scale("1 cups and 2 tbsp flour", 2.0))
    }

    @Test fun `a range after a slash scales at both ends`() {
        // RecipeTin Eats: doubling once left "/ 8 - 10 oz" as written beside "500 - 600 g".
        assertEquals(
            "500 - 600 g / 16 - 20 oz pasta",
            IngredientScaler.scale("250 - 300 g / 8 - 10 oz pasta", 2.0)
        )
        assertEquals("1 cup / 240 to 250 g flour", IngredientScaler.scale("2 cup / 480 to 500 g flour", 0.5))
    }

    // --- Alternatives, compound parts and totals after the name (#61, #62, #63) ---

    @Test fun `an alternative amount with a unit scales with the first`() {
        assertEquals("2 cup butter or 1 cup oil", scale("1 cup butter or 1/2 cup oil", 2.0))
        assertEquals("2 cup butter (or 1 cup oil)", scale("1 cup butter (or 1/2 cup oil)", 2.0))
        assertEquals("2 cup (240 g) sugar or 1 cup (200 g) honey", scale("1 cup (120 g) sugar or 1/2 cup (100 g) honey", 2.0))
        // "or" with no amount after it is part of the name.
        assertEquals("2 cup butter or margarine", scale("1 cup butter or margarine", 2.0))
    }

    @Test fun `an alternative without a unit leaves the whole line as written`() {
        assertEquals("1 cup butter or 2 eggs", scale("1 cup butter or 2 eggs", 2.0))
        assertEquals("1 cup butter or 2-inch piece", scale("1 cup butter or 2-inch piece", 2.0))
    }

    @Test fun `an or inside a package size is not an alternative`() {
        assertEquals("2 can (14 oz or 400 g) tomatoes", scale("1 can (14 oz or 400 g) tomatoes", 2.0))
    }

    @Test fun `a second part added later in the line scales, counted or measured`() {
        assertEquals("2 cup flour, plus 4 tbsp for dusting", scale("1 cup flour, plus 2 tbsp for dusting", 2.0))
        assertEquals("6 eggs + 3 yolk", scale("2 eggs + 1 yolk", 3.0))
        assertEquals("4 eggs plus 2 yolks", scale("2 eggs plus 1 yolks", 2.0))
    }

    @Test fun `a part taken away scales too`() {
        assertEquals("1 cups minus 1 tbsp flour", scale("2 cups minus 2 tbsp flour", 0.5))
        assertEquals("4 eggs minus 2 whites", scale("2 eggs minus 1 whites", 2.0))
    }

    @Test fun `a measure's total after the name scales with it`() {
        assertEquals("4 cups flour (500 g)", scale("2 cups flour (250 g)", 2.0))
        assertEquals("2 lb potatoes, peeled (about 900-1000 g)", scale("1 lb potatoes, peeled (about 450-500 g)", 2.0))
        assertEquals("1 cup oats (~45 g/1 1/2 oz)", scale("2 cup oats (~90 g/3 oz)", 0.5))
    }

    @Test fun `package and per-item sizes never scale`() {
        assertEquals("4 cans (15 oz) beans", scale("2 cans (15 oz) beans", 2.0))
        assertEquals("4 (15 oz) cans beans", scale("2 (15 oz) cans beans", 2.0))
        assertEquals("8 steaks (8 oz each)", scale("4 steaks (8 oz each)", 2.0))
        assertEquals("2 can tomatoes (400 g)", scale("1 can tomatoes (400 g)", 2.0))
    }

    @Test fun `a bracket that may contradict the scaled amount leaves the line as written`() {
        // A count's bracket may be each one's weight or the total.
        assertEquals("4 steaks (about 2 lb)", scale("4 steaks (about 2 lb)", 2.0))
        assertEquals("1 onion (150 g)", scale("1 onion (150 g)", 2.0))
        // Not only an amount: it can't be scaled whole.
        assertEquals("1 cup rice (cooked in 2 cups water)", scale("1 cup rice (cooked in 2 cups water)", 2.0))
        // No unit in the bracket: nothing to contradict.
        assertEquals("4 cups chopped onion (2 medium)", scale("2 cups chopped onion (2 medium)", 2.0))
    }
}
