package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [StepPhotos] (#433): numbering by the "(n)" markers, lining up, an edit, and storage. */
class StepPhotosTest {

    private val a = "https://img.example/a.jpg"
    private val b = "https://img.example/b.jpg"
    private val c = "https://img.example/c.jpg"
    private val d = "https://img.example/d.jpg"

    @Test
    fun `markers are bare numbers in brackets, in order`() {
        assertEquals(listOf(3, 4), StepPhotos.markers("Sbuccia le mele (3) (4)."))
        assertEquals(listOf(5), StepPhotos.markers("Ripiega sul ripieno (5), in modo da formare un fagottino."))
        assertEquals(emptyList<Int>(), StepPhotos.markers("Bake (about 20 minutes) at 180 °C (350 °F)."))
        assertEquals(emptyList<Int>(), StepPhotos.markers("Use (123) or ( 4 )."))
    }

    @Test
    fun `markers number a step's photos when there are as many, else their place does`() {
        val steps = listOf("Mix (1).", "Peel (3) (4).", "Heat the oven.", "Bake (9).", "Serve.")
        val photos = listOf(listOf(a), listOf(b, c), emptyList(), listOf(d), listOf(a, b))
        val numbered = StepPhotos.numbered(steps, photos)
        assertEquals(listOf(listOf(1), listOf(3, 4), emptyList(), listOf(9), listOf(5, 6)), numbered.map { s -> s.map { it.number } })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), StepPhotos.all(numbered).map { it.position })
        assertEquals(listOf(0, 1, 1, 3, 4, 4), StepPhotos.all(numbered).map { it.step })
    }

    @Test
    fun `a step with two photos and one marker is numbered by place`() {
        val numbered = StepPhotos.numbered(listOf("Peel (3).", "Bake."), listOf(listOf(a, b), listOf(c)))
        assertEquals(listOf(1, 2, 3), StepPhotos.all(numbered).map { it.number })
    }

    @Test
    fun `no photos number nothing, and a short list is lined up`() {
        assertEquals(emptyList<List<StepPhoto>>(), StepPhotos.numbered(listOf("Mix."), emptyList()))
        assertEquals(emptyList<List<StepPhoto>>(), StepPhotos.numbered(listOf("Mix."), listOf(emptyList())))
        val numbered = StepPhotos.numbered(listOf("Mix (1).", "Bake."), listOf(listOf(a)))
        assertEquals(2, numbered.size)
        assertEquals(emptyList<StepPhoto>(), numbered[1])
        assertEquals(listOf(listOf(a), emptyList()), StepPhotos.aligned(listOf(listOf(a), listOf(b), listOf(c)).take(1), 2))
        assertEquals(listOf(listOf(a)), StepPhotos.aligned(listOf(listOf(a), listOf(b)), 1))
        assertEquals(emptyList<List<String>>(), StepPhotos.aligned(listOf(emptyList(), listOf(b)), 1))
    }

    @Test
    fun `an edited step keeps its photos, a moved one carries them, a removed one loses them`() {
        val old = listOf("Mix (1).", "Peel (2).", "Bake (3).")
        val photos = listOf(listOf(a), listOf(b), listOf(c))
        // Text changed in place.
        assertEquals(photos, StepPhotos.afterEdit(old, photos, listOf("Mix well (1).", "Peel (2).", "Bake (3).")))
        // Reordered.
        assertEquals(listOf(listOf(c), listOf(a), listOf(b)), StepPhotos.afterEdit(old, photos, listOf("Bake (3).", "Mix (1).", "Peel (2).")))
        // Removed.
        assertEquals(listOf(listOf(a), listOf(c)), StepPhotos.afterEdit(old, photos, listOf("Mix (1).", "Bake (3).")))
        // Added.
        assertEquals(
            listOf(listOf(a), emptyList(), listOf(b), listOf(c)),
            StepPhotos.afterEdit(old, photos, listOf("Mix (1).", "Rest.", "Peel (2).", "Bake (3)."))
        )
        // Added and changed at once: the unchanged ones keep theirs, the rest can't be paired.
        assertEquals(
            listOf(listOf(a), emptyList(), emptyList(), listOf(c)),
            StepPhotos.afterEdit(old, photos, listOf("Mix (1).", "Rest.", "Peel well.", "Bake (3)."))
        )
        // Everything removed but one changed step.
        assertEquals(emptyList<List<String>>(), StepPhotos.afterEdit(old, photos, listOf("Something else.")))
        // A recipe with no photos stays without.
        assertEquals(emptyList<List<String>>(), StepPhotos.afterEdit(old, emptyList(), listOf("Mix (1).")))
    }

    @Test
    fun `an edit through the draft keeps the photos`() {
        val recipe = Recipe(
            name = "Pie", image = null, ingredients = listOf("1 egg"), instructions = listOf("Mix (1).", "Bake (2)."),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "https://x.example/pie",
            stepPhotos = listOf(listOf(a), listOf(b))
        )
        val edited = RecipeDraft.of(recipe).copy(instructionsText = "Bake (2).\nMix it all (1).").applyTo(recipe)
        assertEquals(listOf(listOf(b), listOf(a)), edited.stepPhotos)
    }

    @Test
    fun `stored as JSON, read back, and anything odd reads as none`() {
        val photos = listOf(listOf(a), emptyList(), listOf(b, c))
        val json = StepPhotos.encode(photos)
        assertEquals(photos, StepPhotos.decode(json))
        assertNull(StepPhotos.encode(emptyList()))
        assertNull(StepPhotos.encode(listOf(emptyList(), emptyList())))
        assertEquals(emptyList<List<String>>(), StepPhotos.decode(null))
        assertEquals(emptyList<List<String>>(), StepPhotos.decode(""))
        assertEquals(emptyList<List<String>>(), StepPhotos.decode("not json"))
        assertEquals(emptyList<List<String>>(), StepPhotos.decode("{\"a\":1}"))
        assertEquals(emptyList<List<String>>(), StepPhotos.decode("[[]]"))
        // A link that isn't a web image is dropped as it is read.
        assertEquals(listOf(listOf(a), emptyList()), StepPhotos.decode("[[\"file:///x.jpg\",\"$a\"],[1]]"))
    }
}
