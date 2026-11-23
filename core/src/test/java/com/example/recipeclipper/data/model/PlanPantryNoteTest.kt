package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Plan's quiet pantry note (#456): which planned ingredients the pantry hasn't got, by name. */
class PlanPantryNoteTest {

    private fun item(name: String, inStock: Boolean = true, alwaysHave: Boolean = false, language: String = "en") =
        PantryItem(name.hashCode().toLong(), name, null, language, Aisle.OTHER, inStock, alwaysHave, null, null)

    private fun gap(pantry: List<PantryItem>, vararg lines: String, language: String? = "en") =
        PlanPantryNote.gap("Recipe", language, lines.toList(), pantry)

    private fun meal(id: Long, day: Long, vararg lines: String, recipe: Boolean = true) = PlannedMeal(
        id = id, day = day, mealTypeId = 1, recipeId = if (recipe) id else null, title = if (recipe) "Recipe $id" else null,
        imageUrl = null, servings = null, note = if (recipe) null else "Leftovers", language = if (recipe) "en" else null,
        ingredients = lines.toList()
    )

    @Test fun `everything in the pantry says nothing`() {
        assertNull(gap(listOf(item("flour"), item("eggs")), "2 cups flour", "3 large eggs"))
    }

    @Test fun `an empty pantry says nothing`() {
        assertNull(gap(emptyList(), "2 cups flour", "1 tbsp tahini"))
    }

    @Test fun `a pantry with nothing in the recipe's language says nothing`() {
        assertNull(gap(listOf(item("Mehl", language = "de")), "2 cups flour", "1 tbsp tahini"))
    }

    @Test fun `the missing names, in the recipe's order`() {
        assertEquals(
            PantryGap(listOf("tahini", "sumac")),
            gap(listOf(item("flour")), "2 cups flour", "2 tbsp tahini", "1 tsp sumac")
        )
    }

    @Test fun `three are named, past that two and how many more`() {
        val pantry = listOf(item("flour"))
        val three = gap(pantry, "2 tbsp tahini", "1 tsp sumac", "1 lemon")!!
        assertEquals(listOf("tahini", "sumac", "lemon"), three.names)
        assertEquals(0, three.more)
        assertFalse(three.capped)

        val five = gap(pantry, "2 tbsp tahini", "1 tsp sumac", "1 lemon", "2 cloves garlic", "1 cup chickpeas")!!
        assertEquals(listOf("tahini", "sumac"), five.names)
        assertEquals(3, five.more)
        assertTrue(five.capped)
        // Every name is kept, for the note opened in place.
        assertEquals(listOf("tahini", "sumac", "lemon", "garlic", "chickpeas"), five.missing)
    }

    @Test fun `headings, blank lines and lines with no name are not counted`() {
        assertNull(gap(listOf(item("flour")), "For the dough:", "", "2 cups flour", "salt and pepper to taste"))
    }

    @Test fun `rice flour is never flour, and the other way round`() {
        assertEquals(PantryGap(listOf("rice flour")), gap(listOf(item("flour")), "2 cups rice flour"))
        assertEquals(PantryGap(listOf("flour")), gap(listOf(item("rice flour")), "2 cups flour"))
        assertNull(gap(listOf(item("flour")), "2 cups all-purpose flour"))
    }

    @Test fun `a staple is had, an item out of stock is missing, the quantity never matters`() {
        assertNull(gap(listOf(item("salt", inStock = false, alwaysHave = true), item("butter")), "1 tsp salt", "2 kg butter"))
        assertEquals(PantryGap(listOf("butter")), gap(listOf(item("butter", inStock = false)), "2 tbsp butter"))
    }

    @Test fun `one name per ingredient`() {
        assertEquals(PantryGap(listOf("onion")), gap(listOf(item("flour")), "1 onion, diced", "2 onions, sliced"))
    }

    @Test fun `a language with no tables says nothing`() {
        assertNull(gap(listOf(item("butter", language = "xx")), "1 cup butter", language = "xx"))
    }

    @Test fun `the model's same answer turns missing into had`() {
        val decided = Decisions(mapOf(DecisionQuestion.sameIngredient("scallions", "green onion", "en") to "same"))
        assertNull(PlanPantryNote.gap("r", "en", listOf("2 scallions"), listOf(item("green onion")), decided))
    }

    @Test fun `gaps skip notes, the past and an empty pantry`() {
        val pantry = listOf(item("flour"))
        val meals = listOf(
            meal(1, 9, "1 tbsp tahini"),
            meal(2, 10, "1 tbsp tahini", "2 cups flour"),
            meal(3, 11, "2 cups flour"),
            meal(4, 12, recipe = false)
        )
        assertEquals(mapOf(2L to PantryGap(listOf("tahini"))), PlanPantryNote.gaps(meals, pantry, today = 10))
        assertTrue(PlanPantryNote.gaps(meals, emptyList(), today = 10).isEmpty())
    }

    // --- The grocery list (#456)

    private fun grocery(text: String, checked: Boolean = false, language: String? = "en") =
        GroceryItem(text.hashCode().toLong(), text, language, Aisle.OTHER, checked, 0)

    @Test fun `a missing name already on the grocery list is counted, not named`() {
        val pantry = listOf(item("flour"))
        val lines = arrayOf("2 tbsp tahini", "1 tsp sumac", "2 cups flour")
        assertEquals(
            PantryGap(listOf("sumac"), onList = 1),
            PlanPantryNote.gap("r", "en", lines.toList(), pantry, groceries = listOf(grocery("2 tbsp tahini")))
        )
        val all = PlanPantryNote.gap("r", "en", lines.toList(), pantry, groceries = listOf(grocery("tahini"), grocery("1 jar sumac")))!!
        assertTrue(all.allOnList)
        assertEquals(2, all.onList)
    }

    @Test fun `a ticked grocery line, another language or another ingredient doesn't count`() {
        val pantry = listOf(item("flour"))
        val lines = listOf("2 cups rice flour")
        assertEquals(PantryGap(listOf("rice flour")), PlanPantryNote.gap("r", "en", lines, pantry, groceries = listOf(grocery("rice flour", checked = true))))
        assertEquals(PantryGap(listOf("rice flour")), PlanPantryNote.gap("r", "en", lines, pantry, groceries = listOf(grocery("flour"))))
        assertEquals(PantryGap(listOf("rice flour")), PlanPantryNote.gap("r", "en", lines, pantry, groceries = listOf(grocery("rice flour", language = "de"))))
    }

    @Test fun `lines to add are the rendered lines naming what is still missing`() {
        val gap = PantryGap(listOf("tahini", "onion"))
        assertEquals(
            listOf("3 tbsp tahini", "2 onions, sliced"),
            PlanPantryNote.linesToAdd(gap, "en", listOf("For the sauce:", "3 tbsp tahini", "1 cup flour", "2 onions, sliced", "salt to taste"))
        )
        assertTrue(PlanPantryNote.linesToAdd(gap, "xx", listOf("3 tbsp tahini")).isEmpty())
    }
}
