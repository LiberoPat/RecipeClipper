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

        // The pantry (#51) and groceries (#50): later sections, no version bump.
        assertEquals(listOf("p-flour", "p-salt", "p-here", "p-flour2"), backup.pantry.map { it.id })
        assertEquals(
            BackupPantryItem("p-flour", " Flour ", "half a bag", "en", "baking", inStock = true, alwaysHave = false,
                purchasedDay = 20700, expiresDay = 20900, updatedAt = 1789000000000),
            backup.pantry[0]
        )
        val defaults = backup.pantry[3] // absent fields: no dates, no quantity, updatedAt 0
        assertEquals(false, defaults.inStock)
        assertEquals(false, defaults.alwaysHave)
        assertNull(defaults.quantity)
        assertNull(defaults.expiresDay)
        assertEquals(0L, defaults.updatedAt)

        assertEquals(listOf("g-tomatoes", "g-apples", "g-beef", "g-milk", "g-here"), backup.groceries.map { it.id })
        assertEquals(
            BackupGroceryItem("g-tomatoes", "2 lb tomatoes", "en", "produce", checked = false, recipeId = "r-soup",
                plannedDay = 20720, updatedAt = 1789000000000),
            backup.groceries[0]
        )
        assertEquals(true, backup.groceries[1].checked)
        assertNull(backup.groceries[3].recipeId) // names no recipe in the file: none

        // The meal plan (#49): meal types and entries, later sections too.
        assertEquals(listOf("t-dinner", "t-brunch", "t-fakedinner", "t-tea"), backup.mealTypes.map { it.id })
        assertEquals(BackupMealType("t-dinner", "Dinner", "dinner", 2, 1), backup.mealTypes[0])
        assertEquals(BackupMealType("t-fakedinner", "Dinner", null, 5, 0), backup.mealTypes[2])
        assertEquals(listOf("m-soup", "m-old", "m-note", "m-pie", "m-missing", "m-here"), backup.mealPlan.map { it.id })
        assertEquals(BackupPlanEntry("m-soup", 20720, "t-dinner", "r-soup", 6, null, 0, 1789000000000L), backup.mealPlan[0])
        assertEquals(BackupPlanEntry("m-note", 20721, null, null, null, "Leftovers", 1, 3), backup.mealPlan[2])
        assertNull(backup.mealPlan[4].recipeId) // names no recipe in the file: none
    }

    @Test fun `a file without pantry or groceries reads them as empty`() {
        val backup = decodeOrFail("""{"format": "recipe-clipper-backup", "formatVersion": 1}""")
        assertTrue(backup.pantry.isEmpty())
        assertTrue(backup.groceries.isEmpty())
        assertTrue(backup.mealTypes.isEmpty())
        assertTrue(backup.mealPlan.isEmpty())
    }

    @Test fun `a planned meal needs a day, a meal type a name, and ids are unique`() {
        assertEquals(
            BackupError.Malformed("mealPlan[0].day"),
            error("""{"format": "recipe-clipper-backup", "formatVersion": 1, "mealPlan": [{"id": "m", "note": "x"}]}""")
        )
        assertEquals(
            BackupError.Malformed("mealTypes[0].name"),
            error("""{"format": "recipe-clipper-backup", "formatVersion": 1, "mealTypes": [{"id": "t", "name": ""}]}""")
        )
        assertEquals(
            BackupError.Malformed("mealPlan[1].id"),
            error("""{"format": "recipe-clipper-backup", "formatVersion": 1, "mealPlan": [{"id": "m", "day": 1}, {"id": "m", "day": 2}]}""")
        )
    }

    @Test fun `menus decode, round-trip, and a menu meal needs its menu and a day offset`() {
        val head = """"format": "recipe-clipper-backup", "formatVersion": 1"""
        val backup = decodeOrFail(
            """{$head, "menus": [{"id": "menu-a", "name": "Week A", "updatedAt": 5}],
            "menuEntries": [{"id": "me-1", "menuId": "menu-a", "dayOffset": 3, "mealTypeId": "t-gone",
            "recipeId": "r-gone", "servings": 4, "note": null, "sortOrder": 1, "updatedAt": 6}]}"""
        )
        assertEquals(listOf(BackupMenu("menu-a", "Week A", 5)), backup.menus)
        // A meal type or recipe the file doesn't have reads as none.
        assertEquals(BackupMenuEntry("me-1", "menu-a", 3, null, null, 4, null, 1, 6), backup.menuEntries.single())
        assertEquals(backup, decodeOrFail(BackupJson.encode(backup)))
        assertTrue(decodeOrFail("{$head}").menus.isEmpty())

        val menu = """"menus": [{"id": "menu-a", "name": "A"}]"""
        assertEquals(
            BackupError.Malformed("menuEntries[0].menuId"),
            error("""{$head, $menu, "menuEntries": [{"id": "e", "menuId": "other", "dayOffset": 0}]}""")
        )
        assertEquals(
            BackupError.Malformed("menuEntries[0].dayOffset"),
            error("""{$head, $menu, "menuEntries": [{"id": "e", "menuId": "menu-a", "dayOffset": 7}]}""")
        )
        assertEquals(BackupError.Malformed("menus[0].name"), error("""{$head, "menus": [{"id": "m", "name": " "}]}"""))
    }

    @Test fun `a pantry item needs a name, and ids are unique`() {
        assertEquals(
            BackupError.Malformed("pantry[0].name"),
            error("""{"format": "recipe-clipper-backup", "formatVersion": 1, "pantry": [{"id": "p", "name": " "}]}""")
        )
        assertEquals(
            BackupError.Malformed("groceries[1].id"),
            error("""{"format": "recipe-clipper-backup", "formatVersion": 1, "groceries": [{"id": "g", "text": "a"}, {"id": "g", "text": "b"}]}""")
        )
    }

    @Test fun `a newer format version is refused before anything else is read`() {
        assertEquals(BackupError.NewerVersion(2), error(fixture("backup-v2-newer.json")))
    }

    @Test fun `encode then decode round-trips every field`() {
        val original = decodeOrFail(fixture("backup-v1.json"))
        val again = decodeOrFail(BackupJson.encode(original))
        assertEquals(original, again)
    }

    @Test fun `a recipe's language round-trips, and a file without one reads as none`() {
        val original = decodeOrFail(fixture("backup-v1.json"))
        assertTrue(original.recipes.all { it.language == null })
        val withLanguage = original.copy(recipes = original.recipes.map { it.copy(language = "de-de") })
        assertEquals(withLanguage, decodeOrFail(BackupJson.encode(withLanguage)))
    }

    @Test fun `a recipe's content origin round-trips, and a file without one reads as parsed`() {
        val original = decodeOrFail(fixture("backup-v1.json"))
        assertTrue(original.recipes.all { it.contentOrigin == "PARSED" && it.editedAt == null })
        val edited = original.copy(recipes = original.recipes.map { it.copy(contentOrigin = "EDITED", editedAt = 42L) })
        assertEquals(edited, decodeOrFail(BackupJson.encode(edited)))
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
