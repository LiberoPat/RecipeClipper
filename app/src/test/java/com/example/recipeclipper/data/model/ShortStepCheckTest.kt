package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chef mode's gate (#100, #129): a short step shows only if it keeps the step's numbers honest
 * and drops or adds no word a cook acts on. Several rows are replies from the #105/#129
 * evaluation (tools/eval/gold/chef.jsonl).
 */
class ShortStepCheckTest {

    private val en = LanguageWords.ENGLISH

    private fun ok(original: String, short: String, words: LanguageWords? = en, lines: List<String> = emptyList()) =
        assertEquals(ShortStepCheck.tidy(short), ShortStepCheck.accept(original, short, words, lines))

    private fun no(original: String, short: String?, words: LanguageWords? = en, lines: List<String> = emptyList()) =
        assertNull(ShortStepCheck.accept(original, short, words, lines))

    @Test fun `a shorter step with the same numbers and words passes`() {
        ok("Preheat the oven to 350°F (180°C) and grease a 9x13-inch baking pan.", "Preheat oven to 350°F (180°C); grease a 9x13-inch baking pan.")
        ok("Bake for 25 to 30 minutes, until the top is golden.", "Bake 25–30 min until golden.")
        ok("Add 1 1/2 cups of the flour and mix gently until combined.", "Add 1 1/2 cups flour; mix until combined.")
        ok("Stir in ½ teaspoon of salt until it dissolves.", "Stir in ½ tsp salt until dissolved.")
    }

    @Test fun `a number that isn't in the step fails`() {
        no("Bake for 20 minutes, until the top is golden.", "Bake 25 min.")
        no("Add 1 1/2 cups of the flour and mix gently until combined.", "Add 1/2 cup flour; mix.")
        no("Whisk the eggs with the sugar until pale and thick.", "Whisk 2 eggs with sugar.")
    }

    @Test fun `a changed, added or dropped time fails`() {
        no("Microwave for 30 seconds, then stir well.", "Microwave 30 min, stir.")
        no("Simmer for 20 minutes, stirring often so it doesn't catch.", "Simmer, stirring often.")
        no("Simmer for 1 hour 30 minutes, stirring now and then.", "Simmer 90 min.")
    }

    @Test fun `a changed or dropped temperature fails, but one half of a pair may go`() {
        no("Roast at 200°C for 1 hour, turning halfway through.", "Roast at 200°F, 1 hr.")
        no("Bake at 350°F until golden brown on top and set.", "Bake until golden.")
        ok("Preheat the oven to 350°F (180°C) with a rack in the middle.", "Preheat oven to 350°F, rack in middle.")
    }

    @Test fun `a dropped action, piece of equipment or qualifier fails`() {
        no("Preheat the oven to 375°F. Lightly grease (or line with parchment) two baking sheets.", "Preheat the oven to 375°F.")
        no("Preheat the oven to 350°F (180°C) and grease a 9x13-inch baking pan.", "Oven to 350°F (180°C); grease a 9x13-inch pan.")
        no("Beat in the egg, again beating until smooth. Scrape the bottom and sides of the bowl with a spatula.", "Beat in the egg, then scrape down the bowl.")
        no("Set aside for 30 mins to rest if you have time, or start cooking straight away.", "Set aside for 30 mins to rest, then start cooking.")
        no("Alternative: Use 1/3 cup Chinese All Purpose Stir Fry Sauce, if you have some in stock.", "Use 1/3 cup Chinese All Purpose Stir Fry Sauce, if you have some in stock.")
        // A time word without a number ("a few minutes") is kept too.
        no("Remove from the oven and let cool in the pan for a few minutes. Then remove and cool on a rack.", "Remove from oven and let cool in pan. Then remove and cool on rack.")
    }

    @Test fun `a dropped ingredient fails when the recipe names it`() {
        val lines = listOf("2 cloves garlic, minced", "1 onion, diced")
        no("Add the garlic and onion and cook until soft.", "Add garlic; cook until soft.", lines = lines)
        ok("Add the garlic and onion and cook until soft.", "Add garlic and onion; cook until soft.", lines = lines)
        // With no lines, only the tables' words are kept.
        ok("Add the garlic and onion and cook until soft.", "Add garlic; cook until soft.")
        no(
            "In a mixing bowl, mash the ripe bananas with a fork until smooth. Stir in the melted butter.", "Stir in the melted butter.",
            lines = listOf("3 ripe bananas", "1/3 cup butter, melted")
        )
    }

