package com.example.recipeclipper.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loads a file from `shared/fixtures/` (the same files the iOS tests load). */
internal fun fixture(name: String): String =
    requireNotNull(BackupJsonTest::class.java.classLoader?.getResource("backup/$name")) {
        "missing shared fixture backup/$name"
    }.readText()

internal fun decodeOrFail(text: String): Backup = when (val r = BackupJson.decode(text)) {
    is BackupResult.Success -> r.value
    is BackupResult.Failure -> throw AssertionError("decode failed: ${r.error}")
}

/**
 * The export file format. The fixture assertions are mirrored one for one in the iOS
 * `BackupJsonTests`, against the same file: that pair is what proves an export from one
 * platform reads the same on the other.
 */
class BackupJsonTest {

    private fun error(text: String): BackupError =
        (BackupJson.decode(text) as? BackupResult.Failure)?.error
            ?: throw AssertionError("expected a failure")

    @Test fun `the shared fixture decodes field for field`() {
        val backup = decodeOrFail(fixture("backup-v1.json"))

        assertEquals(1790000000000L, backup.exportedAt)
        assertEquals(listOf("r-soup", "r-pie", "r-pie-dup", "r-bread", "r-old", "e-bread"), backup.recipes.map { it.id })
        val soup = backup.recipes[0]
        assertEquals("https://example.com/soup?utm_source=newsletter", soup.sourceUrl) // cleaned on import, not on read
        assertEquals("BLOG", soup.sourceType)
        assertEquals("Tomato Soup", soup.title)
        assertEquals("https://example.com/img/soup.jpg", soup.imageUrl)
        assertEquals(listOf("2 lb tomatoes", "1 onion, \"chopped\"", "½ tsp salt"), soup.ingredients)
        assertEquals(listOf("Roast the tomatoes.", "Blend with the onion."), soup.instructions)
        assertEquals("10m", soup.prepTime)
        assertEquals("40m", soup.cookTime)
        assertEquals("50m", soup.totalTime)
        assertEquals("4 servings", soup.servings)
        assertEquals(1789000000500L, soup.lastViewedAt)
        assertEquals(setOf(1), soup.checkedIngredients)
        assertEquals("Less salt.\nDouble the onion.", soup.notes)

        val pie = backup.recipes[1]
        assertNull(pie.imageUrl)
        assertNull(pie.prepTime)
        assertEquals(setOf(0, 5), pie.checkedIngredients) // range-checked on import, not on read
        assertNull(pie.notes)

        val dup = backup.recipes[2] // optional fields simply absent
        assertNull(dup.imageUrl)
        assertNull(dup.servings)
        assertEquals(emptySet<Int>(), dup.checkedIngredients)

        assertEquals("SOMETHING_NEW", backup.recipes[5].sourceType)
        assertEquals("   ", backup.recipes[5].notes)

        assertEquals(
            BackupList("f-fav", "Faves", isFavorites = true, isBuiltIn = true, sortOrder = 0, createdAt = 1700000000000L),
            backup.lists[0]
        )
        assertEquals(listOf("f-fav", "f-lunch", "l-week", "f-fakefav", "f-party", "f-party2"), backup.lists.map { it.id })
        assertEquals(" Party food ", backup.lists[4].name)
        assertEquals(7, backup.memberships.size)
        assertEquals(BackupMembership("r-soup", "f-fav", 10), backup.memberships[0])
    }

    @Test fun `a newer format version is refused before anything else is read`() {
        assertEquals(BackupError.NewerVersion(2), error(fixture("backup-v2-newer.json")))
    }

    @Test fun `encode then decode round-trips every field`() {
        val original = decodeOrFail(fixture("backup-v1.json"))
        val again = decodeOrFail(BackupJson.encode(original))
        assertEquals(original, again)
    }

