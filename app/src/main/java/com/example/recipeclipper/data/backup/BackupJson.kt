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
 *                     "lastViewedAt", "checkedIngredients", "notes" }],
 *   "lists":       [{ "id", "name", "isFavorites", "isBuiltIn", "sortOrder", "createdAt" }],
 *   "memberships": [{ "recipeId", "listId", "addedAt" }] }
 * ```
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
                notes = r.string(o, "notes")
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

        return Backup(exportedAt, recipes, lists, memberships)
    }

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

    /** `put(key, null)` removes the key in org.json; an explicit JSON null keeps the shape. */
    private fun String?.orNull(): Any = this ?: JSONObject.NULL
}
