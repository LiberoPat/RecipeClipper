package com.example.recipeclipper.data.backup

import com.example.recipeclipper.data.remote.MealieRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a Mealie recipe becomes a library recipe (#391), pure; iOS's `MealieImportTests` expects the same. */
class MealieImportTest {

    private fun recipe(
        name: String = "Soup",
        slug: String = "soup",
        id: String? = null,
        sourceUrl: String? = null,
        ingredients: List<String> = listOf("1 onion"),
        instructions: List<String> = listOf("Simmer."),
        prep: String? = null,
        cook: String? = null,
        total: String? = null,
        notes: String? = null,
        servings: String? = null,
        categories: List<String> = listOf("Dinner"),
        photoFile: String? = null
    ) = MealieRecipe(
        slug = slug, id = id, name = name, ingredients = ingredients, instructions = instructions, notes = notes,
        servings = servings, prepTime = prep, cookTime = cook, totalTime = total, sourceUrl = sourceUrl,
        categories = categories, photoFile = photoFile
    )

    private var uids = 0
    private fun map(vararg recipes: MealieRecipe, now: Long = 1_000_000L, filing: Boolean = false) =
        MealieImport.toBackup(recipes.toList(), now, filing) { "uid-${uids++}" }

    @Test fun `a recipe with a link is keyed by the link and kept as the cook's version`() {
        val r = map(recipe(sourceUrl = "https://www.example.com/soup?id=1", slug = "abc")).backup.recipes.single()
        assertEquals("https://www.example.com/soup?id=1", r.sourceUrl)
        assertEquals("EDITED", r.contentOrigin)
        assertEquals(1_000_000L, r.editedAt)
        assertNull(r.imageUrl)
    }

    @Test fun `one with no link is typed in, keyed by its slug, lower case`() {
        val r = map(recipe(slug = "Pasta-e-Ceci")).backup.recipes.single()
        assertEquals("manual:mealie-pasta-e-ceci", r.sourceUrl)
        assertEquals("MANUAL", r.contentOrigin)
    }

    @Test fun `a slug that is not plain falls back to the id, then to a hash of the text`() {
        val tea = recipe(name = "Mint Tea", slug = "../évil/x", ingredients = emptyList(), instructions = listOf("Steep mint leaves in hot water for 5 minutes."))
        // FNV-1a over "name\ningredients...\nsteps...": pinned so both platforms agree (the Paprika import's hash of the same text).
        assertEquals("manual:mealie-h258018455a849b2a", MealieImport.identity(tea))
        assertEquals("manual:mealie-h258018455a849b2a", MealieImport.identity(tea.copy(id = "http://evil/x")))
        assertEquals("manual:mealie-8a6b-01", MealieImport.identity(tea.copy(id = "8A6B-01")))
        assertEquals("manual:mealie-h258018455a849b2a", MealieImport.identity(tea.copy(slug = "a".repeat(101))))
    }

    @Test fun `a repeat in the file is counted, not written, and the first wins`() {
        val mapped = map(
            recipe(name = "First", sourceUrl = "https://x.example/r?utm_source=a"),
            recipe(name = "Second", slug = "second", sourceUrl = "https://x.example/r"),
            recipe(name = "Third", slug = "third"),
            recipe(name = "Fourth", slug = "THIRD")
        )
        assertEquals(listOf("First", "Third"), mapped.backup.recipes.map { it.title })
        assertEquals(2, mapped.repeated)
    }

    @Test fun `times go through the time rules`() {
        val r = map(recipe(prep = "20 minutes", cook = "1 hour 30 minutes", total = "Overnight")).backup.recipes.single()
        assertEquals("20m", r.prepTime)
        assertEquals("1h 30m", r.cookTime)
        assertEquals("Overnight", r.totalTime)
        val iso = map(recipe(prep = "PT10M", cook = "0 minutes", total = "PT5400S")).backup.recipes.single()
        assertEquals("10m", iso.prepTime)
        assertNull(iso.cookTime)
        assertEquals("1h 30m", iso.totalTime)
    }

    @Test fun `text, servings and notes are carried as they are`() {
        val r = map(
            recipe(ingredients = listOf("For the sauce:", "2 tbsp butter"), instructions = listOf("Melt.", "Stir."), servings = "12 brownies", notes = "Less sugar")
        ).backup.recipes.single()
        assertEquals(listOf("For the sauce:", "2 tbsp butter"), r.ingredients)
        assertEquals(listOf("Melt.", "Stir."), r.instructions)
        assertEquals("12 brownies", r.servings)
        assertEquals("Less sugar", r.notes)
        assertEquals("BLOG", r.sourceType)
    }

    @Test fun `the language is the recipe's own words, never the phone's`() {
        val spanish = recipe(
            name = "Sopa de lentejas", slug = "s", ingredients = listOf("1 taza de lentejas", "2 cucharadas de aceite", "sal y pimienta", "agua"),
            instructions = listOf("Cocer.")
        )
        assertEquals("es", map(spanish).backup.recipes.single().language)
        assertEquals("en", map(recipe(ingredients = listOf("1 cup rice"))).backup.recipes.single().language)
    }

    @Test fun `with the switch off categories and tags are not filed into lists`() {
        val backup = map(recipe(), recipe(name = "B", slug = "b")).backup
        assertTrue(backup.lists.isEmpty())
        assertTrue(backup.memberships.isEmpty())
    }

    @Test fun `with it on, a category or tag is a list, case-insensitive, the first spelling kept, and nothing is a favourite`() {
        val backup = map(
            recipe(name = "A", slug = "a", categories = listOf("Dessert", " baking ")),
            recipe(name = "B", slug = "b", categories = listOf("BAKING", "", "Soup")),
            filing = true
        ).backup
        assertEquals(listOf("Dessert", "baking", "Soup"), backup.lists.map { it.name })
        assertTrue(backup.lists.none { it.isFavorites })
        val ids = backup.recipes.associate { it.title to it.id }
        val named = backup.lists.associate { it.id to it.name }
        fun inLists(title: String) = backup.memberships.filter { it.recipeId == ids.getValue(title) }.map { named.getValue(it.listId) }
        assertEquals(listOf("Dessert", "baking"), inLists("A"))
        assertEquals(listOf("baking", "Soup"), inLists("B"))
    }

    @Test fun `a repeat in the file adds its categories to the first`() {
        val backup = map(
            recipe(name = "First", slug = "x", categories = listOf("Dinner")),
            recipe(name = "Second", slug = "X", categories = listOf("Quick")),
            filing = true
        ).backup
        assertEquals(1, backup.recipes.size)
        assertEquals(setOf("Dinner", "Quick"), backup.lists.map { it.name }.toSet())
        assertEquals(2, backup.memberships.size)
    }

    @Test fun `the picture the archive stored is the recipe's own, under that name`() {
        assertEquals("stored-1.jpg", map(recipe(photoFile = "stored-1.jpg")).backup.recipes.single().photo)
        assertNull(map(recipe()).backup.recipes.single().photo)
    }

    @Test fun `the file's order is kept, the first being the most recently viewed`() {
        val recipes = map(recipe(name = "A", slug = "a"), recipe(name = "B", slug = "b"), recipe(name = "C", slug = "c"), now = 5_000L).backup.recipes
        assertEquals(listOf(5_000L, 4_999L, 4_998L), recipes.map { it.lastViewedAt })
    }

    @Test fun `each recipe gets its own fresh uid`() {
        val ids = map(recipe(slug = "a"), recipe(name = "B", slug = "b")).backup.recipes.map { it.id }
        assertEquals(2, ids.toSet().size)
    }
}
