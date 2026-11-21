import Foundation

/// The list-membership SQL, ported from Android's ListDao. Synchronous; run inside
/// `AppDatabase.read` / `write`.
struct ListDao {
    let db: SQLiteConnection

    /// Passed as `recipeId` by callers that aren't asking about a recipe. No row has it.
    static let noRecipe: Int64 = -1

    /// Lists in the cook's own order (`sortOrder`, #381; seeded lists start first), each with its recipe count and whether it contains `recipeId`.
    /// One query rather than two: the Lists screen passes `noRecipe`, so `containsRecipe` is
    /// false for every row.
    func lists(recipeId: Int64) throws -> [ListRecord] {
        try db.query(
            """
            SELECT l.id, l.name, l.isBuiltIn, l.isFavorites,
                   (SELECT COUNT(*) FROM recipe_list_cross_ref c
                    WHERE c.listId = l.id) AS recipeCount,
                   EXISTS(SELECT 1 FROM recipe_list_cross_ref c2
                          WHERE c2.listId = l.id AND c2.recipeId = ?) AS containsRecipe
            FROM lists l
            ORDER BY l.sortOrder ASC, l.id ASC
            """,
            recipeId, map: ListRecord.init(row:)
        )
    }

    /// The recipes in one list, most recently added first. `isSaved` is a constant 1: being in
    /// this list is what "saved" means.
    func recipesIn(listId: Int64) throws -> [RecipeSummaryRecord] {
        try db.query(
            """
            SELECT r.id, r.title, \(RecipeImageSql.asR) AS imageUrl, r.totalTime, r.lastViewedAt, 1 AS isSaved,
                   r.contentOrigin = 'CLIPPED' AS isClipped, NULL AS lastCookedDay, r.sourceUrl,
                   r.contentOrigin = 'CHAT' AS isFromChat,
                   NULL AS prepTime, NULL AS cookTime, r.language, r.uid, NULL AS ingredients
            FROM recipes r
            JOIN recipe_list_cross_ref c ON c.recipeId = r.id
            WHERE c.listId = ?
            ORDER BY c.addedAt DESC, r.id DESC
            """,
            listId, map: RecipeSummaryRecord.init(row:)
        )
    }

    /// The most photos a list's cover shows: a 2×2 mosaic.
    static let coverPhotoLimit = 4

    /// The Lists screen's covers (#286): up to `coverPhotoLimit` photos per list, from the recipes
    /// in it that have one, most recently added first, grouped by list. A list with no photo
    /// has no rows. A correlated `LIMIT` rather than `ROW_NUMBER()`, as on Android, whose
    /// oldest SQLite has no window functions.
    func coverPhotos() throws -> [ListCoverPhotoRecord] {
        try db.query(
            """
            SELECT c.listId, \(RecipeImageSql.asR) AS imageUrl, r.uid, r.title, r.language, r.ingredients
            FROM recipe_list_cross_ref c
            JOIN recipes r ON r.id = c.recipeId
            WHERE (r.photoFile IS NOT NULL OR (r.imageUrl IS NOT NULL AND r.imageUrl != ''))
              AND r.id IN (
                  SELECT c2.recipeId FROM recipe_list_cross_ref c2
                  JOIN recipes r2 ON r2.id = c2.recipeId
                  WHERE c2.listId = c.listId
                    AND (r2.photoFile IS NOT NULL OR (r2.imageUrl IS NOT NULL AND r2.imageUrl != ''))
                  ORDER BY c2.addedAt DESC, r2.id DESC
                  LIMIT ?)
            ORDER BY c.listId, c.addedAt DESC, r.id DESC
            """,
            Int64(Self.coverPhotoLimit), map: ListCoverPhotoRecord.init(row:)
        )
    }

    /// The covers of lists with no photo (#403): up to `coverPhotoLimit` recipes per list, from
    /// the ones in it that have no photo, most recently added first, with what their designed
    /// images are drawn from. Used only for a list `coverPhotos` has no row for; a list with a
    /// photo shows photos alone. The same correlated `LIMIT` as above.
    func coverPlaceholders() throws -> [ListCoverPlaceholderRecord] {
        try db.query(
            """
            SELECT c.listId, r.uid, r.title, r.language, r.ingredients
            FROM recipe_list_cross_ref c
            JOIN recipes r ON r.id = c.recipeId
            WHERE (r.imageUrl IS NULL OR r.imageUrl = '')
              AND r.id IN (
                  SELECT c2.recipeId FROM recipe_list_cross_ref c2
                  JOIN recipes r2 ON r2.id = c2.recipeId
                  WHERE c2.listId = c.listId AND (r2.imageUrl IS NULL OR r2.imageUrl = '')
                  ORDER BY c2.addedAt DESC, r2.id DESC
                  LIMIT ?)
            ORDER BY c.listId, c.addedAt DESC, r.id DESC
            """,
            Int64(Self.coverPhotoLimit), map: ListCoverPlaceholderRecord.init(row:)
        )
    }

