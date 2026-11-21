package com.example.recipeclipper.data

import com.example.recipeclipper.data.backup.PaprikaSummary
import com.example.recipeclipper.data.local.entity.ListEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.remote.ImportSource
import com.example.recipeclipper.data.remote.PaprikaError
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
 * Import from Recipe Keeper (#390) over an in-memory DAO and the real archive reader on the shared
 * fixtures, through the repository the Paprika import uses (the source picks the reader and the
 * key). The SQL of the write is `BackupDaoTest`'s; this is the plan.
 */
class DefaultRecipeKeeperImportRepositoryTest {

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
        lists += ListEntity(id = 1, uid = "l-fav", name = "Favorites", isBuiltIn = true, isFavorites = true, sortOrder = 0, createdAt = 0)
        lists += ListEntity(id = 2, uid = "l-dinner", name = "Dinner", isBuiltIn = true, isFavorites = false, sortOrder = 2, createdAt = 0)
    }
    private val files = FakePaprikaFiles(photos = store)

    private fun fixture(name: String): String {
        val file = File(folder.root, name)
        javaClass.getResourceAsStream("/recipekeeper/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        files.files["content://$name"] = file
        return "content://$name"
    }

    private fun repository(limit: LibraryLimit = LibraryLimit.Unlimited, dao: InMemoryBackupDao = this.dao) =
        DefaultPaprikaImportRepository(files, dao, clock, log, FakeLibraryPolicy(limit), store)

    private suspend fun import(name: String, filing: Boolean = false, repo: DefaultPaprikaImportRepository = repository()) =
        repo.import(fixture(name), filing, ImportSource.RECIPE_KEEPER)

    private fun summary(result: PaprikaResult) = (result as PaprikaResult.Success).summary

    private fun existing(id: Long, url: String, title: String = "Mine") = RecipeEntity(
        id = id, uid = "e$id", sourceUrl = url, title = title, imageUrl = null, ingredients = listOf("1 egg"),
        instructions = listOf("Cook."), prepTime = null, cookTime = null, totalTime = null, servings = null,
        sourceType = "BLOG", lastViewedAt = 1
    )

    private fun listsOf(recipe: String): List<String> {
        val id = dao.recipes.first { it.title == recipe }.id
        return dao.refs.filter { it.recipeId == id }.map { ref -> dao.lists.first { it.id == ref.listId }.name }.sorted()
    }

    @Test fun `a file's recipes join the library as the cook's own, as the file wrote them`() = runTest {
        val result = summary(import("recipekeeper-valid.zip"))

        // Six recipes; the second cheesecake has the first's id; two blocks are no recipe; pasta's picture is missing.
        assertEquals(PaprikaSummary(6, 1, 0, 2, 0, 1, null), result)
        assertEquals(
            listOf("Blueberry Cheesecake", "Chocolate Chip Cookies", "Tiramisù", "Pasta al Pomodoro", "Mint Tea", "Mac & Cheese – Quick"),
            dao.recipes.sortedBy { it.id }.map { it.title }
        )
        val cheesecake = dao.recipes.first { it.title == "Blueberry Cheesecake" }
        assertEquals("manual:recipekeeper-a2489b23-050f-5c8a-8e5d-12fdb9fdeea3", cheesecake.sourceUrl)
        assertEquals("MANUAL", cheesecake.contentOrigin)
        assertEquals("Serves 6-8", cheesecake.servings)
        assertEquals("10m", cheesecake.prepTime)
        assertEquals("1h", cheesecake.cookTime)
        assertEquals("Better the next day.\nFreezes well.", cheesecake.notes)
        assertEquals("For the base:", cheesecake.ingredients.first())
        assertEquals(6, cheesecake.ingredients.size)
        assertEquals(3, cheesecake.instructions.size)
        val cookies = dao.recipes.first { it.title == "Chocolate Chip Cookies" }
        assertEquals("https://www.example.com/cookies?id=3", cookies.sourceUrl)
        assertEquals("EDITED", cookies.contentOrigin)
        assertEquals("5m", cookies.prepTime)
        assertNull("PT0S is none", cookies.cookTime)
        assertEquals("manual:recipekeeper-h258018455a849b2a", dao.recipes.first { it.title == "Mint Tea" }.sourceUrl)
        // Without the switch nothing is filed and no list is made.
        assertTrue(dao.refs.isEmpty())
        assertEquals(2, dao.lists.size)
    }

    @Test fun `each recipe's first readable picture becomes its own, named by the store`() = runTest {
        import("recipekeeper-valid.zip")
        val photos = dao.recipes.associate { it.title to it.photoFile }
        assertNotNull(photos["Blueberry Cheesecake"])
        assertNotNull(photos["Chocolate Chip Cookies"])
        assertNotNull("the second picture, the first is no image", photos["Tiramisù"])
        assertNotNull("a name with a space in the zip", photos["Mac & Cheese – Quick"])
        assertNull(photos["Pasta al Pomodoro"])
        assertNull(photos["Mint Tea"])
        assertEquals(4, store.files().size)
        assertEquals(dao.recipes.mapNotNull { it.photoFile }.toSet(), store.files().keys)
        assertTrue(dao.cookedPhotos.isEmpty())
    }

    @Test fun `importing the same file twice adds nothing the second time, pictures included`() = runTest {
        import("recipekeeper-valid.zip")
        val again = summary(import("recipekeeper-valid.zip"))

        assertEquals(PaprikaSummary(0, 7, 0, 2, 0, 1, null), again)
        assertEquals(6, dao.recipes.size)
        assertEquals(4, store.files().size)
    }

    @Test fun `a recipe already here by its link keeps its content and gains the file's picture`() = runTest {
        dao.recipes += existing(1, "https://www.example.com/cookies?id=3", title = "My own cookies")
        val result = summary(import("recipekeeper-valid.zip"))

        assertEquals(5, result.imported)
        assertEquals(2, result.alreadyHere)
        val here = dao.recipes.first { it.id == 1L }
        assertEquals("My own cookies", here.title)
        assertEquals(listOf("1 egg"), here.ingredients)
        assertNotNull(here.photoFile)
        assertEquals(6, dao.recipes.size)
    }

    @Test fun `a Paprika recipe and a Recipe Keeper one with the same text are two recipes`() = runTest {
        dao.recipes += existing(1, "manual:paprika-h258018455a849b2a", title = "Mint Tea")
        val result = summary(import("recipekeeper-valid.zip"))
        assertEquals(6, result.imported)
        assertEquals(7, dao.recipes.size)
    }

    // ---- #387: courses, categories and collections become lists

    @Test fun `with the switch on, courses, categories and collections put recipes in lists by name`() = runTest {
        val result = summary(import("recipekeeper-valid.zip", filing = true))

        // Dessert, Cake, Holiday Baking, Party, Snack, Baking, Cookie, Dolce, Dolci, Dolci della nonna, Drinks.
        assertEquals(11, result.listsMade)
        assertEquals(listOf("Cake", "Dessert", "Holiday Baking", "Party"), listsOf("Blueberry Cheesecake"))
        // Favourite: the Favorites list, found by its flag.
        assertEquals(listOf("Baking", "Cookie", "Dessert", "Favorites", "Snack"), listsOf("Chocolate Chip Cookies"))
        assertEquals(listOf("Dolce", "Dolci", "Dolci della nonna"), listsOf("Tiramisù"))
        assertEquals(listOf("Drinks"), listsOf("Mint Tea"))
        assertEquals(emptyList<String>(), listsOf("Pasta al Pomodoro"))
        assertEquals(13, dao.lists.size)
        assertEquals(1, dao.lists.count { it.isFavorites })
    }

    @Test fun `a list that is already here is found by its name, whatever the case, and not made again`() = runTest {
        dao.lists += ListEntity(id = 3, uid = "l-cake", name = "cake", isBuiltIn = false, isFavorites = false, sortOrder = 5, createdAt = 0)
        val result = summary(import("recipekeeper-valid.zip", filing = true))
        assertEquals(10, result.listsMade)
        assertTrue(dao.lists.none { it.name == "Cake" })
        assertTrue("cake" in listsOf("Blueberry Cheesecake"))
    }

    @Test fun `with the switch off, nothing is filed, favourites included`() = runTest {
        val result = summary(import("recipekeeper-valid.zip", filing = false))
        assertEquals(0, result.listsMade)
        assertTrue(dao.refs.isEmpty())
    }

    // ---- the free limit

    @Test fun `at the free limit what fits comes in, the rest is counted and nothing old is removed`() = runTest {
        repeat(49) { dao.recipes += existing(it + 1L, "https://old.example/$it") }
        val result = summary(import("recipekeeper-valid.zip", filing = true, repo = repository(LibraryLimit.Capped(50))))

        assertEquals(1, result.imported)
        assertEquals(5, result.skippedForLimit)
        assertEquals(50, result.freeLimit)
        assertEquals(50, dao.recipes.size)
        assertTrue((1L..49L).all { id -> dao.recipes.any { it.id == id } })
        // The file's first recipe is the one that fits; the others' pictures and lists are not kept.
        assertEquals("Blueberry Cheesecake", dao.recipes.last().title)
        assertEquals(1, store.files().size)
        assertEquals(result.listsMade, dao.lists.size - 2)
        assertEquals(setOf(dao.recipes.last().id), dao.refs.map { it.recipeId }.toSet())
    }

    @Test fun `a full free library takes none and keeps no picture or list`() = runTest {
        repeat(50) { dao.recipes += existing(it + 1L, "https://old.example/$it") }
        val result = summary(import("recipekeeper-valid.zip", filing = true, repo = repository(LibraryLimit.Capped(50))))
        assertEquals(0, result.imported)
        assertEquals(6, result.skippedForLimit)
        assertEquals(50, dao.recipes.size)
        assertTrue(store.files().isEmpty())
        assertEquals(2, dao.lists.size)
    }

    @Test fun `a database failure writes nothing and keeps no picture`() = runTest {
        val failing = object : InMemoryBackupDao() {
            override suspend fun insertRecipe(recipe: RecipeEntity): Long = throw IllegalStateException("disk full")
        }
        val result = import("recipekeeper-valid.zip", repo = repository(dao = failing))
        assertEquals(PaprikaResult.Failure(PaprikaError.SAVE_FAILED), result)
        assertEquals(listOf("recipeKeeperImport failed"), log.messages)
        assertTrue(store.files().isEmpty())
    }

    // ---- other layouts and refused files

    @Test fun `a wrapper folder, a BOM and addresses that lead outside the zip`() = runTest {
        val result = summary(import("recipekeeper-wrapper.zip"))
        assertEquals(PaprikaSummary(2, 0, 0, 0, 0, 1, null), result)
        assertEquals(listOf("Gran's Soup", "Flapjacks"), dao.recipes.sortedBy { it.id }.map { it.title })
        assertNotNull(dao.recipes.first { it.title == "Gran's Soup" }.photoFile)
        assertNull(dao.recipes.first { it.title == "Flapjacks" }.photoFile)
    }

    @Test fun `a picture that is too big or only claims to be is left out and the recipe is kept`() = runTest {
        val result = summary(import("recipekeeper-hostile-photos.zip"))
        assertEquals(PaprikaSummary(4, 0, 0, 0, 0, 2, null), result)
        assertEquals(1, store.files().size)
    }

    @Test fun `a file that isn't Recipe Keeper's, a damaged one, a huge one and an unreadable one say why and write nothing`() = runTest {
        assertEquals(PaprikaResult.Failure(PaprikaError.NOT_A_RECIPE_KEEPER_FILE), import("recipekeeper-not.zip"))
        assertEquals(PaprikaResult.Failure(PaprikaError.NOT_A_RECIPE_KEEPER_FILE), import("recipekeeper-no-page.zip"))
        assertEquals(PaprikaResult.Failure(PaprikaError.DAMAGED), import("recipekeeper-truncated.zip"))
        assertEquals(PaprikaResult.Failure(PaprikaError.TOO_LARGE), import("recipekeeper-bomb.zip"))
        assertEquals(PaprikaResult.Failure(PaprikaError.TOO_LARGE), import("recipekeeper-huge-declared.zip"))
        assertEquals(PaprikaResult.Failure(PaprikaError.READ_FAILED), repository().import("content://gone", false, ImportSource.RECIPE_KEEPER))
        assertEquals(0, dao.writes)
        assertTrue(store.files().isEmpty())
    }

    @Test fun `a Paprika file given to the Recipe Keeper import is not Recipe Keeper's`() = runTest {
        val file = File(folder.root, "p.paprikarecipes")
        javaClass.getResourceAsStream("/paprika/paprika-valid.paprikarecipes")!!.use { i -> file.outputStream().use { i.copyTo(it) } }
        files.files["content://p"] = file
        assertEquals(
            PaprikaResult.Failure(PaprikaError.NOT_A_RECIPE_KEEPER_FILE),
            repository().import("content://p", false, ImportSource.RECIPE_KEEPER)
        )
    }

    @Test fun `deep nesting still imports the recipe`() = runTest {
        assertEquals(1, summary(import("recipekeeper-deep.zip")).imported)
    }
}
