package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitConverterTest {

    private fun grams(line: String, liquids: Boolean = false) =
        UnitConverter.convert(line, UnitSystem.GRAMS, liquids)

    private fun ounces(line: String, liquids: Boolean = false) =
        UnitConverter.convert(line, UnitSystem.OUNCES, liquids)

    private fun metric(line: String) = UnitConverter.convert(line, UnitSystem.METRIC, false)

    // --- Grams: volume to weight through the density table ---

    @Test fun `as written never changes a line`() {
        assertEquals("2 cups flour", UnitConverter.convert("2 cups flour", UnitSystem.AS_WRITTEN, true))
    }

    @Test fun `cups of dry goods become grams`() {
        assertEquals("120 g all-purpose flour", grams("1 cup all-purpose flour"))
        assertEquals("240 g flour", grams("2 cups flour"))
        assertEquals("300 g sugar", grams("1 1/2 cups sugar"))
        assertEquals("225 g whole wheat flour", grams("2 cups whole wheat flour"))
        assertEquals("170 g semisweet chocolate chips", grams("1 cup semisweet chocolate chips"))
    }

    @Test fun `spoons and sticks become grams`() {
        assertEquals("7.5 g flour, sifted", grams("1 tbsp flour, sifted"))
        assertEquals("8 g baking powder", grams("2 tsp baking powder"))
        assertEquals("115 g butter", grams("1 stick butter"))
        assertEquals("1.5 g baking soda", grams("1/4 tsp baking soda"))
    }

    @Test fun `ranges convert both ends`() {
        assertEquals("120-240 g flour", grams("1-2 cups flour"))
    }

    @Test fun `modifiers around the name are ignored`() {
        assertEquals("215 g packed brown sugar", grams("1 cup packed brown sugar"))
        assertEquals("225 g unsalted butter, softened", grams("1 cup unsalted butter, softened"))
        assertEquals("260 g peanut butter", grams("1 cup peanut butter"))
    }

    // --- Grams: weight to weight is exact, and needs no table ---

    @Test fun `ounces and pounds become grams`() {
        assertEquals("225 g cream cheese", grams("8 oz cream cheese"))
        assertEquals("455 g ground beef", grams("1 lb ground beef"))
        assertEquals("2.27 kg potatoes", grams("5 lb potatoes"))
    }

    @Test fun `amounts already in grams are left exactly as written`() {
        assertEquals("125 g flour", grams("125 g flour"))
        assertEquals("1 kg flour", grams("1 kg flour"))
    }

    // --- Things that must NOT convert ---

    @Test fun `unknown ingredients stay as written`() {
        assertEquals("1 tsp salt", grams("1 tsp salt"))
        assertEquals("1 cup rolled oats", grams("1 cup rolled oats"))
        assertEquals("1 cup chopped onion", grams("1 cup chopped onion"))
    }

    @Test fun `names that merely end like a known ingredient are not matched`() {
        assertEquals("1 cup butter beans", grams("1 cup butter beans"))
        assertEquals("1 cup apple butter", grams("1 cup apple butter"))
        assertEquals("1 cup rice flour", grams("1 cup rice flour"))
        assertEquals("2 cups sweetened condensed milk", grams("2 cups sweetened condensed milk", liquids = true))
    }

    @Test fun `lines that are not measured amounts stay as written`() {
        assertEquals("3 cloves garlic", grams("3 cloves garlic"))
        assertEquals("2 large eggs", grams("2 large eggs"))
        assertEquals("1 (14 oz) can tomatoes", grams("1 (14 oz) can tomatoes"))
        assertEquals("Salt to taste", grams("Salt to taste"))
        assertEquals("1 stick cinnamon", grams("1 stick cinnamon"))
        assertEquals("1-inch piece ginger", grams("1-inch piece ginger"))
    }

    // --- Liquids stay as written unless asked ---

    @Test fun `bare cream is a liquid, but creams that are not pourable are not matched`() {
        assertEquals("1 cup cream", grams("1 cup cream"))
        assertEquals("240 g cream", grams("1 cup cream", liquids = true))
        assertEquals("240 ml cream", metric("1 cup cream"))
        assertEquals("240 ml cream", metric("8 oz cream"))
        assertEquals("1 cup ice cream", grams("1 cup ice cream", liquids = true))
        assertEquals("1 cup whipped cream", grams("1 cup whipped cream", liquids = true))
        assertEquals("1/2 cup coconut cream", grams("1/2 cup coconut cream", liquids = true))
        assertEquals("230 g sour cream", grams("1 cup sour cream"))
        assertEquals("225 g cream cheese", grams("8 oz cream cheese"))
    }

    @Test fun `liquids are left alone by default`() {
        assertEquals("1 cup milk", grams("1 cup milk"))
        assertEquals("8 oz milk", grams("8 oz milk"))
        assertEquals("1 cup water", ounces("1 cup water"))
        assertEquals("1 cup (245 g) milk", grams("1 cup (245 g) milk"))
    }

    @Test fun `liquids convert when the option is on`() {
        assertEquals("245 g milk", grams("1 cup milk", liquids = true))
        assertEquals("245 g of milk", grams("1 cup of milk", liquids = true))
        assertEquals("8 1/4 oz water", ounces("1 cup water", liquids = true))
    }

    @Test fun `bare oz beside a liquid means fluid ounces`() {
        assertEquals("245 g milk", grams("8 oz milk", liquids = true))
    }

    // --- The site's own figure beats a calculated one ---

    @Test fun `alternate measure in parentheses is used as written`() {
        assertEquals("120 g flour", grams("1 cup (120 g) flour"))
        assertEquals("18 g table salt", grams("1 tbsp (18 g) table salt"))
    }

    @Test fun `alternate measure after a slash is used as written`() {
        assertEquals("120 grams flour", grams("1 cup/120 grams flour"))
    }

    @Test fun `alternate measure in the wrong system is converted`() {
        assertEquals("4 1/4 oz flour", ounces("1 cup (120 g) flour"))
    }

    // --- Ounces ---

    @Test fun `dry goods become ounces`() {
        assertEquals("4 1/4 oz flour", ounces("1 cup flour"))
        assertEquals("8 oz butter", ounces("2 sticks butter"))
    }

    @Test fun `grams become ounces and pounds`() {
        assertEquals("8 3/4 oz sugar", ounces("250 g sugar"))
        assertEquals("1 lb 1 3/4 oz flour", ounces("500 g flour"))
    }

    @Test fun `amounts already in ounces or pounds are left alone`() {
        assertEquals("8 oz chocolate", ounces("8 oz chocolate"))
        assertEquals("1 lb butter", ounces("1 lb butter"))
    }

    @Test fun `amounts too small to mean anything in ounces are left alone`() {
        assertEquals("1/4 tsp baking soda", ounces("1/4 tsp baking soda"))
    }

    // --- Metric: g for solids, ml for everything poured or spooned ---

    @Test fun `metric weighs known solids`() {
        assertEquals("120 g all-purpose flour", metric("1 cup all-purpose flour"))
        assertEquals("115 g butter", metric("1 stick butter"))
        assertEquals("65 g peanut butter", metric("1/4 cup peanut butter"))
        assertEquals("225 g cream cheese", metric("8 oz cream cheese"))
        assertEquals("455 g butter", metric("1 lb butter"))
    }

    @Test fun `metric turns liquids into ml without needing the option`() {
        assertEquals("240 ml milk", metric("1 cup milk"))
        assertEquals("360 ml milk", metric("1 1/2 cups milk"))
        assertEquals("240-480 ml milk", metric("1-2 cups milk"))
        assertEquals("30 ml olive oil", metric("2 tbsp olive oil"))
        assertEquals("15 ml honey", metric("1 tbsp honey"))
        assertEquals("240 ml milk", metric("8 oz milk"))
    }

    @Test fun `metric turns spoons and cups of unknown things into ml`() {
        assertEquals("5 ml salt", metric("1 tsp salt"))
        assertEquals("2.5 ml vanilla extract", metric("1/2 tsp vanilla extract"))
        assertEquals("240 ml chopped onion", metric("1 cup chopped onion"))
    }

    @Test fun `metric switches to litres from a litre up`() {
        assertEquals("960 ml water", metric("4 cups water"))
        assertEquals("1.2 L water", metric("5 cups water"))
    }

    @Test fun `metric uses the site's own ml figure`() {
        assertEquals("240 ml milk", metric("1 cup (240 ml) milk"))
    }

    // --- Metric: the site's own weight beats ml for anything that isn't a liquid ---

    @Test fun `metric keeps the site's gram figure for an ingredient not in the table`() {
        assertEquals("4 g salt", metric("1 tsp (4 g) salt"))
        assertEquals("4 g Diamond Crystal kosher salt", metric("1¼ tsp. (4 g) Diamond Crystal kosher salt"))
        assertEquals("120 grams chopped onion", metric("1 cup/120 grams chopped onion"))
    }

    @Test fun `metric keeps the site's gram figure for a skip entry`() {
        assertEquals("125 g rice flour", metric("1 cup (125 g) rice flour"))
    }

    @Test fun `metric keeps the site's figure for a compound amount`() {
        assertEquals("140 g chopped onion", metric("1 cup plus 2 tbsp (140 g) chopped onion"))
    }

    @Test fun `metric converts a site's ounce figure to grams`() {
        assertEquals("115 g chopped walnuts", metric("1 cup (4 oz) chopped walnuts"))
        assertEquals("115 g chopped walnuts", metric("1 cup/4 oz chopped walnuts"))
    }

    @Test fun `metric keeps liquids in ml even beside a gram figure`() {
        assertEquals("240 ml milk", metric("1 cup (245 g) milk"))
        assertEquals("240 ml milk", metric("1 cup (240 ml) milk"))
    }

    @Test fun `metric leaves a range with a gram figure as ml`() {
        assertEquals("5-10 ml salt", metric("1-2 tsp (4 g) salt"))
    }

    @Test fun `metric without a site figure is unchanged`() {
        assertEquals("5 ml salt", metric("1 tsp salt"))
        assertEquals("270 ml chopped onion", metric("1 cup plus 2 tbsp chopped onion"))
    }

    @Test fun `metric leaves metric amounts and non-measures alone`() {
        assertEquals("250 ml milk", metric("250 ml milk"))
        assertEquals("500 g flour", metric("500 g flour"))
        assertEquals("1 stick cinnamon", metric("1 stick cinnamon"))
        assertEquals("3 cloves garlic", metric("3 cloves garlic"))
    }

    // --- Abbreviations with a trailing period ("tsp.", "Tbsp.", "oz.", "lb.") ---

    @Test fun `a period after the unit is part of the unit`() {
        assertEquals("4 g baking soda", grams("¾ tsp. (4 g) baking soda"))
        assertEquals("170 g bittersweet chocolate", grams("6 oz. (170 g) bittersweet chocolate"))
        assertEquals("4 g Diamond Crystal kosher salt", metric("1¼ tsp. (4 g) Diamond Crystal kosher salt"))
        assertEquals("455 g boneless chicken", grams("1 lb. boneless chicken"))
        assertEquals("15 g flour", grams("2 Tbsp. flour"))
        assertEquals("1 lb. butter", ounces("1 lb. butter"))
    }

    // --- Compound amounts: "1 cup plus 2 tbsp" ---

    @Test fun `a compound amount uses the site's figure for the whole amount`() {
        assertEquals("200 g all-purpose flour", grams("1½ cups plus 1 Tbsp. (200 g) all-purpose flour"))
        assertEquals("5 oz flour", ounces("1 cup plus 2 tbsp (140 g) flour"))
    }

    @Test fun `a compound amount converts the sum of its parts`() {
        assertEquals("135 g flour", grams("1 cup plus 2 tbsp flour"))
        assertEquals("135 g flour", grams("1 cup + 2 tbsp flour"))
        assertEquals("135 g flour", grams("1 cup and 2 tbsp flour"))
        assertEquals("4 3/4 oz flour", ounces("1 cup plus 2 tbsp flour"))
        assertEquals("510 g chicken", grams("1 lb plus 2 oz chicken"))
        assertEquals("270 ml milk", metric("1 cup plus 2 tbsp milk"))
        assertEquals("135 g flour", metric("1 cup plus 2 tbsp flour"))
        assertEquals("140 g butter", metric("1 stick plus 2 tbsp butter"))
        assertEquals("275 g milk", grams("1 cup plus 2 tbsp milk", liquids = true))
    }

    @Test fun `a compound amount that cannot be converted whole is left as written`() {
        assertEquals("1 cup plus 2 tbsp chopped onion", grams("1 cup plus 2 tbsp chopped onion"))
        assertEquals("1 cup plus 2 tbsp milk", grams("1 cup plus 2 tbsp milk"))
        assertEquals("1-2 cups plus 1 tbsp flour", grams("1-2 cups plus 1 tbsp flour"))
    }

    // --- Doubled or nested parentheses after the name ---

    @Test fun `doubled parentheses do not hide the ingredient name`() {
        assertEquals("23 g plain flour ((all-purpose flour))", grams("3 tbsp plain flour ((all-purpose flour))"))
        assertEquals("120 g flour (sifted (optional))", grams("1 cup flour (sifted (optional))"))
        assertEquals("1 cup butter beans ((canned))", grams("1 cup butter beans ((canned))"))
    }

    @Test fun `single parentheses behave as before`() {
        assertEquals("215 g (packed) brown sugar", grams("1 cup (packed) brown sugar"))
        assertEquals("215 g brown sugar (packed)", grams("1 cup brown sugar (packed)"))
        assertEquals("120 g flour ((sifted))", grams("1 cup (120 g) flour ((sifted))"))
    }

    // --- Decimal commas (#12) ---

    @Test fun `a decimal comma is converted as a decimal and keeps its comma`() {
        assertEquals("3 lb 5 oz flour", ounces("1,5 kg flour"))
        assertEquals("680 g pork shoulder", grams("1,5 lb pork shoulder"))
        assertEquals("1,13 kg potatoes", grams("2,5 lb potatoes"))
        assertEquals("7,5 ml water", metric("1,5 tsp water"))
    }

    @Test fun `a scaled line keeps the separator of the line it was scaled from`() {
        // "2,5 lb" doubled is "5 lb", which no longer shows its comma.
        val scaled = IngredientScaler.scale("2,5 lb potatoes", 2.0)
        assertEquals("2,27 kg potatoes", UnitConverter.convert(scaled, UnitSystem.METRIC, false, "2,5 lb potatoes"))
        assertEquals("2.27 kg potatoes", UnitConverter.convert("5 lb potatoes", UnitSystem.METRIC, false))
    }

    @Test fun `a comma before three digits is ambiguous and left as written`() {
        assertEquals("1,500 g flour", ounces("1,500 g flour"))
        assertEquals("1,500 lb beef", grams("1,500 lb beef"))
        assertEquals("2 cups (1,250 g) flour", ounces("2 cups (1,250 g) flour"))
    }

    // --- Shapes found in real ingredient lines (#33) ---

    @Test fun `a fraction slash and a mixed number with and convert`() {
        assertEquals("2.5 ml olive oil", metric("1⁄2 tsp olive oil"))
        assertEquals("180 g flour", grams("1 1⁄2 cups flour"))
        assertEquals("300 g flour", grams("2 and 1/2 cups flour"))
        assertEquals("360 ml milk", metric("1 and ½ cups milk"))
    }

    @Test fun `a range after a slash uses the site's range in the target unit`() {
        assertEquals("8 - 10 oz pasta", ounces("250 - 300 g / 8 - 10 oz pasta"))
        // In grams and metric the leading range is already the target unit.
        assertEquals("250 - 300 g / 8 - 10 oz pasta", grams("250 - 300 g / 8 - 10 oz pasta"))
        assertEquals("250 - 300 g / 8 - 10 oz pasta", metric("250 - 300 g / 8 - 10 oz pasta"))
        // Neither half in the target unit: the calculated range replaces both.
        assertEquals("225 - 285 g spaghetti", grams("8 - 10 oz / 1/2 - 5/8 lb spaghetti"))
    }
}
