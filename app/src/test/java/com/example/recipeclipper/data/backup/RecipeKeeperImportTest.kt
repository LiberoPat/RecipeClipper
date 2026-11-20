package com.example.recipeclipper.data.backup

import com.example.recipeclipper.data.remote.ImportSource
import com.example.recipeclipper.data.remote.PaprikaRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a Recipe Keeper recipe becomes a library recipe (#390): the Paprika mapping with the source
 * that names a recipe's own key `manual:recipekeeper-…`. Pure; iOS's `RecipeKeeperImportTests`
 * expects the same.
 */
class RecipeKeeperImportTest {

    private fun recipe(
        name: String = "Soup",
        uid: String? = null,
        sourceUrl: String? = null,
        ingredients: List<String> = listOf("For the broth:", "1 onion"),
        directions: List<String> = listOf("Simmer."),
        prep: String? = null,
        cook: String? = null,
        servings: String? = null,
        categories: List<String> = emptyList(),
        favorite: Boolean = false,
        photoFile: String? = null
    ) = PaprikaRecipe(
        uid = uid, name = name, ingredients = ingredients, directions = directions, notes = null, servings = servings,
        prepTime = prep, cookTime = cook, totalTime = null, sourceUrl = sourceUrl, imageUrl = null,
        categories = categories, hasPhoto = photoFile != null, photoFile = photoFile, favorite = favorite
    )

    private var uids = 0
    private fun map(vararg recipes: PaprikaRecipe, filing: Boolean = false) =
        PaprikaImport.toBackup(recipes.toList(), 1_000_000L, filing, ImportSource.RECIPE_KEEPER) { "uid-${uids++}" }

    @Test fun `a recipe with no link is keyed by Recipe Keeper's id, lower case, and is typed in`() {
        val r = map(recipe(uid = "A2489B23-050F-5C8A")).backup.recipes.single()
        assertEquals("manual:recipekeeper-a2489b23-050f-5c8a", r.sourceUrl)
        assertEquals("MANUAL", r.contentOrigin)
    }

    @Test fun `one with a link is keyed by the link and kept as the cook's version, never refetched`() {
        val r = map(recipe(uid = "X", sourceUrl = "https://www.example.com/soup?id=1")).backup.recipes.single()
        assertEquals("https://www.example.com/soup?id=1", r.sourceUrl)
        assertEquals("EDITED", r.contentOrigin)
    }

    @Test fun `with no usable id it is keyed by a hash of its text, under its own prefix`() {
        val tea = recipe(name = "Mint Tea", ingredients = emptyList(), directions = listOf("Steep mint leaves in hot water for 5 minutes."))
        assertEquals("manual:recipekeeper-h258018455a849b2a", PaprikaImport.identity(tea, ImportSource.RECIPE_KEEPER))
        assertEquals("manual:recipekeeper-h258018455a849b2a", PaprikaImport.identity(tea.copy(uid = "../x"), ImportSource.RECIPE_KEEPER))
        // The same recipe from the other app is a different key: the two imports never meet.
        assertEquals("manual:paprika-h258018455a849b2a", PaprikaImport.identity(tea))
    }

    @Test fun `an ingredient heading stays a line ending in a colon, and the lines stay as written`() {
        val r = map(recipe(ingredients = listOf("For the broth:", "1 onion", "2 l water"))).backup.recipes.single()
        assertEquals(listOf("For the broth:", "1 onion", "2 l water"), r.ingredients)
    }

    @Test fun `ISO times go through the time rules, a zero is none, servings stay as written`() {
        val r = map(recipe(prep = "PT10M", cook = "PT0S", servings = "Serves 6-8")).backup.recipes.single()
        assertEquals("10m", r.prepTime)
        assertNull(r.cookTime)
        assertNull(r.totalTime)
        assertEquals("Serves 6-8", r.servings)
        val long = map(recipe(cook = "PT1H30M")).backup.recipes.single()
        assertEquals("1h 30m", long.cookTime)
    }

    @Test fun `the stored picture becomes the recipe's own`() {
        assertEquals("stored-1.jpg", map(recipe(photoFile = "stored-1.jpg")).backup.recipes.single().photo)
    }

    @Test fun `a repeat of a recipe id in the file is counted and adds its categories to the first's`() {
        val mapped = map(
            recipe(name = "First", uid = "U1", categories = listOf("Dessert")),
            recipe(name = "Second", uid = "u1", categories = listOf("dessert", "Party")),
            filing = true
        )
        assertEquals(listOf("First"), mapped.backup.recipes.map { it.title })
        assertEquals(1, mapped.repeated)
        assertEquals(listOf("Dessert", "Party"), mapped.backup.lists.map { it.name })
        assertEquals(2, mapped.backup.memberships.size)
    }

    @Test fun `with the switch on, the favourite flag files the recipe in Favorites by flag, never by name`() {
        val mapped = map(recipe(categories = listOf("Favorites"), favorite = true), filing = true)
        val favorites = mapped.backup.lists.single { it.isFavorites }
        val plain = mapped.backup.lists.single { !it.isFavorites }
        assertEquals("Favorites", plain.name)
        assertTrue(mapped.backup.memberships.any { it.listId == favorites.id })
        assertTrue(mapped.backup.memberships.any { it.listId == plain.id })
    }

    @Test fun `with the switch off no list is made`() {
        val mapped = map(recipe(categories = listOf("Dessert"), favorite = true))
        assertTrue(mapped.backup.lists.isEmpty())
        assertTrue(mapped.backup.memberships.isEmpty())
    }
}
