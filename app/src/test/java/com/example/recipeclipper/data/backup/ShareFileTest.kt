package com.example.recipeclipper.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared file (#149, phase 2): what is sent, how an older reader sees it, and what the
 * receiver's choice merges. Mirrored one for one in the iOS `ShareFileTests`, over the same
 * fixture, `shared/fixtures/backup/share-v1.recipeclipper`.
 */
class ShareFileTest {

    private fun recipe(id: String, url: String = "https://example.com/$id") = BackupRecipe(
        id = id, sourceUrl = url, sourceType = "BLOG", title = "Recipe $id", imageUrl = null,
        ingredients = listOf("1 egg", "2 cups flour"), instructions = listOf("Mix."), prepTime = null,
        cookTime = null, totalTime = null, servings = "4", lastViewedAt = 5, checkedIngredients = setOf(0, 1),
        notes = "Mine", language = "en", contentOrigin = "EDITED", editedAt = 3
    )

    private fun grocery(id: String, recipeId: String?, checked: Boolean = false) = BackupGroceryItem(
        id = id, text = "item $id", language = "en", aisle = "other", checked = checked,
        recipeId = recipeId, plannedDay = 20_000, updatedAt = 1
    )

    private fun pantry(id: String, name: String) = BackupPantryItem(
        id = id, name = name, quantity = "a bag", language = "en", aisle = "grains", inStock = true,
        alwaysHave = false, purchasedDay = 19_000, expiresDay = null, updatedAt = 1
    )

    @Test
    fun `the fixture reads as a share, and unknown keys are ignored`() {
        val file = decodeOrFail(fixture("share-v1.recipeclipper"))

        assertTrue(file.isShare)
        assertEquals(listOf("r-chicken", "r-cake"), file.recipes.map { it.id })
        assertEquals("MANUAL", file.recipes[1].contentOrigin)
        assertEquals(listOf("g-thighs", "g-lemon", "g-paper"), file.groceries.map { it.id })
        assertEquals(listOf("r-chicken", "r-chicken", null), file.groceries.map { it.recipeId })
        assertEquals(listOf("Basmati rice"), file.pantry.map { it.name })
        assertTrue(file.lists.isEmpty() && file.memberships.isEmpty() && file.mealPlan.isEmpty())
    }

    @Test
    fun `a backup is not a share, and writing one adds no kind`() {
        val backup = decodeOrFail(fixture("backup-v1.json"))
        assertFalse(backup.isShare)
        assertFalse(BackupJson.encode(backup).contains("\"kind\""))
    }

    @Test
    fun `a share survives a round trip, kind and all`() {
        val file = ShareFile.make(9, listOf(recipe("a")), listOf(grocery("g", "a")))
        val text = BackupJson.encode(file)

        assertTrue(text.contains("\"kind\": \"share\""))
        assertTrue(text.contains("\"format\": \"recipe-clipper-backup\""))
        assertTrue(text.contains("\"formatVersion\": 1"))
        assertEquals(file, decodeOrFail(text))
    }

    @Test
    fun `a recipe is sent complete, without the sender's ticks, note or last view`() {
        val sent = ShareFile.make(9, listOf(recipe("a"), recipe("a"))).recipes.single()

        assertEquals(listOf("1 egg", "2 cups flour"), sent.ingredients)
        assertEquals("EDITED", sent.contentOrigin)
        assertEquals(3L, sent.editedAt)
        assertTrue(sent.checkedIngredients.isEmpty())
        assertNull(sent.notes)
        assertEquals(9L, sent.lastViewedAt)
    }

    @Test
    fun `grocery items go unticked, name only a recipe the file carries, and keep no planned day`() {
        val file = ShareFile.make(
            9, listOf(recipe("a")), listOf(grocery("g1", "a", checked = true), grocery("g2", "gone"), grocery("g1", "a"))
        )

        assertEquals(listOf("g1", "g2"), file.groceries.map { it.id })
        assertEquals(listOf("a", null), file.groceries.map { it.recipeId })
        assertTrue(file.groceries.none { it.checked || it.plannedDay != null })
        assertTrue(file.lists.isEmpty() && file.memberships.isEmpty() && file.cookedPhotos.isEmpty())
    }

    @Test
    fun `only what was ticked is merged, each new to this phone`() {
        val file = ShareFile.make(9, listOf(recipe("a"), recipe("b")), listOf(grocery("g1", "a"), grocery("g2", "b")))
        val chosen = ShareFile.chosen(file, ShareChoice(recipeIds = setOf("b"), groceryIds = setOf("g1", "g2")), now = 50)

        assertEquals(listOf("b"), chosen.recipes.map { it.id })
        assertEquals(50L, chosen.recipes.single().lastViewedAt)
        // An item's recipe that wasn't ticked isn't brought in for it.
        assertEquals(listOf(null, "b"), chosen.groceries.map { it.recipeId })
        assertEquals(listOf(50L, 50L), chosen.groceries.map { it.updatedAt })
    }

    @Test
    fun `pantry items go to the pantry, or onto the grocery list as their names`() {
        val file = ShareFile.make(9, emptyList(), emptyList(), listOf(pantry("p1", "Basmati rice"), pantry("p2", "Oats")))

        val toPantry = ShareFile.chosen(file, ShareChoice(emptySet(), emptySet(), setOf("p1")), now = 50)
        assertEquals(listOf("Basmati rice"), toPantry.pantry.map { it.name })
        assertTrue(toPantry.groceries.isEmpty())

        val toGroceries = ShareFile.chosen(
            file, ShareChoice(emptySet(), emptySet(), setOf("p1", "p2"), PantryDestination.GROCERIES), now = 50
        )
        assertTrue(toGroceries.pantry.isEmpty())
        assertEquals(listOf("Basmati rice", "Oats"), toGroceries.groceries.map { it.text })
        // Their uids stay, so the same file opened twice adds each once.
        assertEquals(listOf("p1", "p2"), toGroceries.groceries.map { it.id })
        assertTrue(toGroceries.groceries.none { it.checked || it.recipeId != null })
    }

    @Test
    fun `the pantry's file carries only what's in stock, as it is`() {
        val out = pantry("p2", "Oats").copy(inStock = false)
        val file = ShareFile.pantry(9, listOf(pantry("p1", "Basmati rice"), out))!!
        assertTrue(file.isShare)
        assertEquals(listOf(pantry("p1", "Basmati rice")), file.pantry)
        assertTrue(file.recipes.isEmpty() && file.groceries.isEmpty() && file.lists.isEmpty())
        assertEquals(file, decodeOrFail(BackupJson.encode(file)))
        assertNull(ShareFile.pantry(9, listOf(out)))
    }

    @Test
    fun `the file is named for its title, safely`() {
        assertEquals("Sheet-pan chicken.recipeclipper", ShareFile.fileName("Sheet-pan chicken"))
        assertEquals("Mac and cheese 1 2.recipeclipper", ShareFile.fileName("Mac/and\\cheese: 1*2?"))
        assertEquals("hidden.recipeclipper", ShareFile.fileName("..hidden"))
        assertEquals("Recipe Clipper.recipeclipper", ShareFile.fileName(" / "))
        assertEquals(60 + ".recipeclipper".length, ShareFile.fileName("x".repeat(200)).length)
    }
}
