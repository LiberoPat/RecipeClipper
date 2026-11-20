package com.example.recipeclipper.data

import com.example.recipeclipper.data.backup.PaprikaSummary
import com.example.recipeclipper.data.local.entity.ListEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.remote.PaprikaError
import com.example.recipeclipper.data.remote.PaprikaLimits
import com.example.recipeclipper.fake.FakeLibraryPolicy
import com.example.recipeclipper.fake.FakePaprikaFiles
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
 * Import from Paprika (#382) over an in-memory DAO and the real archive reader on the shared
 * fixtures, with a fake photo store (#386) and the seeded lists the categories meet (#387). The
 * SQL of the write is `BackupDaoTest`'s (and every other import's); this is the plan.
 */
class DefaultPaprikaImportRepositoryTest {

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
    private val files = FakePaprikaFiles(photos = store)

    private fun fixture(name: String): String {
        val file = File(folder.root, name)
        javaClass.getResourceAsStream("/paprika/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        files.files["content://$name"] = file
        return "content://$name"
    }

    private fun repository(limit: LibraryLimit = LibraryLimit.Unlimited, dao: InMemoryBackupDao = this.dao) =
        DefaultPaprikaImportRepository(files, dao, clock, log, FakeLibraryPolicy(limit), store)

    private fun summary(result: PaprikaResult) = (result as PaprikaResult.Success).summary

    private fun existing(id: Long, url: String, title: String = "Mine", photo: String? = null) = RecipeEntity(
        id = id, uid = "e$id", sourceUrl = url, title = title, imageUrl = null, ingredients = listOf("1 egg"),
        instructions = listOf("Cook."), prepTime = null, cookTime = null, totalTime = null, servings = null,
        sourceType = "BLOG", lastViewedAt = 1, photoFile = photo
    )

    /** The names of the lists [recipe] is in, sorted. */
    private fun listsOf(recipe: String): List<String> {
        val id = dao.recipes.first { it.title == recipe }.id
        return dao.refs.filter { it.recipeId == id }.map { ref -> dao.lists.first { it.id == ref.listId }.name }.sorted()
    }

    @Test fun `a file's recipes join the library as the cook's own`() = runTest {
        val result = summary(repository().import(fixture("paprika-valid.paprikarecipes")))

        // Brownies, soup and tea; the second brownies has the first's link; two entries are no recipe.
        assertEquals(PaprikaSummary(3, 1, 0, 2, 0, 0, null), result)
        assertEquals(listOf("Fudgy Brownies", "Gran's Lentil Soup", "Mint Tea"), dao.recipes.sortedBy { it.id }.map { it.title })
        val brownies = dao.recipes.first { it.title == "Fudgy Brownies" }
        assertEquals("https://www.example.com/brownies?id=7", brownies.sourceUrl)
        assertEquals("EDITED", brownies.contentOrigin)
        assertEquals("https://img.example.com/brownies.jpg", brownies.imageUrl)
        assertEquals("1h 30m", brownies.cookTime)
        assertEquals("Dad's favourite. Use less sugar next time.", brownies.notes)
        val soup = dao.recipes.first { it.title == "Gran's Lentil Soup" }
        assertEquals("manual:paprika-0a9b8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d", soup.sourceUrl)
        assertEquals("MANUAL", soup.contentOrigin)
        assertEquals("10m", soup.prepTime)
        // Without the switch nothing is filed and no list is made.
        assertTrue(dao.refs.isEmpty())
        assertEquals(2, dao.lists.size)
    }

    @Test fun `each picture in the file becomes the recipe's own, under a name of the store's`() = runTest {
        val result = summary(repository().import(fixture("paprika-valid.paprikarecipes")))
        assertEquals(0, result.photosLeftOut)
        val brownies = dao.recipes.first { it.title == "Fudgy Brownies" }
        val soup = dao.recipes.first { it.title == "Gran's Lentil Soup" }
        val tea = dao.recipes.first { it.title == "Mint Tea" }
        assertNotNull(brownies.photoFile)
        assertNotNull(soup.photoFile)
        assertNull(tea.photoFile)
        assertTrue("never named after the file's entry", listOf(brownies, soup).none { "Brownies" in it.photoFile!! || "Soup" in it.photoFile!! })
        assertTrue(store.exists(brownies.photoFile!!) && store.exists(soup.photoFile!!))
        // The picture is a recipe's own, never a cooking.
        assertTrue(dao.cookedPhotos.isEmpty())
    }

    @Test fun `a picture that isn't one, or is too big, is left out and counted and the recipe stays`() = runTest {
        val result = summary(repository().import(fixture("paprika-photos.paprikarecipes")))

        assertEquals(PaprikaSummary(5, 0, 0, 0, 0, 3, null), result)
        val photos = dao.recipes.associate { it.title to it.photoFile }
        assertNotNull(photos["Toast"])
        assertNull(photos["Jam"])
        assertNull(photos["Butter"])
        assertNull(photos["Honey"])
        assertNull(photos["Salt"])
        assertEquals(1, store.files().size)
    }

    @Test fun `pictures no recipe ends up with are deleted, none is left behind`() = runTest {
        // Brownies is already here, with a picture of its own: the file's picture for it is not kept, nor is it stored.
        dao.recipes += existing(1, "https://www.example.com/brownies?id=7", photo = store.importBytes("mine".toByteArray()))
        repository().import(fixture("paprika-valid.paprikarecipes"))

        val named = dao.recipes.mapNotNull { it.photoFile }.toSet()
        assertEquals(named, store.files().keys)
        assertEquals("mine", store.bytes(dao.recipes.first { it.id == 1L }.photoFile!!))
        assertEquals(2, named.size)
    }

    @Test fun `a recipe already here with no picture gains the file's, and nothing else of it changes`() = runTest {
        dao.recipes += existing(1, "https://www.example.com/brownies?id=7", title = "My own brownies")
        repository().import(fixture("paprika-valid.paprikarecipes"))

        val here = dao.recipes.first { it.id == 1L }
        assertEquals("My own brownies", here.title)
        assertNotNull(here.photoFile)
        assertTrue(store.exists(here.photoFile!!))
    }

    @Test fun `a free library that is full takes none, and its pictures are not kept`() = runTest {
        repeat(50) { dao.recipes += existing(it + 1L, "https://old.example/$it") }
        repository(LibraryLimit.Capped(50)).import(fixture("paprika-valid.paprikarecipes"))
        assertEquals(50, dao.recipes.size)
        assertTrue(store.files().isEmpty())
    }

    @Test fun `a database failure keeps no picture`() = runTest {
        val failing = object : InMemoryBackupDao() {
            override suspend fun insertRecipe(recipe: RecipeEntity): Long = throw IllegalStateException("disk full")
        }
        val result = repository(dao = failing).import(fixture("paprika-valid.paprikarecipes"))
        assertEquals(PaprikaResult.Failure(PaprikaError.SAVE_FAILED), result)
        assertEquals(listOf("paprikaImport failed"), log.messages)
        assertTrue(store.files().isEmpty())
    }

    @Test fun `importing the same file twice adds nothing the second time, pictures included`() = runTest {
        val uri = fixture("paprika-valid.paprikarecipes")
        repository().import(uri)
        val again = summary(repository().import(uri))

        assertEquals(PaprikaSummary(0, 4, 0, 2, 0, 0, null), again)
        assertEquals(3, dao.recipes.size)
        assertEquals(2, store.files().size)
    }

    @Test fun `a recipe already here keeps its content, and only fills an empty note`() = runTest {
        dao.recipes += existing(1, "https://www.example.com/brownies?id=7", title = "My own brownies")
        val result = summary(repository().import(fixture("paprika-valid.paprikarecipes")))

        assertEquals(2, result.imported)
        // The one here, and the second brownies in the file that has the same link.
        assertEquals(2, result.alreadyHere)
        val here = dao.recipes.first { it.id == 1L }
        assertEquals("My own brownies", here.title)
        assertEquals(listOf("1 egg"), here.ingredients)
        assertEquals("PARSED", here.contentOrigin)
        assertEquals("Dad's favourite. Use less sugar next time.", here.notes)
        assertEquals(3, dao.recipes.size)
    }

    // ---- #387: categories become lists

    @Test fun `with the switch on, categories put recipes in lists by name, and new ones are made`() = runTest {
        val result = summary(repository().import(fixture("paprika-valid.paprikarecipes"), filing = true))

        // Dessert, Baking, Soup, Drinks and holiday are new; "dinner" is the Dinner list here, whatever the case.
        assertEquals(5, result.listsMade)
        assertEquals(listOf("Baking", "Dessert", "holiday"), listsOf("Fudgy Brownies"))
        assertEquals(listOf("Dinner", "Soup"), listsOf("Gran's Lentil Soup"))
        // Tea: its categories, and Favorites only because Paprika flags it a favourite.
        assertEquals(listOf("Baking", "Drinks", "Favorites"), listsOf("Mint Tea"))
        assertEquals(7, dao.lists.size)
        // The Dinner and Favorites lists here were found, not made again, and "dinner" didn't rename it.
        assertEquals(listOf("Favorites", "Dinner"), dao.lists.take(2).map { it.name })
        assertTrue(dao.lists.filter { it.isFavorites }.size == 1)
    }

    @Test fun `a category named Favorites is a list of its own, never the Favorites list`() = runTest {
        val file = File(folder.root, "fav.paprikarecipes")
        val json = """{"name": "Soup", "ingredients": "1 onion", "categories": ["favorites"], "on_favorites": 0}"""
        file.writeBytes(zipOf(json))
        files.files["content://fav"] = file
        repository().import("content://fav", filing = true)

        assertEquals(listOf("favorites"), listsOf("Soup"))
        val favorites = dao.lists.first { it.isFavorites }
        assertTrue(dao.refs.none { it.listId == favorites.id })
    }

    @Test fun `with the switch off, nothing is filed, favourites included`() = runTest {
        val result = summary(repository().import(fixture("paprika-valid.paprikarecipes"), filing = false))
        assertEquals(0, result.listsMade)
        assertTrue(dao.refs.isEmpty())
        assertEquals(2, dao.lists.size)
    }

    @Test fun `a recipe already here gains the lists, and one it was already in keeps its place`() = runTest {
        dao.recipes += existing(1, "https://www.example.com/brownies?id=7", title = "My own brownies")
        dao.refs += com.example.recipeclipper.data.local.entity.RecipeListCrossRef(1, 2, addedAt = 99)
        repository().import(fixture("paprika-valid.paprikarecipes"), filing = true)

        assertEquals(listOf("Baking", "Dessert", "Dinner", "holiday"), listsOf("My own brownies"))
        // Insert-or-ignore: Dinner's addedAt was not rewritten.
        assertEquals(99L, dao.refs.first { it.recipeId == 1L && it.listId == 2L }.addedAt)
    }

    @Test fun `recipes the free limit leaves out are filed nowhere, and make no empty list`() = runTest {
        repeat(49) { dao.recipes += existing(it + 1L, "https://old.example/$it") }
        val result = summary(repository(LibraryLimit.Capped(50)).import(fixture("paprika-valid.paprikarecipes"), filing = true))

        // One recipe fits (a typed-in one counts first); the other two are left out with their lists.
        assertEquals(1, result.imported)
        assertEquals(2, result.skippedForLimit)
        val came = dao.recipes.first { it.id > 49 }
        val filed = dao.refs.map { it.recipeId }.toSet()
        assertEquals(setOf(came.id), filed)
        // Only the lists the recipe that came in is in: Dinner/Favorites here, plus what it needed.
        assertTrue(dao.lists.all { list -> dao.refs.any { it.listId == list.id } || list.id <= 2 })
        assertEquals(result.listsMade, dao.lists.size - 2)
    }

    @Test fun `a free library that is full makes no list at all`() = runTest {
        repeat(50) { dao.recipes += existing(it + 1L, "https://old.example/$it") }
        val result = summary(repository(LibraryLimit.Capped(50)).import(fixture("paprika-valid.paprikarecipes"), filing = true))
        assertEquals(0, result.listsMade)
        assertEquals(2, dao.lists.size)
        assertTrue(dao.refs.isEmpty())
    }

    // ---- the rest of #382

    @Test fun `at the free limit what fits comes in, the rest is counted and nothing old is removed`() = runTest {
        repeat(49) { dao.recipes += existing(it + 1L, "https://old.example/$it") }
        val result = summary(repository(LibraryLimit.Capped(50)).import(fixture("paprika-valid.paprikarecipes")))

        assertEquals(1, result.imported)
        assertEquals(2, result.skippedForLimit)
        assertEquals(50, result.freeLimit)
        assertEquals(50, dao.recipes.size)
        assertTrue((1L..49L).all { id -> dao.recipes.any { it.id == id } })
    }

    @Test fun `entries that couldn't be read are counted and the rest import`() = runTest {
        val result = summary(repository().import(fixture("paprika-bad-entries.paprikarecipes")))
        assertEquals(1, result.imported)
        assertEquals(4, result.unreadable)
    }

    @Test fun `a file with nothing readable writes nothing`() = runTest {
        val uri = fixture("paprika-bad-entries.paprikarecipes")
        val result = summary(
            DefaultPaprikaImportRepository(
                FakePaprikaFiles(PaprikaLimits(maxEntryBytes = 10)).also { it.files.putAll(files.files) },
                dao, clock, log, FakeLibraryPolicy(LibraryLimit.Unlimited), store
            ).import(uri)
        )
        assertEquals(PaprikaSummary(0, 0, 0, 0, 5, 0, null), result)
        assertEquals(0, dao.writes)
    }

    @Test fun `a file that isn't Paprika's, a damaged one and an unreadable one say why and write nothing`() = runTest {
        assertEquals(
            PaprikaResult.Failure(PaprikaError.DAMAGED),
            repository().import(fixture("paprika-truncated.paprikarecipes"))
        )
        val text = File(folder.root, "a.txt").apply { writeText("not a zip at all") }
        files.files["content://a.txt"] = text
        assertEquals(PaprikaResult.Failure(PaprikaError.NOT_A_PAPRIKA_FILE), repository().import("content://a.txt"))
        assertEquals(PaprikaResult.Failure(PaprikaError.READ_FAILED), repository().import("content://gone"))
        assertEquals(0, dao.writes)
    }

    @Test fun `a file that unpacks to far more than it is is dropped entry by entry`() = runTest {
        val bomb = summary(repository().import(fixture("paprika-bomb.paprikarecipes")))
        assertEquals(1, bomb.imported)
        assertEquals(1, bomb.unreadable)
    }

    @Test fun `an entry that claims to be 4 GB is dropped unread`() = runTest {
        val huge = summary(repository().import(fixture("paprika-huge-declared.paprikarecipes")))
        assertEquals(1, huge.imported)
        assertEquals(1, huge.unreadable)
    }

    /** A zip of one `.paprikarecipe` entry holding [json], gzipped. */
    private fun zipOf(json: String): ByteArray {
        val gz = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(json.toByteArray()) } }
        return java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("One.paprikarecipe"))
                zip.write(gz.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
    }
}
