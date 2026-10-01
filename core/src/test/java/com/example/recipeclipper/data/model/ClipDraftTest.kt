package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipSelectionTest {

    @Test fun `each line becomes one item`() {
        assertEquals(
            listOf("1 cup flour", "2 eggs", "½ tsp salt"),
            ClipSelection.lines("1 cup flour\n2 eggs\n½ tsp salt")
        )
    }

    @Test fun `every kind of line break splits`() {
        assertEquals(listOf("a", "b", "c", "d", "e"), ClipSelection.lines("a\r\nb\rc d e"))
    }

    @Test fun `blank lines are dropped and spaces collapsed`() {
        assertEquals(
            listOf("1 cup (226 g) butter", "2 eggs"),
            ClipSelection.lines("\n\n  1 cup  (226\tg)   butter  \n   \n2 eggs\n\n")
        )
    }

    @Test fun `nothing is guessed inside a line`() {
        assertEquals(
            listOf("1. Brown the butter. 2. Whisk in the sugar.", "• 3 cups oats"),
            ClipSelection.lines("1. Brown the butter. 2. Whisk in the sugar.\n• 3 cups oats")
        )
    }

    @Test fun `an empty or blank selection has no lines`() {
        assertEquals(emptyList<String>(), ClipSelection.lines(""))
        assertEquals(emptyList<String>(), ClipSelection.lines(" \n \n"))
    }

    @Test fun `a name joins its lines with one space`() {
        assertEquals("Brown Butter Oat Cookies", ClipSelection.name("Brown Butter\n  Oat Cookies \n"))
        assertEquals("", ClipSelection.name("\n "))
    }
}

class ClipDraftTest {

    private val url = "https://hearthandcrumb.example/cookies"
    private val empty = ClipDraft(url)

    @Test fun `assigning splits lines and records a mark`() {
        val draft = empty.assign(ClipField.INGREDIENTS, "1 cup flour\n\n2 eggs")
        assertEquals(listOf("1 cup flour", "2 eggs"), draft.ingredients)
        assertEquals(listOf(ClipMark("m1", ClipField.INGREDIENTS, listOf("1 cup flour", "2 eggs"))), draft.marks)
        assertEquals("m2", draft.pendingMarkId)
        assertEquals(2, draft.count(ClipField.INGREDIENTS))
    }

    // The owner's call (#237): separate blocks of a page add up.
    @Test fun `ingredients and steps add up, each add with its own mark`() {
        val draft = empty
            .assign(ClipField.INGREDIENTS, "a\nb")
            .assign(ClipField.INGREDIENTS, "1\n2\n3")
            .assign(ClipField.STEPS, "Mix.")
            .assign(ClipField.STEPS, "Bake.")
        assertEquals(listOf("a", "b", "1", "2", "3"), draft.ingredients)
        assertEquals(listOf("Mix.", "Bake."), draft.steps)
        assertEquals(5, draft.count(ClipField.INGREDIENTS))
        assertEquals(listOf("m1", "m2", "m3", "m4"), draft.marks.map { it.id })
    }

    @Test fun `a name is one line, and replaces, with its mark`() {
        val draft = empty.assign(ClipField.NAME, "Brown Butter\nOat Cookies").assign(ClipField.NAME, "Cookies")
        assertEquals("Cookies", draft.name)
        assertEquals(1, draft.count(ClipField.NAME))
        assertEquals(listOf(ClipMark("m2", ClipField.NAME, listOf("Cookies"))), draft.marks)
    }

    @Test fun `a blank selection changes nothing`() {
        val draft = empty.assign(ClipField.STEPS, "Mix.")
        assertSame(draft, draft.assign(ClipField.STEPS, " \n "))
        assertSame(draft, draft.assign(ClipField.NAME, ""))
        assertSame(draft, draft.assign(ClipField.PHOTO, " "))
    }

    @Test fun `the photo is the image address as given, and replaces`() {
        val draft = empty.assign(ClipField.PHOTO, " https://img.example/c.jpg ")
        assertEquals("https://img.example/c.jpg", draft.photo)
        assertEquals(1, draft.count(ClipField.PHOTO))
        assertEquals("m1", draft.marks.single { it.field == ClipField.PHOTO }.id)
        val again = draft.assign(ClipField.PHOTO, "https://img.example/d.jpg")
        assertEquals(listOf("m2"), again.marks.map { it.id })
    }

    @Test fun `removing an add takes out just its lines`() {
        val draft = empty.assign(ClipField.NAME, "Cookies")
            .assign(ClipField.STEPS, "Mix.\nBake.")
            .assign(ClipField.STEPS, "Cool.")
        val removed = draft.removeMark("m2")
        assertEquals(listOf("Cool."), removed.steps)
        assertEquals("Cookies", removed.name)
        assertEquals(listOf("m1", "m3"), removed.marks.map { it.id })
        assertEquals("", draft.removeMark("m1").name)
        assertSame(draft, draft.removeMark("m9"))
    }

    @Test fun `removing an add leaves a line edited by hand since, and repeats taken once`() {
        val draft = empty.assign(ClipField.INGREDIENTS, "salt\nflour").assign(ClipField.INGREDIENTS, "salt")
            .editLine(ClipField.INGREDIENTS, 1, "plain flour")
        assertEquals(listOf("plain flour", "salt"), draft.removeMark("m1").ingredients)
        // "salt" twice: the first add takes one with it, the second add's stays.
        val twice = empty.assign(ClipField.INGREDIENTS, "salt\nflour").assign(ClipField.INGREDIENTS, "salt")
        assertEquals(listOf("salt"), twice.removeMark("m1").ingredients)
    }

