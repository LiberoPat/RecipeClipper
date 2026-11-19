package com.example.recipeclipper.data

import com.example.recipeclipper.data.backup.PaprikaSummary
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.remote.PaprikaError
import com.example.recipeclipper.data.remote.PaprikaLimits
import com.example.recipeclipper.fake.FakeLibraryPolicy
import com.example.recipeclipper.fake.FakePaprikaFiles
import com.example.recipeclipper.fake.InMemoryBackupDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Import from Paprika (#382) over an in-memory DAO and the real archive reader on the shared
 * fixtures. The SQL of the write is `BackupDaoTest`'s (and every other import's); this is the plan.
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
    private val dao = InMemoryBackupDao()
    private val files = FakePaprikaFiles()

    private fun fixture(name: String): String {
        val file = File(folder.root, name)
        javaClass.getResourceAsStream("/paprika/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        files.files["content://$name"] = file
        return "content://$name"
    }

    private fun repository(limit: LibraryLimit = LibraryLimit.Unlimited, dao: InMemoryBackupDao = this.dao) =
        DefaultPaprikaImportRepository(files, dao, clock, log, FakeLibraryPolicy(limit))

    private fun summary(result: PaprikaResult) = (result as PaprikaResult.Success).summary

    private fun existing(id: Long, url: String, title: String = "Mine") = RecipeEntity(
        id = id, uid = "e$id", sourceUrl = url, title = title, imageUrl = null, ingredients = listOf("1 egg"),
        instructions = listOf("Cook."), prepTime = null, cookTime = null, totalTime = null, servings = null,
        sourceType = "BLOG", lastViewedAt = 1
    )

    @Test fun `a file's recipes join the library as the cook's own`() = runTest {
        val result = summary(repository().import(fixture("paprika-valid.paprikarecipes")))

        // Brownies, soup and tea; the second brownies has the first's link; two entries are no recipe.
        assertEquals(PaprikaSummary(3, 1, 0, 2, 0, true, null), result)
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
        assertTrue(dao.refs.isEmpty() && dao.lists.isEmpty())
    }

    @Test fun `importing the same file twice adds nothing the second time`() = runTest {
        val uri = fixture("paprika-valid.paprikarecipes")
        repository().import(uri)
        val again = summary(repository().import(uri))

        assertEquals(PaprikaSummary(0, 4, 0, 2, 0, true, null), again)
        assertEquals(3, dao.recipes.size)
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

    @Test fun `at the free limit what fits comes in, the rest is counted, and nothing old is removed`() = runTest {
        repeat(49) { dao.recipes += existing(it + 1L, "https://old.example/$it") }
        val result = summary(repository(LibraryLimit.Capped(50)).import(fixture("paprika-valid.paprikarecipes")))

        assertEquals(1, result.imported)
        assertEquals(2, result.skippedForLimit)
        assertEquals(50, result.freeLimit)
        assertEquals(50, dao.recipes.size)
        assertTrue((1L..49L).all { id -> dao.recipes.any { it.id == id } })
    }

    @Test fun `a full free library takes none and says so`() = runTest {
        repeat(50) { dao.recipes += existing(it + 1L, "https://old.example/$it") }
        val result = summary(repository(LibraryLimit.Capped(50)).import(fixture("paprika-valid.paprikarecipes")))
        assertEquals(0, result.imported)
        assertEquals(3, result.skippedForLimit)
        assertEquals(50, dao.recipes.size)
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
                dao, clock, log, FakeLibraryPolicy(LibraryLimit.Unlimited)
            ).import(uri)
        )
        assertEquals(PaprikaSummary(0, 0, 0, 0, 5, false, null), result)
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

    @Test fun `a database failure writes nothing the cook could see and says so`() = runTest {
        val failing = object : InMemoryBackupDao() {
            override suspend fun insertRecipe(recipe: RecipeEntity): Long = throw IllegalStateException("disk full")
        }
        val result = repository(dao = failing).import(fixture("paprika-valid.paprikarecipes"))
        assertEquals(PaprikaResult.Failure(PaprikaError.SAVE_FAILED), result)
        assertEquals(listOf("paprikaImport failed"), log.messages)
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
}
