package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoPhotoTest {

    @Test fun `initial skips a leading article of the recipe's language`() {
        assertEquals("B", NoPhoto.initial("The Best Lasagna", "en"))
        assertEquals("A", NoPhoto.initial("Der Apfelkuchen", "de"))
        assertEquals("T", NoPhoto.initial("La Tarte Tatin", "fr-FR"))
        assertEquals("A", NoPhoto.initial("L'agneau aux olives", "fr"))
        assertEquals("I", NoPhoto.initial("L'insalata Caprese", "it"))
        assertEquals("B", NoPhoto.initial("O Bolo de Cenoura", "pt-BR"))
    }

    @Test fun `an article of another language is a word of this one`() {
        // "Der" is no English article; "The" is no German one.
        assertEquals("D", NoPhoto.initial("Der Apfelkuchen", "en"))
        assertEquals("T", NoPhoto.initial("The Best Lasagna", "de"))
    }

    @Test fun `a title that is only an article keeps it`() {
        assertEquals("T", NoPhoto.initial("The", "en"))
        assertEquals("L", NoPhoto.initial("Le", "fr"))
    }

    @Test fun `an article needs a word break, not just the same letters`() {
        assertEquals("T", NoPhoto.initial("Theory of Cake", "en"))
        assertEquals("A", NoPhoto.initial("Apple Pie", "en"))
    }

    @Test fun `the first letter is taken past digits and punctuation`() {
        assertEquals("I", NoPhoto.initial("5-Ingredient Pasta", "en"))
        assertEquals("G", NoPhoto.initial("\"Grandma's\" Lentil Soup", "en"))
        assertEquals("É", NoPhoto.initial("épinards à la crème", "fr"))
    }

    @Test fun `a title with no letter has no initial`() {
        assertNull(NoPhoto.initial("12345", "en"))
        assertNull(NoPhoto.initial("   ", "en"))
        assertNull(NoPhoto.initial("", "en"))
    }

    @Test fun `labels are the first names without amount or unit, as written`() {
        val lines = listOf("1 cup red lentils, rinsed", "2 carrots, diced", "1 onion, chopped", "2 tsp ground cumin")
        assertEquals(listOf("red lentils", "carrots", "onion"), NoPhoto.labels(lines, "en", 3))
        assertEquals(listOf("red lentils"), NoPhoto.labels(lines, "en", 1))
        assertTrue(NoPhoto.labels(lines, "en", 0).isEmpty())
    }

    @Test fun `labels keep the line's own capitals and hyphens`() {
        assertEquals(listOf("Zucker", "Mehl", "Äpfel"), NoPhoto.labels(listOf("2 EL Zucker", "250 g Mehl", "3 Äpfel"), "de", 3))
        assertEquals(listOf("all-purpose flour"), NoPhoto.labels(listOf("2 1/2 cups all-purpose flour"), "en", 1))
        assertEquals(listOf("d'olives vertes"), NoPhoto.labels(listOf("200 g d'olives vertes"), "fr", 1))
    }

    @Test fun `labels skip headings, staples and what has no name`() {
        val lines = listOf(
            "For the sauce:", "Salt and pepper", "1 tsp kosher salt", "2 tbsp olive oil", "Water",
            "Juice of 1 lemon", "12 lasagna noodles", "1 lb ground beef"
        )
        assertEquals(listOf("lasagna noodles", "ground beef"), NoPhoto.labels(lines, "en", 3))
    }

    @Test fun `a staple is matched whole, never at the end of a longer name`() {
        assertEquals(listOf("bell pepper"), NoPhoto.labels(listOf("1 bell pepper", "Pepper"), "en", 3))
        assertEquals(listOf("peanut oil"), NoPhoto.labels(listOf("2 tbsp peanut oil"), "en", 3))
    }

    @Test fun `each language reads its own staples`() {
        assertEquals(listOf("Zwiebeln", "Petersilie"), NoPhoto.labels(
            listOf("500 g Zwiebeln, gewürfelt", "Wasser", "Salz und Pfeffer", "1 Bund Petersilie"), "de", 3
        ))
        assertEquals(listOf("tomates maduros", "pepino"), NoPhoto.labels(
            listOf("1 kg tomates maduros", "1 pepino", "Aceite de oliva", "Sal"), "es", 3
        ))
    }

    @Test fun `the same name twice is shown once`() {
        assertEquals(listOf("red lentils"), NoPhoto.labels(listOf("red lentils", "Red lentils"), "en", 3))
    }

    @Test fun `no ingredient lines, only staples or a language with no words give no labels`() {
        assertTrue(NoPhoto.labels(emptyList(), "en", 3).isEmpty())
        assertTrue(NoPhoto.labels(listOf("Salt", "Water", "Olive oil"), "en", 3).isEmpty())
        assertTrue(NoPhoto.labels(listOf("1 onion"), "xx", 3).isEmpty())
    }

    @Test fun `the tint is stable and in range`() {
        val uids = (1..200).map { "uid-$it" }
        assertTrue(uids.all { NoPhoto.tintIndex(it) in 0 until NoPhoto.TINTS })
        assertEquals(NoPhoto.tintIndex("uid-7"), NoPhoto.tintIndex("uid-7"))
        // All five tints are used.
        assertEquals(NoPhoto.TINTS, uids.map { NoPhoto.tintIndex(it) }.toSet().size)
        // Pinned, so a change of hash (which would recolour every recipe) fails here.
        assertEquals(0, NoPhoto.tintIndex("5b2f0a52-1c7e-4a8d-9d55-0f1b7a4c1e01"))
        assertEquals(2, NoPhoto.tintIndex("uid-2"))
        // The empty uid is FNV-1a's offset basis, 2166136261.
        assertEquals(1, NoPhoto.tintIndex(""))
    }

    @Test fun `the tint follows the uid, not the title`() {
        val a = NoPhoto.art(NoPhotoSource("same", "Lasagna"), 1)
        val b = NoPhoto.art(NoPhotoSource("same", "Renamed"), 1)
        assertEquals(a.tint, b.tint)
    }

    @Test fun `art gathers the letter, the names and the tint`() {
        val art = NoPhoto.art(NoPhotoSource("uid-2", "A Simple Roast Chicken", "en", listOf("1 whole chicken (4 lb)", "1 lemon, halved")), 1)
        assertEquals(NoPhotoArt("S", listOf("whole chicken"), 2), art)
    }
}
