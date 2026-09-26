package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Chef mode's gate (#100): a short step shows only if it keeps the step's numbers honest. */
class ShortStepCheckTest {

    private val en = LanguageWords.ENGLISH

    private fun ok(original: String, short: String, words: LanguageWords? = en) =
        assertEquals(ShortStepCheck.tidy(short), ShortStepCheck.accept(original, short, words))

    private fun no(original: String, short: String?, words: LanguageWords? = en) =
        assertNull(ShortStepCheck.accept(original, short, words))

    @Test fun `a shorter step with the same numbers passes`() {
        ok("Preheat the oven to 350°F (180°C) and grease a 9x13-inch baking pan.", "Oven to 350°F (180°C); grease a 9x13-inch pan.")
        ok("Bake for 25 to 30 minutes, until the top is golden.", "Bake 25–30 min until golden.")
        ok("Add 1 1/2 cups of the flour and mix gently until combined.", "Add 1 1/2 cups flour; mix.")
        ok("Stir in ½ teaspoon of salt until it dissolves.", "Stir in ½ tsp salt.")
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
        ok("Preheat the oven to 350°F (180°C) with a rack in the middle.", "Oven to 350°F, rack in middle.")
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
        ok("Nach und nach 1,5 l Brühe zugießen und dabei ständig rühren.", "1,5 l Brühe nach und nach zugießen.", de)
        no("Nach und nach 1,5 l Brühe zugießen und dabei ständig rühren.", "1.5 l Brühe zugießen.", de)
        val ja = LanguageWords.forTag("ja")!!
        ok("鍋に入れて、中火で５分煮る。ときどき混ぜる。", "中火で5分煮る。", ja)
        no("鍋に入れて、中火で５分煮る。ときどき混ぜる。", "中火で10分煮る。", ja)
    }

    @Test fun `numbers are read as written`() {
        assertEquals(setOf("1,5", "1 1/2", "1½", "½", "10", "12"), ShortStepCheck.numbers("1,5 kg, 1 1/2 cups, 1 ½, ½, 10–12"))
    }
}
