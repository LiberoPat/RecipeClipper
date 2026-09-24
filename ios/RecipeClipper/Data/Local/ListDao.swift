import Foundation

/// The list-membership SQL, ported from Android's ListDao. Synchronous; run inside
/// `AppDatabase.read` / `write`.
struct ListDao {
    let db: SQLiteConnection

    /// Passed as `recipeId` by callers that aren't asking about a recipe. No row has it.
    static let noRecipe: Int64 = -1

    /// Lists, built-ins first, each with its recipe count and whether it contains `recipeId`.
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
            ORDER BY l.isBuiltIn DESC, l.sortOrder ASC, l.id ASC
            """,
            recipeId, map: ListRecord.init(row:)
        )
    }

    /// The recipes in one list, most recently added first. `isSaved` is a constant 1: being in
    /// this list is what "saved" means.
    func recipesIn(listId: Int64) throws -> [RecipeSummaryRecord] {
        try db.query(
            """
            SELECT r.id, r.title, r.imageUrl, r.totalTime, r.lastViewedAt, 1 AS isSaved
            FROM recipes r
            JOIN recipe_list_cross_ref c ON c.recipeId = r.id
            WHERE c.listId = ?
            ORDER BY c.addedAt DESC, r.id DESC
            """,
            listId, map: RecipeSummaryRecord.init(row:)
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

    /// Creates a user list and, unless `recipeId` is `noRecipe`, puts that recipe straight in.
    /// Call inside a write, so a list never exists without the recipe that prompted it.
    func create(name: String, recipeId: Int64, now: Int64) throws -> Int64 {
        try db.run(
            """
            INSERT INTO lists (name, isBuiltIn, isFavorites, sortOrder, createdAt)
            VALUES (?, 0, 0, (SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM lists), ?)
            """,
            name, now
        )
        let id = db.lastInsertRowId
        if recipeId != Self.noRecipe {
            try addToList(ListMembership(recipeId: recipeId, listId: id, addedAt: now))
        }
        return id
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
