package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pantry's pure rules (#51): sorting, search, the expiry badge, and Have/Buy. The iOS `PantryTests` mirror these. */
class PantryTest {

    private var nextId = 1L

    private fun item(
        name: String,
        inStock: Boolean = true,
        alwaysHave: Boolean = false,
        aisle: Aisle = Aisle.OTHER,
        expires: Long? = null,
        language: String? = "en"
    ) = PantryItem(nextId++, name, null, language, aisle, inStock, alwaysHave, null, expires)

    private fun source(title: String, vararg lines: String, day: Long? = 20_720, language: String? = "en", recipeId: Long = 1) =
        GrocerySource("s-$title", recipeId, title, day, language, lines.toList())

    // --- Sorting, search and the badge

    @Test fun `by aisle, aisles in their order and names A to Z within`() {
        val items = listOf(
            item("rice", aisle = Aisle.GRAINS), item("Butter", aisle = Aisle.DAIRY),
            item("apples", aisle = Aisle.PRODUCE), item("eggs", aisle = Aisle.DAIRY)
        )
        val sections = PantryList.arrange(items, "", PantrySort.AISLE)
        assertEquals(listOf(Aisle.PRODUCE, Aisle.DAIRY, Aisle.GRAINS), sections.map { it.aisle })
        assertEquals(listOf("Butter", "eggs"), sections[1].items.map { it.name })
    }

    @Test fun `by expiry, soonest first, undated last A to Z, one section`() {
        val items = listOf(item("zucchini"), item("milk", expires = 20_725), item("yogurt", expires = 20_721), item("bread"))
        val sections = PantryList.arrange(items, "", PantrySort.EXPIRY)
        assertEquals(1, sections.size)
        assertNull(sections[0].aisle)
        assertEquals(listOf("yogurt", "milk", "bread", "zucchini"), sections[0].items.map { it.name })
    }

    @Test fun `search is a case-insensitive part of the name, and finding nothing is no sections`() {
        val items = listOf(item("Plain flour"), item("rice flour"), item("butter"))
        assertEquals(listOf("Plain flour", "rice flour"), PantryList.arrange(items, " FLOUR ", PantrySort.AISLE).flatMap { s -> s.items.map { it.name } })
        assertTrue(PantryList.arrange(items, "cumin", PantrySort.AISLE).isEmpty())
    }

    @Test fun `the badge is expired before today, soon for today and three days after, else none`() {
        val today = 20_720L
        assertEquals(ExpiryBadge.EXPIRED, PantryList.badge(today - 1, today))
        assertEquals(ExpiryBadge.SOON, PantryList.badge(today, today))
        assertEquals(ExpiryBadge.SOON, PantryList.badge(today + 3, today))
        assertNull(PantryList.badge(today + 4, today))
        assertNull(PantryList.badge(null, today))
    }

    @Test fun `the same name is trimmed and case-insensitive, in the same language`() {
        val flour = item("Flour")
        assertEquals(flour, PantryList.sameName(listOf(flour), " flour ", "en"))
        assertNull(PantryList.sameName(listOf(flour), "flour", "de"))
        assertNull(PantryList.sameName(listOf(flour), "rice flour", "en"))
    }

    // --- Matching: presence, by the end-of-name rule

    @Test fun `a line is covered when its ingredient is in stock, by the end of its name`() {
        val pantry = listOf(item("Butter"), item("flour"))
        assertTrue(PantryMatch.covered("2 tbsp unsalted butter, softened", "en", pantry))
        assertTrue(PantryMatch.covered("2 cups all-purpose flour", "en", pantry))
        assertFalse(PantryMatch.covered("1 can butter beans", "en", pantry)) // not butter
        assertFalse(PantryMatch.covered("2 eggs", "en", pantry))
    }

    @Test fun `out of stock is not covered, a staple always is`() {
        assertFalse(PantryMatch.covered("1 cup milk", "en", listOf(item("milk", inStock = false))))
        assertTrue(PantryMatch.covered("1 tsp salt", "en", listOf(item("salt", inStock = false, alwaysHave = true))))
    }

    @Test fun `never across languages, and never for a line the app can't name`() {
        assertFalse(PantryMatch.covered("200 g Butter", "de", listOf(item("butter"))))
        assertFalse(PantryMatch.covered("salt and pepper", "en", listOf(item("salt"), item("pepper"))))
        assertFalse(PantryMatch.covered("2 eggs", null, listOf(item("eggs", language = null))))
    }

    @Test fun `a staple wins over an out-of-stock item of the same name`() {
        val out = item("oil", inStock = false)
        val staple = item("olive oil", alwaysHave = true)
        assertEquals(staple, PantryMatch.find("olive oil", "en", listOf(out, staple)))
        assertEquals(NeedStatus.STAPLE, PantryMatch.status(staple))
        assertEquals(NeedStatus.BUY, PantryMatch.status(out))
        assertEquals(NeedStatus.BUY, PantryMatch.status(null))
    }

    // --- The week's What I need

    @Test fun `lines naming the same ingredient are one row, Have when in stock, never summed`() {
        val needs = PantryMatch.weekNeeds(
            listOf(
                source("Pancakes", "2 cups flour", "2 eggs", recipeId = 1),
                source("Bread", "500 g flour", day = 20_722, recipeId = 2)
            ),
            listOf(item("Flour"))
        )
        assertEquals(1, needs.have.size)
        val flour = needs.have.single()
        assertEquals("flour", flour.name)
        assertEquals(NeedStatus.HAVE, flour.status)
        assertEquals("Flour", flour.pantryName)
        assertEquals(listOf("2 cups flour", "500 g flour"), flour.lines.map { it.text }) // as written, not added up
        assertEquals(listOf("Pancakes", "Bread"), flour.lines.map { it.title })
        assertEquals(listOf("eggs"), needs.buy.map { it.name })
    }

    @Test fun `staples are never on Buy, out-of-stock items are`() {
        val needs = PantryMatch.weekNeeds(
            listOf(source("Soup", "1 tsp salt", "1 cup milk")),
            listOf(item("salt", inStock = false, alwaysHave = true), item("milk", inStock = false))
        )
        assertEquals(listOf("milk"), needs.buy.map { it.name })
        assertEquals(NeedStatus.STAPLE, needs.have.single().status)
    }

    @Test fun `a line with no name stands alone on Buy, each one separately`() {
        val needs = PantryMatch.weekNeeds(
            listOf(source("Salad", "salt and pepper", "salt and pepper")),
            listOf(item("salt"), item("pepper"))
        )
        assertEquals(2, needs.buy.size)
        assertTrue(needs.buy.all { it.name == null && it.lines.size == 1 })
        assertTrue(needs.have.isEmpty())
    }

    @Test fun `the same name in two languages is two rows`() {
        val needs = PantryMatch.weekNeeds(
            listOf(source("A", "2 eggs", language = "en"), source("B", "2 eggs", language = "de")),
            emptyList()
        )
        assertEquals(2, needs.buy.size)
    }

    @Test fun `nothing planned is empty`() {
        assertTrue(PantryMatch.weekNeeds(emptyList(), listOf(item("flour"))).isEmpty)
    }
}
