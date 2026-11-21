package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tags under a Groceries row (#422). iOS: `GroceryRecipeTagsTests`, the same cases. */
class GroceryRecipeTagsTest {

    private fun item(id: Long, recipeId: Long?, day: Long? = null) =
        GroceryItem(id, "1 onion", "en", Aisle.PRODUCE, false, id.toInt(), recipeId, day)

    private val titles = mapOf(1L to "Soup", 2L to "  Stew  ", 3L to "Curry", 4L to "Pasta", 5L to "Tacos", 6L to "")

    @Test
    fun aTypedItemAndAGoneRecipeHaveNoTags() {
        assertTrue(GroceryRecipeTags.of(listOf(item(1, null)), titles).isEmpty())
        assertTrue(GroceryRecipeTags.of(listOf(item(1, 99)), titles).isEmpty())
        assertTrue(GroceryRecipeTags.of(listOf(item(1, 6)), titles).isEmpty())
    }

    @Test
    fun oneTagPerRecipeInTheOrderAddedWithItsDay() {
        val tags = GroceryRecipeTags.of(listOf(item(1, 2, 100), item(2, 1), item(3, 2, 100), item(4, null)), titles)
        assertEquals(listOf(RecipeTag(2, "Stew", 100), RecipeTag(1, "Soup", null)), tags)
    }

    @Test
    fun theSameRecipePlannedOnTwoDaysIsTwoTags() {
        val tags = GroceryRecipeTags.of(listOf(item(1, 1, 100), item(2, 1, 103)), titles)
        assertEquals(listOf(RecipeTag(1, "Soup", 100), RecipeTag(1, "Soup", 103)), tags)
    }

    @Test
    fun upToThreeShowWholeAndMoreShowTwoAndPlusN() {
        fun tags(n: Int) = (1..n).map { RecipeTag(it.toLong(), "R$it", null) }
        assertEquals(tags(1) to 0, GroceryRecipeTags.shown(tags(1)))
        assertEquals(tags(3) to 0, GroceryRecipeTags.shown(tags(3)))
        assertEquals(tags(2) to 2, GroceryRecipeTags.shown(tags(4)))
        assertEquals(tags(2) to 5, GroceryRecipeTags.shown(tags(7)))
        assertEquals(emptyList<RecipeTag>() to 0, GroceryRecipeTags.shown(emptyList()))
    }
}