    @Test fun `the encoded file carries the marker, the version and explicit nulls`() {
        val text = BackupJson.encode(decodeOrFail(fixture("backup-v1.json")))
        val root = org.json.JSONObject(text)
        assertEquals("recipe-clipper-backup", root.getString("format"))
        assertEquals(1, root.getInt("formatVersion"))
        assertTrue(root.getJSONArray("recipes").getJSONObject(1).has("imageUrl"))
        assertTrue(root.getJSONArray("recipes").getJSONObject(1).isNull("imageUrl"))
    }

    @Test fun `an empty export is valid`() {
        val backup = decodeOrFail("""{"format":"recipe-clipper-backup","formatVersion":1}""")
        assertEquals(Backup(0, emptyList(), emptyList(), emptyList()), backup)
    }

    @Test fun `anything that isn't an export is NotABackup`() {
        assertEquals(BackupError.NotABackup, error(""))
        assertEquals(BackupError.NotABackup, error("not json"))
        assertEquals(BackupError.NotABackup, error("[1, 2]"))
        assertEquals(BackupError.NotABackup, error("""{"formatVersion":1,"recipes":[]}"""))
        assertEquals(BackupError.NotABackup, error("""{"format":"something-else","formatVersion":1}"""))
        assertEquals(BackupError.NotABackup, error("[".repeat(100_000)))
    }

    @Test fun `a damaged export names the first bad field`() {
        val head = """{"format":"recipe-clipper-backup","formatVersion":1,"""
        assertEquals(BackupError.Malformed("formatVersion"), error("""{"format":"recipe-clipper-backup"}"""))
        assertEquals(BackupError.Malformed("formatVersion"), error("""{"format":"recipe-clipper-backup","formatVersion":"1"}"""))
        assertEquals(BackupError.Malformed("formatVersion"), error("""{"format":"recipe-clipper-backup","formatVersion":0}"""))
        assertEquals(BackupError.Malformed("recipes"), error(head + """"recipes":{}}"""))
        assertEquals(BackupError.Malformed("recipes[0].id"), error(head + """"recipes":[{"sourceUrl":"https://a.b/c","title":"T"}]}"""))
        assertEquals(BackupError.Malformed("recipes[0].sourceUrl"), error(head + """"recipes":[{"id":"a","sourceUrl":" ","title":"T"}]}"""))
        assertEquals(BackupError.Malformed("recipes[0].title"), error(head + """"recipes":[{"id":"a","sourceUrl":"https://a.b/c"}]}"""))
        assertEquals(
            BackupError.Malformed("recipes[0].ingredients[1]"),
            error(head + """"recipes":[{"id":"a","sourceUrl":"https://a.b/c","title":"T","ingredients":["x",2]}]}""")
        )
        assertEquals(
            BackupError.Malformed("recipes[0].lastViewedAt"),
            error(head + """"recipes":[{"id":"a","sourceUrl":"https://a.b/c","title":"T","lastViewedAt":1.5}]}""")
        )
        assertEquals(
            BackupError.Malformed("recipes[1].id"),
            error(head + """"recipes":[{"id":"a","sourceUrl":"https://a.b/c","title":"T"},{"id":"a","sourceUrl":"https://a.b/d","title":"U"}]}""")
        )
        assertEquals(BackupError.Malformed("lists[0].name"), error(head + """"lists":[{"id":"l","name":""}]}"""))
        assertEquals(BackupError.Malformed("lists[0].isFavorites"), error(head + """"lists":[{"id":"l","name":"L","isFavorites":1}]}"""))
        assertEquals(
            BackupError.Malformed("memberships[0].listId"),
            error(head + """"recipes":[{"id":"a","sourceUrl":"https://a.b/c","title":"T"}],"memberships":[{"recipeId":"a","listId":"nope"}]}""")
        )
        assertEquals(
            BackupError.Malformed("memberships[0].recipeId"),
            error(head + """"lists":[{"id":"l","name":"L"}],"memberships":[{"recipeId":"nope","listId":"l"}]}""")
        )
    }
}
