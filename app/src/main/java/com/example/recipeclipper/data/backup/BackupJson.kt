package com.example.recipeclipper.data.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The export file's JSON, both ways. Pure: text in, data out (org.json is an Android framework
 * class; the JVM tests use `org.json:json`).
 *
 * ```
 * { "format": "recipe-clipper-backup", "formatVersion": 1, "exportedAt": <epoch ms>,
 *   "recipes":     [{ "id", "sourceUrl", "sourceType", "title", "imageUrl", "ingredients",
 *                     "instructions", "prepTime", "cookTime", "totalTime", "servings",
 *                     "lastViewedAt", "checkedIngredients", "notes", "language",
 *                     "contentOrigin", "editedAt" }],
 *   "lists":       [{ "id", "name", "isFavorites", "isBuiltIn", "sortOrder", "createdAt" }],
 *   "memberships": [{ "recipeId", "listId", "addedAt" }],
 *   "pantry":      [{ "id", "name", "quantity", "language", "aisle", "inStock", "alwaysHave",
 *                     "purchasedDay", "expiresDay", "updatedAt" }],
 *   "groceries":   [{ "id", "text", "language", "aisle", "checked", "recipeId", "plannedDay",
 *                     "updatedAt" }],
 *   "mealTypes":   [{ "id", "name", "builtInKey", "sortOrder", "updatedAt" }],
 *   "mealPlan":    [{ "id", "day", "mealTypeId", "recipeId", "servings", "note", "sortOrder",
 *                     "updatedAt" }],
 *   "menus":       [{ "id", "name", "updatedAt" }],
 *   "menuEntries": [{ "id", "menuId", "dayOffset", "mealTypeId", "recipeId", "servings", "note",
 *                     "sortOrder", "updatedAt" }],
 *   "cookedPhotos": [{ "id", "recipeId", "day", "note", "createdAt", "updatedAt", "file" }] }
 * ```
 *
 * `cookedPhotos` (#116) is written only when there are some, so an export without photos is the
 * same file as before; their pictures sit beside the JSON in a zip (`BackupArchive`), at `file`
 * (`photos/<name>.jpg`, nothing else). A photo whose `recipeId` names no recipe in the file is
 * left out: a photo belongs to its recipe.
 *
 * `pantry` (#51), `groceries` (#50), `mealTypes` and `mealPlan` (#49) came later without a
 * version bump: an older reader ignores them. A grocery's `recipeId` naming no recipe in the
 * file reads as none (the recipe is where it came from, not what it is); so does a planned
 * meal's, and its `mealTypeId` naming no meal type in the file reads as none (Dinner).
 * `menus` and `menuEntries` (#52) came the same way; a menu meal reads like a planned one, and
 * one whose `menuId` names no menu in the file is malformed.
 *
 * Reading is strict about what it needs and lenient about the rest, so the format can grow:
 * unknown keys and sections are ignored, a missing section is empty, and a missing optional
 * field is null (or 0, false, empty). The version is checked before anything else is read, so a
 * newer file is refused as [BackupError.NewerVersion] rather than misread as [BackupError.Malformed].
 */
object BackupJson {

    fun encode(backup: Backup): String {
        val root = JSONObject()
        root.put("format", Backup.FORMAT)
        root.put("formatVersion", Backup.FORMAT_VERSION)
        root.put("exportedAt", backup.exportedAt)
        root.put("recipes", JSONArray().apply { backup.recipes.forEach { put(it.toJson()) } })
        root.put("lists", JSONArray().apply { backup.lists.forEach { put(it.toJson()) } })
        root.put("memberships", JSONArray().apply { backup.memberships.forEach { put(it.toJson()) } })
        root.put("pantry", JSONArray().apply { backup.pantry.forEach { put(it.toJson()) } })
        root.put("groceries", JSONArray().apply { backup.groceries.forEach { put(it.toJson()) } })
        root.put("mealTypes", JSONArray().apply { backup.mealTypes.forEach { put(it.toJson()) } })
        root.put("mealPlan", JSONArray().apply { backup.mealPlan.forEach { put(it.toJson()) } })
        root.put("menus", JSONArray().apply { backup.menus.forEach { put(it.toJson()) } })
        root.put("menuEntries", JSONArray().apply { backup.menuEntries.forEach { put(it.toJson()) } })
        if (backup.cookedPhotos.isNotEmpty()) {
            root.put("cookedPhotos", JSONArray().apply { backup.cookedPhotos.forEach { put(it.toJson()) } })
        }
        return root.toString(2)
    }

    fun decode(text: String): BackupResult<Backup> {
        val root = try {
            JSONTokener(text).nextValue() as? JSONObject
        } catch (e: JSONException) {
            null
        } catch (e: StackOverflowError) {
            // Deeply nested JSON overflows org.json's recursive parser. A picked file is
            // untrusted input, so this is "not an export", not a crash.
            null
        } ?: return BackupResult.Failure(BackupError.NotABackup)

        if (root.opt("format") != Backup.FORMAT) return BackupResult.Failure(BackupError.NotABackup)

        return try {
            val version = Reader("").int(root, "formatVersion")
                ?: throw MalformedException("formatVersion")
            when {
                version < 1 -> throw MalformedException("formatVersion")
                version > Backup.FORMAT_VERSION ->
                    return BackupResult.Failure(BackupError.NewerVersion(version))
            }
            BackupResult.Success(readBackup(root))
        } catch (e: MalformedException) {
            BackupResult.Failure(BackupError.Malformed(e.path))
        }
    }

    private fun readBackup(root: JSONObject): Backup {
        val top = Reader("")
        val exportedAt = top.long(root, "exportedAt") ?: 0L

        val recipes = top.objects(root, "recipes").map { (path, o) ->
            val r = Reader(path)
            BackupRecipe(
                id = r.requiredId(o, "id"),
                sourceUrl = r.string(o, "sourceUrl")?.takeIf { it.isNotBlank() }
                    ?: throw MalformedException("$path.sourceUrl"),
                sourceType = r.string(o, "sourceType") ?: "BLOG",
                title = r.string(o, "title") ?: throw MalformedException("$path.title"),
                imageUrl = r.string(o, "imageUrl"),
                ingredients = r.strings(o, "ingredients"),
                instructions = r.strings(o, "instructions"),
                prepTime = r.string(o, "prepTime"),
                cookTime = r.string(o, "cookTime"),
                totalTime = r.string(o, "totalTime"),
                servings = r.string(o, "servings"),
                lastViewedAt = r.long(o, "lastViewedAt") ?: 0L,
                checkedIngredients = r.ints(o, "checkedIngredients").toSet(),
                notes = r.string(o, "notes"),
                language = r.string(o, "language"),
                contentOrigin = r.string(o, "contentOrigin")?.takeIf { it.isNotBlank() } ?: "PARSED",
                editedAt = r.long(o, "editedAt")
            )
        }
        requireUniqueIds(recipes.map { it.id }, "recipes")

        val lists = top.objects(root, "lists").map { (path, o) ->
            val r = Reader(path)
            BackupList(
                id = r.requiredId(o, "id"),
                name = r.string(o, "name")?.takeIf { it.isNotBlank() }
                    ?: throw MalformedException("$path.name"),
                isFavorites = r.bool(o, "isFavorites") ?: false,
                isBuiltIn = r.bool(o, "isBuiltIn") ?: false,
                sortOrder = r.int(o, "sortOrder") ?: 0,
                createdAt = r.long(o, "createdAt") ?: 0L
            )
        }
        requireUniqueIds(lists.map { it.id }, "lists")

        val recipeIds = recipes.mapTo(HashSet()) { it.id }
        val listIds = lists.mapTo(HashSet()) { it.id }
        val memberships = top.objects(root, "memberships").map { (path, o) ->
            val r = Reader(path)
            val membership = BackupMembership(
                recipeId = r.requiredId(o, "recipeId"),
                listId = r.requiredId(o, "listId"),
                addedAt = r.long(o, "addedAt") ?: 0L
            )
            if (membership.recipeId !in recipeIds) throw MalformedException("$path.recipeId")
            if (membership.listId !in listIds) throw MalformedException("$path.listId")
            membership
        }

        val pantry = top.objects(root, "pantry").map { (path, o) ->
            val r = Reader(path)
            BackupPantryItem(
                id = r.requiredId(o, "id"),
                name = r.string(o, "name")?.takeIf { it.isNotBlank() } ?: throw MalformedException("$path.name"),
                quantity = r.string(o, "quantity"),
                language = r.string(o, "language"),
                aisle = r.string(o, "aisle") ?: "other",
                inStock = r.bool(o, "inStock") ?: true,
                alwaysHave = r.bool(o, "alwaysHave") ?: false,
                purchasedDay = r.long(o, "purchasedDay"),
                expiresDay = r.long(o, "expiresDay"),
                updatedAt = r.long(o, "updatedAt") ?: 0L
            )
        }
        requireUniqueIds(pantry.map { it.id }, "pantry")

        val groceries = top.objects(root, "groceries").map { (path, o) ->
            val r = Reader(path)
            BackupGroceryItem(
                id = r.requiredId(o, "id"),
                text = r.string(o, "text")?.takeIf { it.isNotBlank() } ?: throw MalformedException("$path.text"),
                language = r.string(o, "language"),
                aisle = r.string(o, "aisle") ?: "other",
                checked = r.bool(o, "checked") ?: false,
                recipeId = r.string(o, "recipeId")?.takeIf { it in recipeIds },
                plannedDay = r.long(o, "plannedDay"),
                updatedAt = r.long(o, "updatedAt") ?: 0L
            )
        }
        requireUniqueIds(groceries.map { it.id }, "groceries")

        val mealTypes = top.objects(root, "mealTypes").map { (path, o) ->
            val r = Reader(path)
            BackupMealType(
                id = r.requiredId(o, "id"),
                name = r.string(o, "name")?.takeIf { it.isNotBlank() } ?: throw MalformedException("$path.name"),
                builtInKey = r.string(o, "builtInKey")?.takeIf { it.isNotBlank() },
                sortOrder = r.int(o, "sortOrder") ?: 0,
                updatedAt = r.long(o, "updatedAt") ?: 0L
            )
        }
        requireUniqueIds(mealTypes.map { it.id }, "mealTypes")

        val mealTypeIds = mealTypes.mapTo(HashSet()) { it.id }
        val mealPlan = top.objects(root, "mealPlan").map { (path, o) ->
            val r = Reader(path)
            BackupPlanEntry(
                id = r.requiredId(o, "id"),
                day = r.long(o, "day") ?: throw MalformedException("$path.day"),
                mealTypeId = r.string(o, "mealTypeId")?.takeIf { it in mealTypeIds },
                recipeId = r.string(o, "recipeId")?.takeIf { it in recipeIds },
                servings = r.int(o, "servings"),
                note = r.string(o, "note")?.takeIf { it.isNotBlank() },
                sortOrder = r.int(o, "sortOrder") ?: 0,
                updatedAt = r.long(o, "updatedAt") ?: 0L
            )
        }
        requireUniqueIds(mealPlan.map { it.id }, "mealPlan")

        val menus = top.objects(root, "menus").map { (path, o) ->
            val r = Reader(path)
            BackupMenu(
                id = r.requiredId(o, "id"),
                name = r.string(o, "name")?.takeIf { it.isNotBlank() } ?: throw MalformedException("$path.name"),
                updatedAt = r.long(o, "updatedAt") ?: 0L
            )
        }
        requireUniqueIds(menus.map { it.id }, "menus")

        val menuIds = menus.mapTo(HashSet()) { it.id }
        val menuEntries = top.objects(root, "menuEntries").map { (path, o) ->
            val r = Reader(path)
            BackupMenuEntry(
                id = r.requiredId(o, "id"),
                menuId = r.string(o, "menuId")?.takeIf { it in menuIds } ?: throw MalformedException("$path.menuId"),
                dayOffset = r.int(o, "dayOffset")?.takeIf { it in 0..6 } ?: throw MalformedException("$path.dayOffset"),
                mealTypeId = r.string(o, "mealTypeId")?.takeIf { it in mealTypeIds },
                recipeId = r.string(o, "recipeId")?.takeIf { it in recipeIds },
                servings = r.int(o, "servings"),
                note = r.string(o, "note")?.takeIf { it.isNotBlank() },
                sortOrder = r.int(o, "sortOrder") ?: 0,
                updatedAt = r.long(o, "updatedAt") ?: 0L
            )
        }
        requireUniqueIds(menuEntries.map { it.id }, "menuEntries")

        val cookedPhotos = top.objects(root, "cookedPhotos").mapNotNull { (path, o) ->
            val r = Reader(path)
            val id = r.requiredId(o, "id")
            val file = r.string(o, "file")?.takeIf { PHOTO_FILE.matches(it) } ?: throw MalformedException("$path.file")
            val day = r.long(o, "day") ?: throw MalformedException("$path.day")
            val recipeId = r.string(o, "recipeId")?.takeIf { it in recipeIds } ?: return@mapNotNull null
            BackupCookedPhoto(
                id = id,
                recipeId = recipeId,
                day = day,
                note = r.string(o, "note")?.takeIf { it.isNotBlank() },
                createdAt = r.long(o, "createdAt") ?: 0L,
                updatedAt = r.long(o, "updatedAt") ?: 0L,
                file = file
            )
        }
        requireUniqueIds(cookedPhotos.map { it.id }, "cookedPhotos")

        return Backup(
            exportedAt, recipes, lists, memberships, pantry, groceries, mealTypes, mealPlan, menus, menuEntries, cookedPhotos
        )
    }

    /** A picture's path in the zip: under `photos/`, one plain name, never `..` or a folder. */
    val PHOTO_FILE = Regex("photos/[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")

    private fun requireUniqueIds(ids: List<String>, section: String) {
        val seen = HashSet<String>()
        ids.forEachIndexed { i, id -> if (!seen.add(id)) throw MalformedException("$section[$i].id") }
    }

    private class MalformedException(val path: String) : Exception(path)

    /**
     * Typed reads with the path of the field in every failure ("recipes[2].title"). A JSON
     * `null` and a missing key are both "absent"; a value of the wrong type is malformed.
     */
    private class Reader(private val path: String) {
        private fun at(key: String) = if (path.isEmpty()) key else "$path.$key"

        private fun raw(o: JSONObject, key: String): Any? =
            o.opt(key).takeUnless { it == null || it == JSONObject.NULL }

        fun string(o: JSONObject, key: String): String? {
            val v = raw(o, key) ?: return null
            return v as? String ?: throw MalformedException(at(key))
        }

        fun requiredId(o: JSONObject, key: String): String =
            string(o, key)?.takeIf { it.isNotBlank() } ?: throw MalformedException(at(key))

        fun bool(o: JSONObject, key: String): Boolean? {
            val v = raw(o, key) ?: return null
            return v as? Boolean ?: throw MalformedException(at(key))
        }

        fun long(o: JSONObject, key: String): Long? {
            val v = raw(o, key) ?: return null
            return wholeNumber(v) ?: throw MalformedException(at(key))
        }

        fun int(o: JSONObject, key: String): Int? {
            val v = long(o, key) ?: return null
            if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw MalformedException(at(key))
            return v.toInt()
        }

        fun strings(o: JSONObject, key: String): List<String> {
            val array = array(o, key) ?: return emptyList()
            return List(array.length()) { i ->
                array.opt(i) as? String ?: throw MalformedException("${at(key)}[$i]")
            }
        }

        fun ints(o: JSONObject, key: String): List<Int> {
            val array = array(o, key) ?: return emptyList()
            return List(array.length()) { i ->
                val n = wholeNumber(array.opt(i))
                if (n == null || n < Int.MIN_VALUE || n > Int.MAX_VALUE) {
                    throw MalformedException("${at(key)}[$i]")
                }
                n.toInt()
            }
        }

        /** Each object in the array at [key], with its path. */
        fun objects(o: JSONObject, key: String): List<Pair<String, JSONObject>> {
            val array = array(o, key) ?: return emptyList()
            return List(array.length()) { i ->
                val item = array.opt(i) as? JSONObject ?: throw MalformedException("${at(key)}[$i]")
                "${at(key)}[$i]" to item
            }
        }

        private fun array(o: JSONObject, key: String): JSONArray? {
            val v = raw(o, key) ?: return null
            return v as? JSONArray ?: throw MalformedException(at(key))
        }

        private fun wholeNumber(v: Any?): Long? = when (v) {
            is Int -> v.toLong()
            is Long -> v
            is Number -> v.toDouble().let { d ->
                if (d % 1.0 == 0.0 && d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble()) d.toLong() else null
            }
            else -> null
        }
    }

    private fun BackupRecipe.toJson() = JSONObject().apply {
        put("id", id)
        put("sourceUrl", sourceUrl)
        put("sourceType", sourceType)
        put("title", title)
        put("imageUrl", imageUrl.orNull())
        put("ingredients", JSONArray(ingredients))
        put("instructions", JSONArray(instructions))
        put("prepTime", prepTime.orNull())
        put("cookTime", cookTime.orNull())
        put("totalTime", totalTime.orNull())
        put("servings", servings.orNull())
        put("lastViewedAt", lastViewedAt)
        put("checkedIngredients", JSONArray(checkedIngredients.sorted()))
        put("notes", notes.orNull())
        put("language", language.orNull())
        put("contentOrigin", contentOrigin)
        put("editedAt", editedAt ?: JSONObject.NULL)
    }

    private fun BackupList.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("isFavorites", isFavorites)
        put("isBuiltIn", isBuiltIn)
        put("sortOrder", sortOrder)
        put("createdAt", createdAt)
    }

    private fun BackupMembership.toJson() = JSONObject().apply {
        put("recipeId", recipeId)
        put("listId", listId)
        put("addedAt", addedAt)
    }

    private fun BackupPantryItem.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("quantity", quantity.orNull())
        put("language", language.orNull())
        put("aisle", aisle)
        put("inStock", inStock)
        put("alwaysHave", alwaysHave)
        put("purchasedDay", purchasedDay ?: JSONObject.NULL)
        put("expiresDay", expiresDay ?: JSONObject.NULL)
        put("updatedAt", updatedAt)
    }

    private fun BackupGroceryItem.toJson() = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("language", language.orNull())
        put("aisle", aisle)
        put("checked", checked)
        put("recipeId", recipeId.orNull())
        put("plannedDay", plannedDay ?: JSONObject.NULL)
        put("updatedAt", updatedAt)
    }

    private fun BackupMealType.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("builtInKey", builtInKey.orNull())
        put("sortOrder", sortOrder)
        put("updatedAt", updatedAt)
    }

    private fun BackupPlanEntry.toJson() = JSONObject().apply {
        put("id", id)
        put("day", day)
        put("mealTypeId", mealTypeId.orNull())
        put("recipeId", recipeId.orNull())
        put("servings", servings ?: JSONObject.NULL)
        put("note", note.orNull())
        put("sortOrder", sortOrder)
        put("updatedAt", updatedAt)
    }

    private fun BackupMenu.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("updatedAt", updatedAt)
    }

    private fun BackupMenuEntry.toJson() = JSONObject().apply {
        put("id", id)
        put("menuId", menuId)
        put("dayOffset", dayOffset)
        put("mealTypeId", mealTypeId.orNull())
        put("recipeId", recipeId.orNull())
        put("servings", servings ?: JSONObject.NULL)
        put("note", note.orNull())
        put("sortOrder", sortOrder)
        put("updatedAt", updatedAt)
    }

    private fun BackupCookedPhoto.toJson() = JSONObject().apply {
        put("id", id)
        put("recipeId", recipeId)
        put("day", day)
        put("note", note.orNull())
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("file", file)
    }

    /** `put(key, null)` removes the key in org.json; an explicit JSON null keeps the shape. */
    private fun String?.orNull(): Any = this ?: JSONObject.NULL
}
