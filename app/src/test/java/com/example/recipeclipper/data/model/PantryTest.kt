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

    // #194: what has run out is one last section, in either sort; running low stays in its aisle.
    @Test fun `run out items are one last section, and running low stays in its aisle`() {
        val items = listOf(
            item("rice", aisle = Aisle.GRAINS), item("milk", inStock = false, aisle = Aisle.DAIRY, expires = 20_730),
            item("apples", aisle = Aisle.PRODUCE).copy(runningLow = true), item("oats", inStock = false, aisle = Aisle.GRAINS)
        )
        val byAisle = PantryList.arrange(items, "", PantrySort.AISLE)
        assertEquals(listOf(Aisle.PRODUCE, Aisle.GRAINS, null), byAisle.map { it.aisle })
        assertEquals(listOf(false, false, true), byAisle.map { it.runOut })
        assertEquals(listOf("milk", "oats"), byAisle.last().items.map { it.name })
        assertEquals(PantryStock.RUNNING_LOW, byAisle[0].items.single().stock)

        val byExpiry = PantryList.arrange(items, "", PantrySort.EXPIRY)
        assertEquals(listOf(listOf("apples", "rice"), listOf("milk", "oats")), byExpiry.map { s -> s.items.map { it.name } })
        assertEquals(listOf(false, true), byExpiry.map { it.runOut })

        val onlyOut = PantryList.arrange(items, "oats", PantrySort.EXPIRY)
        assertEquals(listOf(true), onlyOut.map { it.runOut })
    }

    @Test fun `the stock is run out whenever the item is out, running low only in stock`() {
        assertEquals(PantryStock.IN_STOCK, item("a").stock)
        assertEquals(PantryStock.RUNNING_LOW, item("b").copy(runningLow = true).stock)
        assertEquals(PantryStock.RUN_OUT, item("c", inStock = false).copy(runningLow = true).stock)
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
        // A listed pair's number aside (#191).
        val onions = item("Onions")
        assertEquals(onions, PantryList.sameName(listOf(onions), "onion", "en"))
        assertNull(PantryList.sameName(listOf(onions), "red onion", "en"))
    }

    // --- Matching: presence, by the end-of-name rule

    @Test fun `a line is covered when its ingredient is in stock, by the end of its name`() {
        val pantry = listOf(item("Butter"), item("flour"))
        assertTrue(PantryMatch.covered("2 tbsp unsalted butter, softened", "en", pantry))
        assertTrue(PantryMatch.covered("2 cups all-purpose flour", "en", pantry))
        assertFalse(PantryMatch.covered("1 can butter beans", "en", pantry)) // not butter
        assertFalse(PantryMatch.covered("2 eggs", "en", pantry))
    }

    @Test fun `running low is still covered`() {
        assertTrue(PantryMatch.covered("2 cups flour", "en", listOf(item("flour").copy(runningLow = true))))
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

    // --- Listed singular/plural pairs (#191)

    @Test fun `a listed pair is Have in either number, but a different onion is Buy`() {
        // Walkthrough 03: "onions" in the pantry, "1 onion, sliced" in the recipe.
        assertTrue(PantryMatch.covered("1 onion, sliced", "en", listOf(item("onions"))))
        assertTrue(PantryMatch.covered("2 large eggs", "en", listOf(item("egg"))))
        assertTrue(PantryMatch.covered("1 red onion", "en", listOf(item("Red Onions"))))
        // The owner: red onions are different, as yellow onions are from white.
        assertFalse(PantryMatch.covered("1 red onion", "en", listOf(item("yellow onions"))))
        assertFalse(PantryMatch.covered("1 red onion", "en", listOf(item("onions"))))
        assertFalse(PantryMatch.covered("2 onions", "en", listOf(item("red onion"))))
        assertFalse(PantryMatch.covered("1 tsp onion powder", "en", listOf(item("onions"))))
        assertFalse(PantryMatch.covered("1 cup pea shoots", "en", listOf(item("peas"))))
    }

    @Test fun `a listed pair's lines are one row of What I need, named by the first`() {
        val needs = PantryMatch.weekNeeds(
            listOf(source("Soup", "1 onion", "1 red onion"), source("Stew", "2 onions, sliced", recipeId = 2)),
            listOf(item("Onions"))
        )
        val onion = needs.have.single()
        assertEquals("onion", onion.name)
        assertEquals("Onions", onion.pantryName)
        assertEquals(listOf("1 onion", "2 onions, sliced"), onion.lines.map { it.text })
        assertEquals(listOf("red onion"), needs.buy.map { it.name })
    }

    @Test fun `on list is the item's own name, a listed pair's number aside`() {
        fun grocery(id: Long, text: String, checked: Boolean = false) = GroceryItem(id, text, "en", Aisle.PRODUCE, checked, id.toInt())
        val list = listOf(grocery(1, "onion"), grocery(2, "2 onions"), grocery(3, "red onions"), grocery(4, "Onions", checked = true))
        assertEquals(listOf(1L), PantryList.ownLines(item("Onions"), list).map { it.id })
        assertEquals(listOf(3L), PantryList.ownLines(item("red onion"), list).map { it.id })
    }

    @Test fun `nothing planned is empty`() {
        assertTrue(PantryMatch.weekNeeds(emptyList(), listOf(item("flour"))).isEmpty)
    }
}
