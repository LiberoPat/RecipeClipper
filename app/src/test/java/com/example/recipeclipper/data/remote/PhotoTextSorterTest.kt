package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iOS suite (`PhotoTextSorterTests.swift`) has the same cases and expectations. */
class PhotoTextSorterTest {

    /** A recipe card as the recogniser reads it: a title, headers, a numbered method. */
    private val card = listOf(
        "Aunt June's Oatmeal Cookies",
        "Ingredients",
        "1 cup butter",
        "1 1/2 cups flour",
        "2 cups rolled oats",
        "Directions",
        "1. Cream the butter.",
        "2. Stir in the flour and oats.",
        "3. Bake at 350°F for 12 minutes."
    )

    private fun lines(texts: List<String>, unsure: Set<String> = emptySet()) =
        texts.map { PhotoLine(it, if (it in unsure) 0.3f else 1f) }

    @Test fun `a card's lines sort into ingredients and steps like a typed post`() {
        val reading = PhotoTextSorter.sort(lines(card))

        assertTrue(reading.sorted)
        assertEquals(listOf("1 cup butter", "1 1/2 cups flour", "2 cups rolled oats"), reading.ingredients)
        assertEquals(
            listOf("Cream the butter.", "Stir in the flour and oats.", "Bake at 350°F for 12 minutes."),
            reading.instructions
        )
        assertEquals(emptyList<String>(), reading.uncertain)
    }

    @Test fun `lines the recogniser was unsure of are marked as they are shown`() {
        val reading = PhotoTextSorter.sort(lines(card, unsure = setOf("1 1/2 cups flour", "3. Bake at 350°F for 12 minutes.")))

        // The step lost its "3." to the splitter and is still marked.
        assertEquals(listOf("1 1/2 cups flour", "Bake at 350°F for 12 minutes."), reading.uncertain)
    }

    @Test fun `a misread amount stays as read, never corrected`() {
        val misread = card.map { if (it == "1 cup butter") "l cup butter" else it }
        val reading = PhotoTextSorter.sort(lines(misread, unsure = setOf("l cup butter")))

        assertEquals("l cup butter", reading.ingredients.first())
        assertEquals(listOf("l cup butter"), reading.uncertain)
    }

    @Test fun `a mixed number that lost its space is checked however sure the recogniser was`() {
        // Read from a real card by Vision, at full confidence: "1 1/2 cups flour".
        val misread = card.map { if (it == "1 1/2 cups flour") "11/2 cups flour" else it }
        val reading = PhotoTextSorter.sort(lines(misread))

        assertEquals("11/2 cups flour", reading.ingredients[1])
        assertEquals(listOf("11/2 cups flour"), reading.uncertain)
    }

    @Test fun `checked lines keep the order they are shown in`() {
        val misread = card.map {
            when (it) {
                "1 cup butter" -> "1 cupbutter"
                "3. Bake at 350°F for 12 minutes." -> "3. Bake at 35O°F for 12 minutes."
                else -> it
            }
        }
        val reading = PhotoTextSorter.sort(lines(misread, unsure = setOf("2 cups rolled oats")))

        assertEquals(
            listOf("1 cupbutter", "2 cups rolled oats", "Bake at 35O°F for 12 minutes."),
            reading.uncertain
        )
    }

    @Test fun `amounts shaped like a misreading are suspect`() {
        listOf(
            "11/2 cups flour", "13/4 cups sugar", "21/2 tsp baking soda", "31/3 cups milk",
            "Bake for 11/2 hours.", "3/2 cup water",
            "l/2 cup sugar", "I/4 tsp salt", "O.5 kg potatoes", "1/Z cup milk", "1/S tsp salt",
            "1O eggs", "Bake at 35o°F.", "l2 eggs", "|/2 cup oil",
            "1 cupraisitos", "1 cupraisins", "2 tbspsugar", "1 teaspoonvanilla"
        ).forEach { assertTrue(it, PhotoTextSorter.suspect(it)) }
    }

    @Test fun `ordinary amounts are not suspect`() {
        listOf(
            "1 1/2 cups flour", "1/2 cup sugar", "3/4 tsp salt", "2/2 cups", "1-1/2 cups oats",
            "1/2-3/4 cup milk", "11/16 inch", "1 cup raisins", "1cup flour", "2 green onions",
            "3 carrots", "12 cupcake liners", "1oz chocolate", "1l milk", "10 oz spinach",
            "2 lb chicken", "350°F", "Bake at 350°F for 12 minutes.", "Cool for 10 min.Serve.",
            "Aunt June's Oatmeal Cookies", "Stir in 2 eggs", "0.5 kg potatoes", "1,5 kg flour"
        ).forEach { assertFalse(it, PhotoTextSorter.suspect(it)) }
    }

    @Test fun `no confidence is not low confidence`() {
        val reading = PhotoTextSorter.sort(card.map { PhotoLine(it, null) })

        assertEquals(emptyList<String>(), reading.uncertain)
    }

    @Test fun `a short unsure piece marks only a line that is exactly it`() {
        val reading = PhotoTextSorter.sort(lines(card + "1", unsure = setOf("1")))

        assertFalse(reading.uncertain.contains("1 cup butter"))
    }

    @Test fun `lines that don't sort come back unsorted, every one as read`() {
        val prose = listOf("Mix the butter and sugar,", "then the flour. Bake till golden.")
        val reading = PhotoTextSorter.sort(lines(prose, unsure = setOf("then the flour. Bake till golden.")))

        assertFalse(reading.sorted)
        assertEquals(prose, reading.ingredients)
        assertEquals(emptyList<String>(), reading.instructions)
        assertEquals(listOf("then the flour. Bake till golden."), reading.uncertain)
    }

    @Test fun `a photo with no text reads as empty`() {
        val reading = PhotoTextSorter.sort(listOf(PhotoLine("  "), PhotoLine("")))

        assertFalse(reading.sorted)
        assertTrue(reading.isEmpty)
    }

    @Test fun `a gallery's pictures read as one text, in order`() {
        // The front has the ingredients, the back the method.
        val front = card.take(5)
        val back = card.drop(5)
        val reading = PhotoTextSorter.sort(lines(front) + lines(back))

        assertTrue(reading.sorted)
        assertEquals(3, reading.ingredients.size)
        assertEquals(3, reading.instructions.size)
    }
}
