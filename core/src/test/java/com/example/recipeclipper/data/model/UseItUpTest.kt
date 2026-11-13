package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UseItUpTest {

    private val today = 20_000L

    private fun item(
        name: String,
        expires: Long?,
        inStock: Boolean = true,
        alwaysHave: Boolean = false,
        language: String = "en",
        id: Long = name.hashCode().toLong()
    ) = PantryItem(id, name, null, language, Aisle.OTHER, inStock, alwaysHave, null, expires)

    private fun recipe(id: Long, title: String, vararg lines: String, language: String = "en") =
        SavedRecipe(id, title, null, language, lines.toList())

    @Test fun `soon is the reminder's window, today and tomorrow`() {
        assertTrue(UseItUp.soon(item("spinach", today), today))
        assertTrue(UseItUp.soon(item("spinach", today + 1), today))
        assertFalse(UseItUp.soon(item("spinach", today + 2), today))
        assertFalse(UseItUp.soon(item("spinach", today - 1), today))
        assertFalse(UseItUp.soon(item("spinach", null), today))
        assertFalse(UseItUp.soon(item("spinach", today, inStock = false), today))
        assertFalse(UseItUp.soon(item("spinach", today, alwaysHave = true), today))
    }

    @Test fun `a recipe uses an item its lines name, by name only`() {
        val butter = item("butter", today)
        assertTrue(UseItUp.uses(recipe(1, "Cake", "2 tbsp unsalted butter, softened"), butter))
        assertFalse(UseItUp.uses(recipe(1, "Stew", "1 can butter beans"), butter))
        assertFalse(UseItUp.uses(recipe(1, "Bread", "2 cups flour"), item("rice flour", today)))
        assertFalse(UseItUp.uses(recipe(1, "Bread", "2 cups rice flour"), item("flour", today)))
    }

    @Test fun `only the same language matches, and headings are skipped`() {
        assertFalse(UseItUp.uses(recipe(1, "Kuchen", "200 g Butter", language = "de"), item("butter", today)))
        assertTrue(UseItUp.uses(recipe(1, "Kuchen", "200 g Butter", language = "de"), item("Butter", today, language = "de")))
        assertFalse(UseItUp.uses(recipe(1, "Pie", "For the spinach:"), item("spinach", today)))
    }

    // #332: the lines are parsed once per recipe, then checked against every item expiring.
    @Test fun `a recipe's names are read once, a heading aside, and checked against each item`() {
        val names = UseItUp.names(recipe(1, "Pie", "For the filling:", "300 g spinach", "1 cup milk"))!!
        assertEquals("en", names.language)
        assertEquals(listOf("spinach", "milk"), names.names)
        assertTrue(UseItUp.uses(names, item("spinach", today)))
        assertTrue(UseItUp.uses(names, item("milk", today)))
        assertFalse(UseItUp.uses(names, item("eggs", today)))
        assertFalse(UseItUp.uses(names, item("Milch", today, language = "de")))
        assertNull(UseItUp.names(recipe(2, "Pie", "300 g spinach", language = "xx")))
        assertEquals(emptyList<UseItUpGroup>(), UseItUp.groups(listOf(item("spinach", today + 3)), listOf(recipe(1, "Pie", "300 g spinach")), today))
    }

    @Test fun `a recipe stored with no language is detected`() {
        val adobo = SavedRecipe(1, "Chicken Adobo", null, null, listOf("2 lb chicken thighs", "1/2 cup soy sauce"))
        assertTrue(UseItUp.uses(adobo, item("chicken thighs", today)))
    }

    @Test fun `groups list soon items with recipes, soonest first, recipes A-Z`() {
        val spinach = item("spinach", today + 1)
        val milk = item("milk", today)
        val eggs = item("eggs", today) // no recipe uses it
        val later = item("cream", today + 2)
        val saved = listOf(
            recipe(1, "Saag", "500 g spinach"),
            recipe(2, "bake", "1 cup milk", "200 g spinach"),
            recipe(3, "Alfredo", "1 cup cream")
        )
        val groups = UseItUp.groups(listOf(spinach, milk, eggs, later), saved, today)
        assertEquals(listOf(milk, spinach), groups.map { it.item })
        assertEquals(listOf(2L), groups[0].recipes.map { it.id })
        assertEquals(listOf(2L, 1L), groups[1].recipes.map { it.id })
    }

    @Test fun `plan days are today, then the use-by day`() {
        assertEquals(listOf(today), UseItUp.planDays(item("milk", today), today))
        assertEquals(listOf(today, today + 1), UseItUp.planDays(item("milk", today + 1), today))
    }
}