    /// Every saved recipe (in at least one list, once however many) with its ingredient lines,
    /// for "Use it up" (#305).
    func savedRecipes() throws -> [SavedRecipe] {
        try db.query(
            """
            SELECT r.id, r.title, r.language, r.ingredients
            FROM recipes r
            WHERE r.id IN (SELECT recipeId FROM recipe_list_cross_ref)
            ORDER BY r.id
            """,
            map: { row in
                SavedRecipe(
                    id: row.int64(0), title: row.string(1),
                    language: row.optionalString(2), lines: JSONColumns.decodeStrings(row.string(3))
                )
            }
        )
    }

    /// IGNORE, never REPLACE: re-adding a recipe already in the list must not rewrite
    /// `addedAt`, which is what orders the list. (A missing recipe or list still fails: OR
    /// IGNORE does not cover foreign-key violations.)
    func addToList(_ ref: ListMembership) throws {
        try db.run(
            "INSERT OR IGNORE INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (?, ?, ?)",
            ref.recipeId, ref.listId, ref.addedAt
        )
    }

    /// Unticking. The recipe row is deliberately untouched: out of its last list, a recipe is
    /// ordinary history again (and cullable), not deleted.
    func removeFromList(recipeId: Int64, listId: Int64) throws {
        try db.run("DELETE FROM recipe_list_cross_ref WHERE recipeId = ? AND listId = ?", recipeId, listId)
    }

    /// Ticking a list for several recipes (#377): insert-or-ignore each, so one already in keeps
    /// its `addedAt`. Call inside a write: all or none.
    func addAllToList(recipeIds: [Int64], listId: Int64, addedAt: Int64) throws {
        for id in recipeIds { try addToList(ListMembership(recipeId: id, listId: listId, addedAt: addedAt)) }
    }

    /// Unticking a list for several recipes (#377).
    func removeAllFromList(recipeIds: [Int64], listId: Int64) throws {
        for id in recipeIds { try removeFromList(recipeId: id, listId: listId) }
    }

    /// For each list, how many of `recipeIds` it holds (#377); a list with none has no row.
    func membershipCounts(recipeIds: [Int64]) throws -> [Int64: Int] {
        guard !recipeIds.isEmpty else { return [:] }
        let marks = Array(repeating: "?", count: recipeIds.count).joined(separator: ", ")
        let rows: [(Int64, Int)] = try db.query(
            "SELECT listId, COUNT(*) FROM recipe_list_cross_ref WHERE recipeId IN (\(marks)) GROUP BY listId",
            arguments: recipeIds.map { $0 as SQLiteBindable? }, map: { ($0.int64(0), Int($0.int64(1))) }
        )
        return Dictionary(uniqueKeysWithValues: rows)
    }

    /// Creates a user list and, unless `recipeId` is `noRecipe`, puts that recipe straight in.
    /// Call inside a write, so a list never exists without the recipe that prompted it.
    func create(name: String, recipeId: Int64, now: Int64) throws -> Int64 {
        try db.run(
            """
            INSERT INTO lists (name, isBuiltIn, isFavorites, sortOrder, createdAt, uid)
            VALUES (?, 0, 0, (SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM lists), ?, ?)
            """,
            name, now, newUid()
        )
        let id = db.lastInsertRowId
        if recipeId != Self.noRecipe {
            try addToList(ListMembership(recipeId: recipeId, listId: id, addedAt: now))
        }
        return id
    }

    /// The cook's new order (#381): `orderedIds` first, in that order, then any list not named
    /// (one created while a drag was in flight) in the order it already had. Ids that are not
    /// lists are ignored, and every list ends up with a distinct position 0, 1, 2... Call inside
    /// a write, so the Lists screen sees the move once.
    func reorder(_ orderedIds: [Int64]) throws {
        let current = try db.query("SELECT id FROM lists ORDER BY sortOrder ASC, id ASC") { $0.int64(0) }
        let known = Set(current)
        var seen = Set<Int64>()
        let named = orderedIds.filter { known.contains($0) && seen.insert($0).inserted }
        let rest = current.filter { !seen.contains($0) }
        for (position, id) in (named + rest).enumerated() {
            try db.run("UPDATE lists SET sortOrder = ? WHERE id = ?", position, id)
        }
    }

    /// Built-ins are renameable: the restriction is on deleting them, not naming them.
    func rename(id: Int64, name: String) throws {
        try db.run("UPDATE lists SET name = ? WHERE id = ?", name, id)
    }

    /// Deletes a list and, by cascade, its membership rows — never its recipes. The guard is
    /// `isFavorites = 0`, not `isBuiltIn = 0`: Favorites is the only list that cannot be
    /// deleted. It lives in the SQL so no code path can get around it.
    func delete(id: Int64) throws {
        try db.run("DELETE FROM lists WHERE id = ? AND isFavorites = 0", id)
    }
}
