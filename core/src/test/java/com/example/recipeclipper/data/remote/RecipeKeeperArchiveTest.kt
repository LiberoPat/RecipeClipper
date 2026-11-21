package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reading a Recipe Keeper export (#390). The files in `shared/fixtures/recipekeeper/` are built by
 * `tools/recipekeeper-fixtures/make_fixtures.py` from the format the open-source importers document;
 * iOS's `RecipeKeeperZipReaderTests` reads the same files and expects the same counts.
 */
class RecipeKeeperArchiveTest {

    @get:Rule val folder = TemporaryFolder()

    private fun fixture(name: String): File {
        val file = folder.newFile(name)
        javaClass.getResourceAsStream("/recipekeeper/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun success(read: PaprikaRead) = (read as PaprikaRead.Success).contents

    private fun failure(read: PaprikaRead) = (read as PaprikaRead.Failure).error

    /** A store like the real one: a name for a picture, null for what isn't one. */
    private class Store {
        val bytes = mutableListOf<String>()
        fun store(picture: ByteArray): String? =
            if (String(picture).startsWith("bad")) null else "stored-${bytes.size + 1}.jpg".also { bytes += String(picture, Charsets.ISO_8859_1) }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val file = folder.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test fun `a valid export gives its recipes in order and counts what it skipped`() {
        val store = Store()
        val contents = success(RecipeKeeperArchive.read(fixture("recipekeeper-valid.zip"), PaprikaLimits(), store::store))
        assertEquals(
            listOf(
                "Blueberry Cheesecake", "Chocolate Chip Cookies", "Tiramisù", "Pasta al Pomodoro",
                "Blueberry Cheesecake (copy)", "Mint Tea", "Mac & Cheese – Quick"
            ),
            contents.recipes.map { it.name }
        )
        // The block with no name, and the one with a name and nothing else.
        assertEquals(2, contents.notRecipes)
        assertEquals(0, contents.unreadable)
        // Pasta's only picture isn't in the zip.
        assertEquals(1, contents.photosLeftOut)
    }

    @Test fun `a recipe reads as the shape the Paprika import maps`() {
        val contents = success(RecipeKeeperArchive.read(fixture("recipekeeper-valid.zip"), PaprikaLimits(), Store()::store))
        val cheesecake = contents.recipes[0]
        assertEquals("a2489b23-050f-5c8a-8e5d-12fdb9fdeea3", cheesecake.uid)
        assertEquals(
            listOf(
                "For the base:", "1 1/2 cups digestive biscuit crumbs", "1/2 cup melted butter",
                "For the filling:", "600 g cream cheese", "2 large eggs"
            ),
            cheesecake.ingredients
        )
        assertEquals(
            listOf(
                "Melt the butter and mix it into the crumbs. Press into a 9-inch pan. Bake at 325°F for 10 minutes.",
                "Beat the cream cheese with the eggs until smooth.",
                "Pour over the base and bake for 40 minutes."
            ),
            cheesecake.directions
        )
        assertEquals("Better the next day.\nFreezes well.", cheesecake.notes)
        assertEquals("Serves 6-8", cheesecake.servings)
        assertEquals("PT10M", cheesecake.prepTime)
        assertEquals("PT1H", cheesecake.cookTime)
        assertNull(cheesecake.totalTime)
        assertNull("'Recipe Keeper' is no link", cheesecake.sourceUrl)
        assertEquals(listOf("Dessert", "Cake", "Holiday Baking"), cheesecake.categories)
        assertFalse(cheesecake.favorite)

        val cookies = contents.recipes[1]
        assertEquals("https://www.example.com/cookies?id=3", cookies.sourceUrl)
        assertTrue(cookies.favorite)
        assertEquals(listOf("Snack", "Dessert", "Baking", "Cookie"), cookies.categories)

        val tiramisu = contents.recipes[2]
        assertEquals("4 persone", tiramisu.servings)
        assertEquals(listOf("Per la crema:", "3 uova", "250 g mascarpone", "Per la base:", "savoiardi q.b."), tiramisu.ingredients)

        val pasta = contents.recipes[3]
        assertEquals(listOf("Cook the pasta.", "2 minutes before the end, add the basil.", "Serve."), pasta.directions)
        assertNull(contents.recipes[5].uid)
    }

    @Test fun `each recipe's first readable picture is stored, the next is tried when one is not an image`() {
        val store = Store()
        val contents = success(RecipeKeeperArchive.read(fixture("recipekeeper-valid.zip"), PaprikaLimits(), store::store))
        val files = contents.recipes.map { it.photoFile }
        // Cheesecake's first, cookies', tiramisu's second (the first is not an image), and the
        // percent-encoded name of the macaroni one; pasta's is missing and the others have none.
        assertEquals(listOf("stored-1.jpg", "stored-2.jpg", "stored-3.jpg", null, null, null, "stored-4.jpg"), files)
        assertEquals(4, store.bytes.size)
        assertTrue(contents.recipes.all { it.photoData == null })
    }

    @Test fun `with no store every picture is left out and counted, the recipes stay`() {
        val contents = success(RecipeKeeperArchive.read(fixture("recipekeeper-valid.zip")))
        assertEquals(7, contents.recipes.size)
        // Cheesecake, cookies, tiramisu, pasta and macaroni each named a picture.
        assertEquals(5, contents.photosLeftOut)
    }

    @Test fun `the page may sit in one wrapper folder, behind a BOM, and only the shallowest is read`() {
        val store = Store()
        val contents = success(RecipeKeeperArchive.read(fixture("recipekeeper-wrapper.zip"), PaprikaLimits(), store::store))
        assertEquals(listOf("Gran's Soup", "Flapjacks"), contents.recipes.map { it.name })
        assertEquals(listOf("stored-1.jpg", null), contents.recipes.map { it.photoFile })
        // Flapjacks names five pictures that all point outside the zip: none is followed.
        assertEquals(1, contents.photosLeftOut)
        assertEquals(1, store.bytes.size)
    }

    @Test fun `a picture's address is only ever compared with entry names`() {
        assertEquals("a/images/x.jpg", RecipeKeeperArchive.resolve("a", "images/x.jpg"))
        assertEquals("images/x.jpg", RecipeKeeperArchive.resolve("", "./images/./x.jpg"))
        assertEquals("images/x y.jpg", RecipeKeeperArchive.resolve("", "images/x%20y.jpg"))
        assertEquals("b/x.jpg", RecipeKeeperArchive.resolve("a", "../b/x.jpg"))
        assertNull(RecipeKeeperArchive.resolve("", "../x.jpg"))
        assertNull(RecipeKeeperArchive.resolve("a", "../../x.jpg"))
        assertNull(RecipeKeeperArchive.resolve("", "/etc/passwd"))
        assertNull(RecipeKeeperArchive.resolve("", "https://evil.example/x.jpg"))
        assertNull(RecipeKeeperArchive.resolve("", "C:\\x.jpg"))
        assertNull(RecipeKeeperArchive.resolve("", "images\\x.jpg"))
        assertNull(RecipeKeeperArchive.resolve("", ""))
    }

    @Test fun `folders, macOS copies and hidden files are no page`() {
        assertTrue(RecipeKeeperArchive.isPageEntry("recipes.html"))
        assertTrue(RecipeKeeperArchive.isPageEntry("RecipeKeeper_20261119/recipes.html"))
        assertTrue(RecipeKeeperArchive.isPageEntry("Recipes.HTML"))
        assertFalse(RecipeKeeperArchive.isPageEntry("__MACOSX/x/recipes.html"))
        assertFalse(RecipeKeeperArchive.isPageEntry("x/__MACOSX/recipes.html"))
        assertFalse(RecipeKeeperArchive.isPageEntry("._recipes.html"))
        assertFalse(RecipeKeeperArchive.isPageEntry("recipes.html/"))
        assertFalse(RecipeKeeperArchive.isPageEntry("my-recipes.html"))
    }

    @Test fun `a page with no recipe blocks, or no page at all, is not Recipe Keeper's`() {
        assertEquals(PaprikaError.NOT_A_RECIPE_KEEPER_FILE, failure(RecipeKeeperArchive.read(fixture("recipekeeper-not.zip"))))
        assertEquals(PaprikaError.NOT_A_RECIPE_KEEPER_FILE, failure(RecipeKeeperArchive.read(fixture("recipekeeper-no-page.zip"))))
        val text = folder.newFile("notes.txt").apply { writeText("Dear diary, today I made soup.") }
        assertEquals(PaprikaError.NOT_A_RECIPE_KEEPER_FILE, failure(RecipeKeeperArchive.read(text)))
        assertEquals(PaprikaError.NOT_A_RECIPE_KEEPER_FILE, failure(RecipeKeeperArchive.read(folder.newFile())))
    }

    @Test fun `a Paprika file is not Recipe Keeper's, and the other way round`() {
        val paprika = folder.newFile("p.paprikarecipes")
        javaClass.getResourceAsStream("/paprika/paprika-valid.paprikarecipes")!!.use { i -> paprika.outputStream().use { i.copyTo(it) } }
        assertEquals(PaprikaError.NOT_A_RECIPE_KEEPER_FILE, failure(RecipeKeeperArchive.read(paprika)))
        assertEquals(PaprikaError.NOT_A_PAPRIKA_FILE, failure(PaprikaArchive.read(fixture("recipekeeper-valid.zip"))))
    }

    @Test fun `a truncated zip is damaged and imports nothing`() {
        assertEquals(PaprikaError.DAMAGED, failure(RecipeKeeperArchive.read(fixture("recipekeeper-truncated.zip"))))
    }

    @Test fun `a page that claims 4 GB, or unpacks past the cap, is refused for the whole file`() {
        assertEquals(PaprikaError.TOO_LARGE, failure(RecipeKeeperArchive.read(fixture("recipekeeper-huge-declared.zip"))))
        val bomb = fixture("recipekeeper-bomb.zip")
        assertTrue("the bomb is small", bomb.length() < 100_000)
        assertEquals(PaprikaError.TOO_LARGE, failure(RecipeKeeperArchive.read(bomb)))
    }

    @Test fun `too many entries, or more unpacked than the total cap, is refused`() {
        val file = fixture("recipekeeper-valid.zip")
        assertEquals(PaprikaError.TOO_LARGE, failure(RecipeKeeperArchive.read(file, PaprikaLimits(maxEntries = 3))))
        assertEquals(PaprikaError.TOO_LARGE, failure(RecipeKeeperArchive.read(file, PaprikaLimits(maxTotalBytes = 600))))
        assertEquals(PaprikaError.TOO_LARGE, failure(RecipeKeeperArchive.read(file, PaprikaLimits(maxEntryBytes = 600))))
    }

    @Test fun `a picture that is too big, or only claims to be, is left out and the recipe stays`() {
        val store = Store()
        val contents = success(RecipeKeeperArchive.read(fixture("recipekeeper-hostile-photos.zip"), PaprikaLimits(), store::store))
        assertEquals(listOf("Toast", "Jam", "Honey", "Salt"), contents.recipes.map { it.name })
        assertEquals(listOf("stored-1.jpg", null, null, null), contents.recipes.map { it.photoFile })
        // Jam's unpacks past 8 MiB, Honey's header says 4 GB; Salt names none.
        assertEquals(2, contents.photosLeftOut)
        assertEquals(1, store.bytes.size)
    }

    @Test fun `a lowered picture cap leaves a picture out`() {
        val contents = success(RecipeKeeperArchive.read(fixture("recipekeeper-valid.zip"), PaprikaLimits(maxPhotoBytes = 8)) { "kept.jpg" })
        assertEquals(5, contents.photosLeftOut)
        assertTrue(contents.recipes.all { it.photoFile == null })
    }

    @Test fun `nesting a hundred thousand deep neither crashes nor loses the recipe`() {
        val contents = success(RecipeKeeperArchive.read(fixture("recipekeeper-deep.zip")))
        assertEquals(listOf("Deep"), contents.recipes.map { it.name })
    }

    @Test fun `an entry name that is a path does nothing`() {
        val html = """<div class="recipe-details"><h2 itemprop="name">Soup</h2><div itemprop="recipeIngredients"><p>1 onion</p></div></div>"""
        val zip = zipOf("../../etc/recipes.html" to html.toByteArray())
        assertEquals(listOf("Soup"), success(RecipeKeeperArchive.read(zip)).recipes.map { it.name })
    }
}