    @Test fun `clearing empties one field and drops only its marks`() {
        val draft = empty.assign(ClipField.NAME, "Cookies").assign(ClipField.STEPS, "Mix.\nBake.")
            .assign(ClipField.STEPS, "Cool.")
            .clear(ClipField.STEPS)
        assertEquals(emptyList<String>(), draft.steps)
        assertEquals("Cookies", draft.name)
        assertEquals(listOf("m1"), draft.marks.map { it.id })
        assertNull(empty.assign(ClipField.PHOTO, "x").clear(ClipField.PHOTO).photo)
    }

    @Test fun `mark ids never repeat, even after a clear`() {
        val draft = empty.assign(ClipField.NAME, "A").clear(ClipField.NAME).assign(ClipField.NAME, "B")
        assertEquals(listOf("m2"), draft.marks.map { it.id })
    }

    @Test fun `the next field is the first empty one, the photo last`() {
        assertEquals(ClipField.NAME, empty.nextField)
        assertEquals(ClipField.NAME, empty.assign(ClipField.PHOTO, "p").nextField)
        assertEquals(ClipField.STEPS, empty.assign(ClipField.NAME, "A").assign(ClipField.INGREDIENTS, "x").nextField)
        val full = empty.assign(ClipField.NAME, "A").assign(ClipField.INGREDIENTS, "x").assign(ClipField.STEPS, "y")
        assertEquals(ClipField.PHOTO, full.nextField)
        assertNull(full.assign(ClipField.PHOTO, "p").nextField)
    }
    @Test fun `finishing needs a name plus ingredients or steps`() {
        assertFalse(empty.canFinish)
        assertFalse(empty.assign(ClipField.NAME, "Cookies").canFinish)
        assertFalse(empty.assign(ClipField.INGREDIENTS, "flour").assign(ClipField.STEPS, "Mix.").canFinish)
        assertTrue(empty.assign(ClipField.NAME, "Cookies").assign(ClipField.INGREDIENTS, "flour").canFinish)
        assertTrue(empty.assign(ClipField.NAME, "Cookies").assign(ClipField.STEPS, "Mix.").canFinish)
    }

    @Test fun `blank lines left in review do not count`() {
        val draft = empty.assign(ClipField.NAME, "Cookies").addLine(ClipField.INGREDIENTS)
        assertFalse(draft.canFinish)
        assertEquals(0, draft.count(ClipField.INGREDIENTS))
    }

    @Test fun `empty means nothing assigned or typed`() {
        assertTrue(empty.isEmpty)
        assertTrue(empty.addLine(ClipField.STEPS).isEmpty)
        assertFalse(empty.copy(serves = "24").isEmpty)
        assertFalse(empty.assign(ClipField.PHOTO, "x").isEmpty)
        assertTrue(empty.assign(ClipField.NAME, "A").clear(ClipField.NAME).isEmpty)
    }

    @Test fun `review edits, removes and adds lines`() {
        val draft = empty.assign(ClipField.STEPS, "Mix.\nBake.\nCool.")
        assertEquals(listOf("Mix well.", "Bake.", "Cool."), draft.editLine(ClipField.STEPS, 0, "Mix well.").steps)
        assertEquals(listOf("Mix.", "Cool."), draft.removeLine(ClipField.STEPS, 1).steps)
        assertEquals(listOf("Mix.", "Bake.", "Cool.", ""), draft.addLine(ClipField.STEPS).steps)
        assertSame(draft, draft.editLine(ClipField.STEPS, 3, "x"))
        assertSame(draft, draft.removeLine(ClipField.STEPS, -1))
    }

    @Test fun `no recipe until it can finish`() {
        assertNull(empty.assign(ClipField.NAME, "Cookies").toRecipe())
    }

    @Test fun `the recipe is trimmed, drops blank lines, and takes serves and time only if typed`() {
        val recipe = empty
            .assign(ClipField.NAME, "Cookies")
            .assign(ClipField.INGREDIENTS, "flour\nsugar")
            .addLine(ClipField.INGREDIENTS)
            .editLine(ClipField.INGREDIENTS, 1, "  sugar  ")
            .assign(ClipField.PHOTO, "https://img.example/c.jpg")
            .toRecipe()!!
        assertEquals("Cookies", recipe.name)
        assertEquals(listOf("flour", "sugar"), recipe.ingredients)
        assertEquals(emptyList<String>(), recipe.instructions)
        assertEquals("https://img.example/c.jpg", recipe.image)
        assertEquals(url, recipe.sourceUrl)
        assertNull(recipe.yield)
        assertNull(recipe.totalTime)
        assertNull(recipe.prepTime)
        assertNull(recipe.cookTime)

        val typed = empty.assign(ClipField.NAME, "Cookies").assign(ClipField.STEPS, "Bake.")
            .copy(serves = " 24 cookies ", totalTime = "1h 30m").toRecipe()!!
        assertEquals("24 cookies", typed.yield)
        assertEquals("1h 30m", typed.totalTime)
    }
}