    @Test fun `an added word fails`() {
        no("Whisk the eggs with the sugar until pale and thick.", "Whisk eggs, sugar and vanilla until pale.")
        no("In the meantime wrap tofu in a clean, absorbent towel and set something heavy on top to press out the liquid.", "Press tofu for an hour.")
        no("Heat oven to 350 degrees F. Butter a 6-cup loaf pan or coat it with nonstick spray.", "Preheat oven to 350 degrees F. Grease and flour a 6-cup loaf pan.")
    }

    @Test fun `endings, abbreviations and units count as the same word`() {
        ok("In a medium bowl, whisk together the flour, baking soda, baking powder and salt.", "In a medium bowl, whisk flour, baking soda, baking powder and salt.")
        ok("Store cookies, well wrapped, at room temperature for up to 5 days; freeze for longer storage.", "Store cookies, well wrapped, at room temp for up to 5 days. Freeze for longer.")
        ok("Once the tofu is done baking, add directly to the sauce and marinate for 5 minutes, stirring occasionally.", "Once the tofu is done baking, add directly to the sauce and marinate for 5 mins, stir.")
        // "the rest of the flour": after "the", an action word is a noun and may go.
        ok("Let the dough rest, then add the rest of the flour and knead until smooth.", "Let the dough rest, then knead in flour until smooth.")
    }

    @Test fun `a faithful synonym is still rejected`() {
        // Doubtful means rejected: "set oven" for "preheat" drops the action word.
        no("When ready to bake, preheat oven to 350 degrees. Line a baking sheet with parchment paper.", "Set oven to 350 degrees. Line a baking sheet with parchment paper.")
    }

    @Test fun `it must be shorter, and there must be something`() {
        no("Stir well.", "Stir it well.")
        no("Stir well until smooth.", "   ")
        no("Stir well until smooth.", null)
        no("Stir well until smooth.", "Stir.", words = null)
    }

    @Test fun `a bullet, quotes and line breaks are tidied away`() {
        assertEquals("Stir until smooth.", ShortStepCheck.accept("Stir everything together until smooth.", "- \"Stir until\n smooth.\"", en))
    }

    @Test fun `decimal commas and other languages`() {
        val de = LanguageWords.forTag("de")!!
        ok("Nach und nach 1,5 l Brühe zugießen und dabei ständig rühren.", "1,5 l Brühe nach und nach zugießen, ständig rühren.", de)
        no("Nach und nach 1,5 l Brühe zugießen und dabei ständig rühren.", "1,5 l Brühe nach und nach zugießen.", de)
        no("Nach und nach 1,5 l Brühe zugießen und dabei ständig rühren.", "1.5 l Brühe zugießen.", de)
        val ja = LanguageWords.forTag("ja")!!
        ok("鍋に入れて、中火で５分煮る。ときどき混ぜる。", "鍋に入れ、中火で5分煮てときどき混ぜる。", ja)
        no("鍋に入れて、中火で５分煮る。ときどき混ぜる。", "中火で5分煮る。", ja)
        no("鍋に入れて、中火で５分煮る。ときどき混ぜる。", "鍋に入れ、中火で10分煮てときどき混ぜる。", ja)
    }

    @Test fun `words are compared only where the table has them`() {
        assertTrue(ShortStepCheck.keepsWords("Cover and simmer until tender.", "Cover; simmer until tender.", en))
        assertFalse(ShortStepCheck.keepsWords("Cover and simmer until tender.", "Simmer until tender.", en))
    }

    @Test fun `numbers are read as written`() {
        assertEquals(setOf("1,5", "1 1/2", "1½", "½", "10", "12"), ShortStepCheck.numbers("1,5 kg, 1 1/2 cups, 1 ½, ½, 10–12"))
    }
}
