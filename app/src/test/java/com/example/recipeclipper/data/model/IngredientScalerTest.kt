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
    }

    @Test fun `only the leading quantity is scaled`() {
        assertEquals("2 (14 oz) can tomatoes", scale("1 (14 oz) can tomatoes", 2.0))
        assertEquals("6 cloves garlic, minced (about 1 tbsp)", scale("3 cloves garlic, minced (about 1 tbsp)", 2.0))
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

    @Test fun `compound amounts scale both parts and the alternate measure`() {
        assertEquals("2 cup plus 4 tbsp (280 g) flour", IngredientScaler.scale("1 cup plus 2 tbsp (140 g) flour", 2.0))
        assertEquals(
            "3/4 cups plus 1/2 Tbsp. (100 g) all-purpose flour",
            IngredientScaler.scale("1½ cups plus 1 Tbsp. (200 g) all-purpose flour", 0.5)
        )
        assertEquals("3 cup + 6 tbsp sugar", IngredientScaler.scale("1 cup + 2 tbsp sugar", 3.0))
        assertEquals("2 cup plus 1 egg", IngredientScaler.scale("1 cup plus 1 egg", 2.0))
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
}
