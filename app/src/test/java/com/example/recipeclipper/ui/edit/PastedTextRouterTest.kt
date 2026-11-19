package com.example.recipeclipper.ui.edit

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pasted text (#372) goes where the same text shared would ([com.example.recipeclipper.TextIntent]):
 * a link imports, a recipe or a week opens its review, a list is offered. Robolectric, because a
 * link's route is built with `Uri`. iOS: `PastedTextRouterTests`.
 */
@RunWith(AndroidJUnit4::class)
class PastedTextRouterTest {

    private val fixtures = PastedTextFixtures()

    private suspend fun open(text: String) = fixtures.router.open(text)

    @Test fun `a lone link imports, with or without its scheme`() = runTest {
        assertEquals(PasteTarget.Link("https://example.com/soup"), open("https://example.com/soup"))
        assertEquals(PasteTarget.Link("https://example.com/soup"), open("  example.com/soup \n"))
    }

    @Test fun `a link with a caption imports, as a share would`() = runTest {
        assertEquals(PasteTarget.Link("https://example.com/soup"), open("Great soup\nhttps://example.com/soup"))
    }

    @Test fun `a JSON recipe opens the editor, read once, in the inbox`() = runTest {
        val target = open(PastedTextFixtures.json) as PasteTarget.Review

        val entry = fixtures.inbox.take(target.key)
        assertNotNull(entry)
        assertEquals("Lemon Pasta", entry!!.reading.name)
        assertTrue(entry.reading.fromChat)
        assertNull("taken once", fixtures.inbox.take(target.key))
    }

    @Test fun `a week of recipes with days opens the meal-plan review`() = runTest {
        val target = open(PastedTextFixtures.plan) as PasteTarget.Plan

        assertEquals(listOf("Fried rice", "Omelette"), fixtures.inbox.takePlan(target.key)!!.plan.items.map { it.recipe.name })
    }

    @Test fun `a recipe written out opens the editor, sorted`() = runTest {
        val target = open(PastedTextFixtures.prose) as PasteTarget.Review

        assertTrue(fixtures.inbox.take(target.key)!!.reading.sorted)
    }

    @Test fun `a bulleted list is offered on Groceries`() = runTest {
        assertEquals(PasteTarget.Groceries, open("- milk\n- eggs\n- flour"))
        assertEquals("- milk\n- eggs\n- flour", fixtures.lists.take())
    }

    @Test fun `numbers with a dot are not a link`() = runTest {
        val target = open("1.5 cups flour") as PasteTarget.Review

        assertEquals(false, fixtures.inbox.take(target.key)!!.reading.sorted)
    }

    @Test fun `empty text opens nothing`() = runTest {
        assertNull(open(""))
        assertNull(open("  \n "))
    }
}
