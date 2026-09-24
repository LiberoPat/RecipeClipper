import Foundation

/// The recipe-side SQL, ported from Android's RecipeDao. Synchronous: callers run it inside
/// `AppDatabase.read` / `write`, which puts it on the database queue (and, for writes, in a
/// transaction — so `upsert`'s find/insert-or-update/cull is atomic).
struct RecipeDao {
    let db: SQLiteConnection

    /// Shared by every summary query. "Saved" is derived: a recipe is saved when at least one
    /// list contains it. There is deliberately no isSaved column.
    private static let summaryColumns = """
        id, title, imageUrl, totalTime, lastViewedAt,
        EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isSaved
        """

    func get(_ id: Int64) throws -> RecipeRecord? {
        try db.queryOne("SELECT \(RecipeRecord.columns) FROM recipes WHERE id = ?", id, map: RecipeRecord.init(row:))
    }

    func findByUrl(_ url: String) throws -> RecipeRecord? {
        try db.queryOne("SELECT \(RecipeRecord.columns) FROM recipes WHERE sourceUrl = ?", url, map: RecipeRecord.init(row:))
    }

    /// An `id` of 0 lets SQLite assign one (Room's autoGenerate); a non-zero id is honoured,
    /// which is what `restore` relies on.
    @discardableResult
    func insert(_ r: RecipeRecord) throws -> Int64 {
        try db.run(
            """
            INSERT INTO recipes (\(RecipeRecord.columns))
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            r.id == 0 ? nil : r.id, r.sourceUrl, r.title, r.imageUrl,
            JSONColumns.encode(r.ingredients), JSONColumns.encode(r.instructions),
            r.prepTime, r.cookTime, r.totalTime, r.servings, r.sourceType, r.lastViewedAt,
            JSONColumns.encode(r.checkedIngredients), r.notes, r.uid, r.language, r.cookState, r.servingsTarget
        )
        return db.lastInsertRowId
    }

    func update(_ r: RecipeRecord) throws {
        try db.run(
            """
            UPDATE recipes SET sourceUrl = ?, title = ?, imageUrl = ?, ingredients = ?,
                instructions = ?, prepTime = ?, cookTime = ?, totalTime = ?, servings = ?,
                sourceType = ?, lastViewedAt = ?, checkedIngredients = ?, notes = ?, language = ?,
                cookState = ?, servingsTarget = ?
            WHERE id = ?
            """,
            r.sourceUrl, r.title, r.imageUrl,
            JSONColumns.encode(r.ingredients), JSONColumns.encode(r.instructions),
            r.prepTime, r.cookTime, r.totalTime, r.servings, r.sourceType, r.lastViewedAt,
            JSONColumns.encode(r.checkedIngredients), r.notes, r.language, r.cookState, r.servingsTarget, r.id
        )
    }

    func touch(_ id: Int64, now: Int64) throws {
        try db.run("UPDATE recipes SET lastViewedAt = ? WHERE id = ?", now, id)
    }

    func setChecked(_ id: Int64, checked: Set<Int>) throws {
        try db.run("UPDATE recipes SET checkedIngredients = ? WHERE id = ?", JSONColumns.encode(checked), id)
    }

    /// The user's note; nil clears it.
    func setNotes(_ id: Int64, notes: String?) throws {
        try db.run("UPDATE recipes SET notes = ? WHERE id = ?", notes, id)
    }

    /// Cook progress as `CookStateJSON`; nil clears it.
    func setCookState(_ id: Int64, cookState: String?) throws {
        try db.run("UPDATE recipes SET cookState = ? WHERE id = ?", cookState, id)
    }

    /// The chosen servings; nil means the recipe's own yield.
    func setServingsTarget(_ id: Int64, target: Int?) throws {
        try db.run("UPDATE recipes SET servingsTarget = ? WHERE id = ?", target, id)
    }

    /// Every recipe with saved cook progress, for finding its running timers.
    func cookStates() throws -> [CookStateRecord] {
        try db.query("SELECT id, title, cookState FROM recipes WHERE cookState IS NOT NULL") {
            CookStateRecord(id: $0.int64(0), title: $0.string(1), cookState: $0.string(2))
        }
    }

    /// Hard delete. The cross-ref rows go with it by cascade.
    func delete(_ id: Int64) throws {
        try db.run("DELETE FROM recipes WHERE id = ?", id)
    }

    /// Read before `delete` so undo has something to restore.
    func crossRefsFor(_ recipeId: Int64) throws -> [ListMembership] {
        try db.query(
            "SELECT recipeId, listId, addedAt FROM recipe_list_cross_ref WHERE recipeId = ?",
            recipeId, map: ListMembership.init(row:)
        )
    }

    /// Undoes `delete`: re-inserts the row with its original id, then its memberships, so they
    /// still point at the right row. Call inside a write (one transaction).
    ///
    /// A membership whose list was deleted in the meantime (possible with two scenes open on
    /// iPad) is skipped rather than inserted: its foreign key would fail and roll back the
    /// whole restore, so pressing Undo would lose the recipe for good over a list that no
    /// longer exists.
    func restore(_ recipe: RecipeRecord, crossRefs: [ListMembership]) throws {
        try insert(recipe)
        for ref in crossRefs {
            try db.run(
                """
                INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt)
                SELECT ?1, ?2, ?3 WHERE EXISTS (SELECT 1 FROM lists WHERE id = ?2)
                """,
                ref.recipeId, ref.listId, ref.addedAt
            )
        }
    }

    /// Titles or ingredients containing `query`, case-insensitively; everything when `query`
    /// is empty — one code path, not two queries picked in Swift.
    ///
    /// `instr()`, never `LIKE`: LIKE treats `%` and `_` as wildcards, so "100%" would
    /// misbehave. It matches the ingredients column as stored (parsed, not scaled/converted).
    func history(query: String) throws -> [RecipeSummaryRecord] {
        try db.query(
            """
            SELECT \(Self.summaryColumns)
            FROM recipes
            WHERE ?1 = ''
               OR instr(lower(title), lower(?1)) > 0
               OR instr(lower(ingredients), lower(?1)) > 0
            ORDER BY lastViewedAt DESC, id DESC
            """,
            query, map: RecipeSummaryRecord.init(row:)
        )
    }

    func recent(limit: Int) throws -> [RecipeSummaryRecord] {
        try db.query(
            """
            SELECT \(Self.summaryColumns)
            FROM recipes
            ORDER BY lastViewedAt DESC, id DESC
            LIMIT ?
            """,
            limit, map: RecipeSummaryRecord.init(row:)
        )
    }

    /// Deletes recipes that are in no list, oldest view first, keeping the `keep` most recently
    /// viewed of them. A recipe in any list is never touched.
    func cullHistory(keep: Int) throws {
        try db.run(
            """
            DELETE FROM recipes WHERE id IN (
                SELECT id FROM recipes
                WHERE id NOT IN (SELECT recipeId FROM recipe_list_cross_ref)
                ORDER BY lastViewedAt DESC, id DESC
                LIMIT -1 OFFSET ?
            )
            """,
            keep
        )
    }

    /// Saves a freshly parsed recipe and returns its id. A link seen before is updated in
    /// place, keeping its id, its uid (`update` never writes it), its list membership, its
    /// note, its chosen servings, its ticked ingredients only if the ingredients are unchanged,
    /// and its cook progress only if the steps are unchanged (both hold indexes). The history
    /// cap is enforced in the same transaction. Call
    /// inside a write.
    func upsert(_ fresh: RecipeRecord, historyLimit: Int) throws -> Int64 {
        let id: Int64
        if let existing = try findByUrl(fresh.sourceUrl) {
            var updated = fresh
            updated.id = existing.id
            // Ticks are indexes; if the ingredients changed they'd point at different lines.
            updated.checkedIngredients = existing.ingredients == fresh.ingredients
                ? existing.checkedIngredients
                : []
            // The note is the user's, not the source's: a fresh parse never carries one.
            updated.notes = existing.notes
            // Step indexes, like ticks, only mean the same steps if the steps are unchanged.
            updated.cookState = existing.instructions == fresh.instructions ? existing.cookState : nil
            // The chosen servings are the user's and don't depend on the wording of the steps.
            updated.servingsTarget = existing.servingsTarget
            try update(updated)
            id = existing.id
        } else {
            var inserted = fresh
            inserted.id = 0
            id = try insert(inserted)
        }
        try cullHistory(keep: historyLimit)
        return id
    }
}
