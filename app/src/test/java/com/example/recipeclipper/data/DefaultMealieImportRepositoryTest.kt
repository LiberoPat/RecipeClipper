package com.example.recipeclipper.data

import com.example.recipeclipper.data.backup.MealieSummary
import com.example.recipeclipper.data.local.entity.ListEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.remote.MealieError
import com.example.recipeclipper.data.remote.MealieLimits
import com.example.recipeclipper.fake.FakeLibraryPolicy
import com.example.recipeclipper.fake.FakeMealieFiles
import com.example.recipeclipper.fake.FakePhotoStore
import com.example.recipeclipper.fake.InMemoryBackupDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Import from Mealie (#391) over an in-memory DAO and the real archive reader on the shared
 * fixtures, with a fake photo store (#386) and the seeded lists the categories meet (#387). The SQL of
 * the write is `BackupDaoTest`'s (and every other import's); this is the plan. iOS:
 * `MealieImportRepositoryTests`.
 */
class DefaultMealieImportRepositoryTest {

    @get:Rule val folder = TemporaryFolder()

    private class RecordingLog : ErrorLog {
        val messages = mutableListOf<String>()
        override fun error(message: String, cause: Throwable) {
            messages += message
        }
    }

    private val clock = Clock { 1_790_000_000_000L }
    private val log = RecordingLog()
    private val store = FakePhotoStore()
    private val dao = InMemoryBackupDao().apply {
        // The lists a fresh phone has: Favorites, and Dinner among the others.
        lists += ListEntity(id = 1, uid = "l-fav", name = "Favorites", isBuiltIn = true, isFavorites = true, sortOrder = 0, createdAt = 0)
        lists += ListEntity(id = 2, uid = "l-dinner", name = "Dinner", isBuiltIn = true, isFavorites = false, sortOrder = 2, createdAt = 0)
    }
    private val files = FakeMealieFiles(photos = store)

    private fun fixture(name: String): String {
        val file = File(folder.root, name)
        javaClass.getResourceAsStream("/mealie/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        files.files["content://$name"] = file
        return "content://$name"
    }

    private fun repository(limit: LibraryLimit = LibraryLimit.Unlimited, dao: InMemoryBackupDao = this.dao) =
        DefaultMealieImportRepository(files, dao, clock, log, FakeLibraryPolicy(limit), store)

    private fun summary(result: MealieResult) = (result as MealieResult.Success).summary

    private fun existing(id: Long, url: String, title: String = "Mine") = RecipeEntity(
        id = id, uid = "e$id", sourceUrl = url, title = title, imageUrl = null, ingredients = listOf("1 egg"),
        instructions = listOf("Cook."), prepTime = null, cookTime = null, totalTime = null, servings = null,
        sourceType = "BLOG", lastViewedAt = 1
    )

    /** The names of the lists [recipe] is in, sorted. */
    private fun listsOf(recipe: String): List<String> {
        val id = dao.recipes.first { it.title == recipe }.id
        return dao.refs.filter { it.recipeId == id }.map { ref -> dao.lists.first { it.id == ref.listId }.name }.sorted()
    }

    @Test fun `a bulk export's recipes join the library as the cook's own`() = runTest {
        val result = summary(repository().import(fixture("mealie-bulk.zip")))

        // Pasta, the old soup and tea; the second pasta has the first's link; three files are no recipe; one is unreadable.
        assertEquals(MealieSummary(3, 1, 0, 3, 1, 0, null), result)
        assertEquals(listOf("Pasta e Ceci", "Old Lentil Soup", "Mint Tea"), dao.recipes.sortedBy { it.id }.map { it.title })
        val pasta = dao.recipes.first { it.title == "Pasta e Ceci" }
        assertEquals("https://www.example.com/pasta-e-ceci?id=9", pasta.sourceUrl)
        assertEquals("EDITED", pasta.contentOrigin)
        assertEquals(listOf("The base:", "2 cups canned chickpeas, drained", "1 tbsp olive oil", "The pasta:", "200 g ditalini", "salt"), pasta.ingredients)
        assertEquals(
            listOf(
                "Start the soup: Heat the oil and soften the garlic.",
                "Add the chickpeas and 4 cups of water, then simmer for 20 minutes.",
                "Finish: Stir in the pasta and cook until tender."
            ),
            pasta.instructions
        )
        assertEquals("4 servings", pasta.servings)
        assertEquals("10m", pasta.prepTime)
        assertEquals("35m", pasta.cookTime)
        assertEquals("45m", pasta.totalTime)
        assertEquals("Storage\nKeeps 3 days in the fridge.\n\nThickens as it stands.", pasta.notes)
        assertNull(pasta.imageUrl)
        val soup = dao.recipes.first { it.title == "Old Lentil Soup" }
        assertEquals("manual:mealie-old-lentil-soup", soup.sourceUrl)
        assertEquals("MANUAL", soup.contentOrigin)
        // Before Mealie 3.1: the unparsed lines were stored with quantity 1, and never show it.
        assertEquals(listOf("2 cups red lentils", "1 onion, chopped", "2 cups water"), soup.ingredients)
        assertEquals("2.5", soup.servings)
        assertEquals("10m", soup.prepTime)
        assertEquals("1h 15m", soup.totalTime)
        // Without the switch nothing is filed and no list is made.
        assertTrue(dao.refs.isEmpty())
        assertEquals(2, dao.lists.size)
    }

    @Test fun `a recipe's download brings its picture in as the recipe's own, never a cooking`() = runTest {
        val result = summary(repository().import(fixture("mealie-recipe-download.zip")))
        assertEquals(MealieSummary(1, 0, 0, 0, 0, 0, null), result)
        val recipe = dao.recipes.single()
        assertNotNull(recipe.photoFile)
        assertTrue(store.exists(recipe.photoFile!!))
        assertTrue(dao.cookedPhotos.isEmpty())
        assertEquals(1, store.files().size)
    }

    @Test fun `a bare json is one recipe`() = runTest {
        val result = summary(repository().import(fixture("mealie-recipe.json")))
        assertEquals(1, result.imported)
        val tea = dao.recipes.single()
        assertEquals("Mint Tea", tea.title)
        assertEquals("manual:mealie-mint-tea", tea.sourceUrl)
        assertEquals(listOf("a handful of fresh mint"), tea.ingredients)
        assertEquals("1", tea.servings)
        assertEquals("5m", tea.cookTime)
        assertEquals("10m", tea.totalTime)
    }

    @Test fun `a picture that isn't one, or is too big, is left out and counted and the recipe stays`() = runTest {
        val result = summary(repository().import(fixture("mealie-photos.zip")))

        assertEquals(MealieSummary(4, 0, 0, 0, 0, 2, null), result)
        val photos = dao.recipes.associate { it.title to it.photoFile }
        assertNotNull(photos["Toast"])
        assertNull(photos["Jam"])
        assertNull(photos["Honey"])
        assertNull(photos["Salt"])
        assertEquals(1, store.files().size)
    }

    @Test fun `pictures no recipe ends up with are deleted, none is left behind`() = runTest {
        // The pasta is already here: the file's picture for it is not kept, nor is it left in the store, when it has its own.
        dao.recipes += existing(1, "https://www.example.com/pasta-e-ceci?id=9").copy(photoFile = store.importBytes("mine".toByteArray()))
        repository().import(fixture("mealie-with-pictures.zip"))

        val named = dao.recipes.mapNotNull { it.photoFile }.toSet()
        assertEquals(named, store.files().keys)
        assertEquals("mine", store.bytes(dao.recipes.first { it.id == 1L }.photoFile!!))
    }

    @Test fun `a recipe already here with no picture gains the file's, and nothing else of it changes`() = runTest {
        dao.recipes += existing(1, "https://www.example.com/pasta-e-ceci?id=9", title = "My own pasta")
        repository().import(fixture("mealie-recipe-download.zip"))

        val here = dao.recipes.first { it.id == 1L }
        assertEquals("My own pasta", here.title)
        assertEquals(listOf("1 egg"), here.ingredients)
        assertNotNull(here.photoFile)
        assertEquals(1, dao.recipes.size)
    }

    @Test fun `a free library that is full takes none, and its pictures are not kept`() = runTest {
        repeat(50) { dao.recipes += existing(it + 1L, "https://old.example/$it") }
        repository(LibraryLimit.Capped(50)).import(fixture("mealie-recipe-download.zip"))
        assertEquals(50, dao.recipes.size)
        assertTrue(store.files().isEmpty())
    }

    @Test fun `a database failure keeps no picture`() = runTest {
        val failing = object : InMemoryBackupDao() {
            override suspend fun insertRecipe(recipe: RecipeEntity): Long = throw IllegalStateException("disk full")
        }
        val result = repository(dao = failing).import(fixture("mealie-recipe-download.zip"))
        assertEquals(MealieResult.Failure(MealieError.SAVE_FAILED), result)
        assertEquals(listOf("mealieImport failed"), log.messages)
        assertTrue(store.files().isEmpty())
    }

    @Test fun `importing the same file twice adds nothing the second time, pictures included`() = runTest {
        val uri = fixture("mealie-with-pictures.zip")
        repository().import(uri)
        val again = summary(repository().import(uri))

        assertEquals(MealieSummary(0, 2, 0, 0, 0, 0, null), again)
        assertEquals(2, dao.recipes.size)
        assertEquals(1, store.files().size)
    }

    @Test fun `a recipe already here keeps its content, and only fills an empty note`() = runTest {
        dao.recipes += existing(1, "https://www.example.com/pasta-e-ceci?id=9", title = "My own pasta")
        val result = summary(repository().import(fixture("mealie-bulk.zip")))

        assertEquals(2, result.imported)
        // The one here, and the second pasta in the file that has the same link.
        assertEquals(2, result.alreadyHere)
        val here = dao.recipes.first { it.id == 1L }
        assertEquals("My own pasta", here.title)
        assertEquals(listOf("1 egg"), here.ingredients)
        assertEquals("PARSED", here.contentOrigin)
        assertEquals("Storage\nKeeps 3 days in the fridge.\n\nThickens as it stands.", here.notes)
        assertEquals(3, dao.recipes.size)
    }

    // ---- #387: categories and tags become lists

    @Test fun `with the switch on, categories and tags put recipes in lists by name, and new ones are made`() = runTest {
        val result = summary(repository().import(fixture("mealie-bulk.zip"), filing = true))

        // Soup, Vegetarian, Weeknight (from the repeat), Drinks and Quick are new; "dinner" is the Dinner list here.
        assertEquals(5, result.listsMade)
        assertEquals(listOf("Dinner", "Soup", "Vegetarian", "Weeknight"), listsOf("Pasta e Ceci"))
        assertEquals(emptyList<String>(), listsOf("Old Lentil Soup"))
        assertEquals(listOf("Drinks", "Quick"), listsOf("Mint Tea"))
        assertEquals(7, dao.lists.size)
        // Mealie keeps favourites per user, outside the recipe: nothing goes to Favorites.
        assertTrue(dao.refs.none { it.listId == 1L })
    }

    @Test fun `with the switch off, nothing is filed`() = runTest {
        val result = summary(repository().import(fixture("mealie-bulk.zip"), filing = false))
        assertEquals(0, result.listsMade)
        assertTrue(dao.refs.isEmpty())
        assertEquals(2, dao.lists.size)
    }

    @Test fun `recipes the free limit leaves out are filed nowhere, and make no empty list`() = runTest {
        repeat(49) { dao.recipes += existing(it + 1L, "https://old.example/$it") }
        val result = summary(repository(LibraryLimit.Capped(50)).import(fixture("mealie-bulk.zip"), filing = true))

        // One recipe fits (the file's first); the other two are left out with their lists.
        assertEquals(1, result.imported)
        assertEquals(2, result.skippedForLimit)
        val came = dao.recipes.first { it.id > 49 }
        assertEquals("Pasta e Ceci", came.title)
        assertEquals(setOf(came.id), dao.refs.map { it.recipeId }.toSet())
        assertEquals(result.listsMade, dao.lists.size - 2)
        assertTrue(dao.lists.none { it.name == "Drinks" || it.name == "Quick" })
    }

    // ---- the rest of #391

    @Test fun `at the free limit what fits comes in, the rest is counted and nothing old is removed`() = runTest {
        repeat(49) { dao.recipes += existing(it + 1L, "https://old.example/$it") }
        val result = summary(repository(LibraryLimit.Capped(50)).import(fixture("mealie-bulk.zip")))

        assertEquals(1, result.imported)
        assertEquals(2, result.skippedForLimit)
        assertEquals(50, result.freeLimit)
        assertEquals(50, dao.recipes.size)
        assertTrue((1L..49L).all { id -> dao.recipes.any { it.id == id } })
    }

    @Test fun `files that couldn't be read are counted and the rest import`() = runTest {
        val result = summary(repository().import(fixture("mealie-bad-entries.zip")))
        assertEquals(1, result.imported)
        assertEquals(3, result.unreadable)
    }

    @Test fun `a file with nothing readable writes nothing`() = runTest {
        val uri = fixture("mealie-bad-entries.zip")
        val result = summary(
            DefaultMealieImportRepository(
                FakeMealieFiles(MealieLimits(maxEntryBytes = 10)).also { it.files.putAll(files.files) },
                dao, clock, log, FakeLibraryPolicy(LibraryLimit.Unlimited), store
            ).import(uri)
        )
        assertEquals(MealieSummary(0, 0, 0, 0, 4, 0, null), result)
        assertEquals(0, dao.writes)
    }

    @Test fun `a file that isn't Mealie's, a damaged one and an unreadable one say why and write nothing`() = runTest {
        assertEquals(MealieResult.Failure(MealieError.DAMAGED), repository().import(fixture("mealie-truncated.zip")))
        assertEquals(MealieResult.Failure(MealieError.NOT_A_MEALIE_FILE), repository().import(fixture("mealie-not-mealie.zip")))
        // A recipe page's schema.org data is not a Mealie file: it goes in through the link.
        assertEquals(MealieResult.Failure(MealieError.NOT_A_MEALIE_FILE), repository().import(fixture("mealie-schema-org.json")))
        assertEquals(MealieResult.Failure(MealieError.NOT_A_MEALIE_FILE), repository().import(fixture("mealie-only-schema.zip")))
        val text = File(folder.root, "a.txt").apply { writeText("not a zip at all") }
        files.files["content://a.txt"] = text
        assertEquals(MealieResult.Failure(MealieError.NOT_A_MEALIE_FILE), repository().import("content://a.txt"))
        assertEquals(MealieResult.Failure(MealieError.READ_FAILED), repository().import("content://gone"))
        assertEquals(0, dao.writes)
    }

    @Test fun `a file that unpacks to far more than it says is dropped entry by entry`() = runTest {
        val bomb = summary(repository().import(fixture("mealie-bomb.zip")))
        assertEquals(1, bomb.imported)
        assertEquals(1, bomb.unreadable)
        val huge = summary(repository().import(fixture("mealie-huge-declared.zip")))
        assertEquals(0, huge.imported)
        assertEquals(1, huge.alreadyHere)
        assertEquals(1, huge.unreadable)
    }
}
