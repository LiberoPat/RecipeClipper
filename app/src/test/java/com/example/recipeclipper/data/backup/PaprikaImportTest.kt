package com.example.recipeclipper.data.backup

import com.example.recipeclipper.data.remote.PaprikaRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a Paprika recipe becomes a library recipe (#382), pure; iOS's `PaprikaImportTests` expects the same. */
class PaprikaImportTest {

    private fun recipe(
        name: String = "Soup",
        uid: String? = null,
        sourceUrl: String? = null,
        ingredients: List<String> = listOf("1 onion"),
        directions: List<String> = listOf("Simmer."),
        prep: String? = null,
        cook: String? = null,
        total: String? = null,
        notes: String? = null,
        servings: String? = null,
        imageUrl: String? = null
    ) = PaprikaRecipe(
        uid = uid, name = name, ingredients = ingredients, directions = directions, notes = notes, servings = servings,
        prepTime = prep, cookTime = cook, totalTime = total, sourceUrl = sourceUrl, imageUrl = imageUrl,
        categories = listOf("Dinner"), hasPhoto = true
    )

    private var uids = 0
    private fun map(vararg recipes: PaprikaRecipe, now: Long = 1_000_000L) =
        PaprikaImport.toBackup(recipes.toList(), now) { "uid-${uids++}" }

    @Test fun `a recipe with a link is keyed by the link and kept as the cook's version`() {
        val mapped = map(recipe(sourceUrl = "https://www.example.com/soup?id=1", uid = "ABC"))
        val r = mapped.backup.recipes.single()
        assertEquals("https://www.example.com/soup?id=1", r.sourceUrl)
        assertEquals("EDITED", r.contentOrigin)
        assertEquals(1_000_000L, r.editedAt)
    }

    @Test fun `one with no link is typed in, keyed by Paprika's id, lower case`() {
        val r = map(recipe(uid = "0A9B8C7D-6E5F-4A3B")).backup.recipes.single()
        assertEquals("manual:paprika-0a9b8c7d-6e5f-4a3b", r.sourceUrl)
        assertEquals("MANUAL", r.contentOrigin)
    }

    @Test fun `with no id, or an id that is not plain, it is keyed by a hash of its text`() {
        val tea = recipe(name = "Mint Tea", ingredients = emptyList(), directions = listOf("Steep mint leaves in hot water for 5 minutes."))
        // FNV-1a over "name\ningredients...\ndirections...": pinned so both platforms agree.
        assertEquals("manual:paprika-h258018455a849b2a", PaprikaImport.identity(tea))
        assertEquals("manual:paprika-h258018455a849b2a", PaprikaImport.identity(tea.copy(uid = "http://evil/x")))
        assertEquals("manual:paprika-h258018455a849b2a", PaprikaImport.identity(tea.copy(uid = "a".repeat(65))))
        assertTrue(PaprikaImport.identity(tea.copy(uid = "Abc-123")).endsWith("abc-123"))
    }

    @Test fun `the same file twice makes the same keys`() {
        val file = listOf(recipe(uid = "A"), recipe(name = "Tea", directions = listOf("Steep.")), recipe(sourceUrl = "https://x.example/r"))
        assertEquals(
            map(*file.toTypedArray()).backup.recipes.map { it.sourceUrl },
            map(*file.toTypedArray()).backup.recipes.map { it.sourceUrl }
        )
    }

    @Test fun `a repeat in the file is counted, not written, and the first wins`() {
        val mapped = map(
            recipe(name = "First", sourceUrl = "https://x.example/r?utm_source=a"),
            recipe(name = "Second", sourceUrl = "https://x.example/r"),
            recipe(name = "Third", uid = "U1"),
            recipe(name = "Fourth", uid = "u1")
        )
        assertEquals(listOf("First", "Third"), mapped.backup.recipes.map { it.title })
        assertEquals(2, mapped.repeated)
    }

    @Test fun `a time that is a whole phrase renders like ISO, anything else stays as written`() {
        val r = map(recipe(prep = "20 minutes", cook = "1 hour 30 minutes", total = "Overnight")).backup.recipes.single()
        assertEquals("20m", r.prepTime)
        assertEquals("1h 30m", r.cookTime)
        assertEquals("Overnight", r.totalTime)
        val iso = map(recipe(prep = "PT10M", cook = "0 minutes", total = "1-2 hours")).backup.recipes.single()
        assertEquals("10m", iso.prepTime)
        assertNull(iso.cookTime)
        assertEquals("1-2 hours", iso.totalTime)
    }

    @Test fun `text, servings, notes and the web picture are carried as they are`() {
        val r = map(
            recipe(
                ingredients = listOf("For the sauce:", "2 tbsp butter"), directions = listOf("Melt.", "Stir."),
                servings = "12 brownies", notes = "Less sugar", imageUrl = "https://img.example/a.jpg"
            )
        ).backup.recipes.single()
        assertEquals(listOf("For the sauce:", "2 tbsp butter"), r.ingredients)
        assertEquals(listOf("Melt.", "Stir."), r.instructions)
        assertEquals("12 brownies", r.servings)
        assertEquals("Less sugar", r.notes)
        assertEquals("https://img.example/a.jpg", r.imageUrl)
        assertEquals("BLOG", r.sourceType)
    }

    @Test fun `the language is the recipe's own words, never the phone's`() {
        val spanish = recipe(
            name = "Sopa de lentejas", ingredients = listOf("1 taza de lentejas", "2 cucharadas de aceite", "sal y pimienta", "agua"),
            directions = listOf("Cocer.")
        )
        assertEquals("es", map(spanish).backup.recipes.single().language)
        assertEquals("en", map(recipe(ingredients = listOf("1 cup rice"))).backup.recipes.single().language)
    }

    @Test fun `categories are not filed into lists`() {
        val backup = map(recipe(), recipe(name = "B", uid = "b")).backup
        assertTrue(backup.lists.isEmpty())
        assertTrue(backup.memberships.isEmpty())
    }

    @Test fun `the file's order is kept, the first being the most recently viewed`() {
        val recipes = map(recipe(name = "A", uid = "a"), recipe(name = "B", uid = "b"), recipe(name = "C", uid = "c"), now = 5_000L)
            .backup.recipes
        assertEquals(listOf(5_000L, 4_999L, 4_998L), recipes.map { it.lastViewedAt })
    }

    @Test fun `each recipe gets its own fresh uid`() {
        val ids = map(recipe(uid = "a"), recipe(name = "B", uid = "b")).backup.recipes.map { it.id }
        assertEquals(2, ids.toSet().size)
    }
}
