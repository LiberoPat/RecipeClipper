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
        EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isSaved,
        contentOrigin = 'CLIPPED' AS isClipped
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
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            r.id == 0 ? nil : r.id, r.sourceUrl, r.title, r.imageUrl,
            JSONColumns.encode(r.ingredients), JSONColumns.encode(r.instructions),
            r.prepTime, r.cookTime, r.totalTime, r.servings, r.sourceType, r.lastViewedAt,
            JSONColumns.encode(r.checkedIngredients), r.notes, r.uid, r.language, r.cookState, r.servingsTarget,
            r.contentOrigin, r.editedAt
        )
        return db.lastInsertRowId
    }

    func update(_ r: RecipeRecord) throws {
        try db.run(
            """
            UPDATE recipes SET sourceUrl = ?, title = ?, imageUrl = ?, ingredients = ?,
                instructions = ?, prepTime = ?, cookTime = ?, totalTime = ?, servings = ?,
                sourceType = ?, lastViewedAt = ?, checkedIngredients = ?, notes = ?, language = ?,
                cookState = ?, servingsTarget = ?, contentOrigin = ?, editedAt = ?
            WHERE id = ?
            """,
            r.sourceUrl, r.title, r.imageUrl,
            JSONColumns.encode(r.ingredients), JSONColumns.encode(r.instructions),
            r.prepTime, r.cookTime, r.totalTime, r.servings, r.sourceType, r.lastViewedAt,
            JSONColumns.encode(r.checkedIngredients), r.notes, r.language, r.cookState, r.servingsTarget,
            r.contentOrigin, r.editedAt, r.id
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

    /// Read before `delete`, like `crossRefsFor`: the recipe's planned meals cascade with it.
    func planEntriesFor(_ recipeId: Int64) throws -> [MealPlanEntryRecord] {
        try db.query(
            "SELECT \(MealPlanEntryRecord.columns) FROM meal_plan_entries WHERE recipeId = ?",
            recipeId, map: MealPlanEntryRecord.init(row:)
        )
    }

    /// Read before `delete`, like `planEntriesFor`: the recipe's meals in saved menus (#52).
    func menuEntriesFor(_ recipeId: Int64) throws -> [MenuEntryRecord] {
        try db.query(
            "SELECT \(MenuEntryRecord.columns) FROM menu_entries WHERE recipeId = ?",
            recipeId, map: MenuEntryRecord.init(row:)
        )
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
    func restore(
        _ recipe: RecipeRecord, crossRefs: [ListMembership], planEntries: [MealPlanEntryRecord] = [],
        menuEntries: [MenuEntryRecord] = []
    ) throws {
        try insert(recipe)
        // Its planned meals too (#49), each skipped if its meal type went meanwhile.
        let plan = MealPlanDao(db: db)
        for entry in planEntries { try plan.restore(entry) }
        // And its menu meals (#52), each skipped if its menu or meal type went meanwhile.
        for e in menuEntries {
            try db.run(
                """
                INSERT INTO menu_entries (\(MenuEntryRecord.columns))
                SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10
                WHERE EXISTS (SELECT 1 FROM meal_types WHERE id = ?4)
                  AND EXISTS (SELECT 1 FROM menus WHERE id = ?2)
                """,
                e.id, e.menuId, e.dayOffset, e.mealTypeId, e.recipeId, e.servings, e.note, e.sortOrder,
                e.updatedAt, e.uid
            )
        }
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

    /// The `today` that protects no planned recipe from the cull: no day is on or after it.
    /// The default for callers with no plan in mind; the repository passes today.
    static let noPlanProtection = Int64.max

    /// Deletes recipes that are in no list, oldest view first, keeping the `keep` most recently
    /// viewed of them. A recipe in any list is never touched, and neither is one planned for
    /// `today` or later (#49; an epoch day, see `PlanDays`): both are outside the cap. A recipe
    /// planned only for past days is ordinary history again. Nor is one in a saved menu (#52),
    /// nor one typed in by hand (#102, origin MANUAL): it has no link to bring it back.
    ///
    /// The plan subquery filters out NULL recipe ids (a note): `NOT IN` a set holding a NULL is
    /// never true, which would silently stop the cull altogether.
    func cullHistory(keep: Int, today: Int64 = noPlanProtection) throws {
        try db.run(
            """
            DELETE FROM recipes WHERE id IN (
                SELECT id FROM recipes
                WHERE id NOT IN (SELECT recipeId FROM recipe_list_cross_ref)
                  AND id NOT IN (SELECT recipeId FROM meal_plan_entries
                                 WHERE recipeId IS NOT NULL AND day >= ?2)
                  AND id NOT IN (SELECT recipeId FROM menu_entries WHERE recipeId IS NOT NULL)
                  AND contentOrigin != 'MANUAL'
                ORDER BY lastViewedAt DESC, id DESC
                LIMIT -1 OFFSET ?1
            )
            """,
            keep, today
        )
    }

    /// The free tier's one-for-one removal (#107): the oldest-viewed recipe `cullHistory` could
    /// delete (the same protections: keep this WHERE in step with it), or nil if every recipe is
    /// protected.
    func oldestCullable(today: Int64 = noPlanProtection) throws -> Int64? {
        try db.queryOne(
            """
            SELECT id FROM recipes
            WHERE id NOT IN (SELECT recipeId FROM recipe_list_cross_ref)
              AND id NOT IN (SELECT recipeId FROM meal_plan_entries
                             WHERE recipeId IS NOT NULL AND day >= ?1)
              AND id NOT IN (SELECT recipeId FROM menu_entries WHERE recipeId IS NOT NULL)
              AND contentOrigin != 'MANUAL'
            ORDER BY lastViewedAt ASC, id ASC
            LIMIT 1
            """,
            today
        ) { $0.int64(0) }
    }

    /// Every recipe, of every kind (#107: the Recipes screen's count).
    func count() throws -> Int {
        try db.queryOne("SELECT COUNT(*) FROM recipes") { $0.int(0) } ?? 0
    }

    /// `upsert`'s answer when a full free library had no room (#107): no row has id 0.
    static let notKept: Int64 = 0

    /// Saves a freshly parsed recipe and returns its id. A link seen before is updated in
    /// place, keeping its id, its uid (`update` never writes it), its list membership, its
    /// note, its chosen servings, its ticked ingredients only if the ingredients are unchanged,
    /// and its cook progress only if the steps are unchanged (both hold indexes). The `limit` is
    /// applied in the same transaction (see `LibraryLimit`): a `.history` cap is culled after the
    /// save; a `.free` one only when a new recipe joins a library already at or over it, by
    /// removing the oldest unprotected recipe first, one for one. If there is none, nothing is
    /// written and `notKept` is returned. Updating a recipe already here never removes one.
    /// Call inside a write.
    ///
    /// A row that is the user's version (#29: `contentOrigin` not PARSED) keeps its content:
    /// the re-share only counts as a view. `replaceUsersVersion` is "Update from source", which
    /// does replace it, and makes it PARSED again (`fresh` is).
    func upsert(
        _ fresh: RecipeRecord, limit: LibraryLimit, replaceUsersVersion: Bool = false,
        today: Int64 = noPlanProtection
    ) throws -> Int64 {
        let id: Int64
        if let existing = try findByUrl(fresh.sourceUrl) {
            if existing.contentOrigin != Self.originParsed && !replaceUsersVersion {
                try touch(existing.id, now: fresh.lastViewedAt)
            } else {
                try update(keepingUserState(existing, fresh))
            }
            id = existing.id
        } else {
            if case .free(let max) = limit, try count() >= max {
                guard let oldest = try oldestCullable(today: today) else { return Self.notKept }
                try delete(oldest)
            }
            var inserted = fresh
            inserted.id = 0
            id = try insert(inserted)
        }
        if case .history(let keep) = limit { try cullHistory(keep: keep, today: today) }
        return id
    }

    /// `upsert` under the old history cap: the app before #107, and most tests.
    func upsert(
        _ fresh: RecipeRecord, historyLimit: Int, replaceUsersVersion: Bool = false,
        today: Int64 = noPlanProtection
    ) throws -> Int64 {
        try upsert(fresh, limit: .history(keep: historyLimit), replaceUsersVersion: replaceUsersVersion, today: today)
    }

    /// Saves the user's edit of recipe `id` (#29): `edited`'s content, with `origin` and
    /// `editedAt` as given. Everything that is the user's rather than the content (id, uid,
    /// link, note, servings, list membership, last view) stays, and ticks and cook progress
    /// follow the same rule as a re-share. False if the recipe is gone. Call inside a write.
    func saveEdit(_ id: Int64, edited: RecipeRecord, origin: String, editedAt: Int64) throws -> Bool {
        guard let existing = try get(id) else { return false }
        var updated = keepingUserState(existing, edited)
        updated.sourceUrl = existing.sourceUrl
        updated.sourceType = existing.sourceType
        updated.language = existing.language
        updated.lastViewedAt = existing.lastViewedAt
        updated.contentOrigin = origin
        updated.editedAt = editedAt
        try update(updated)
        return true
    }

    /// `fresh`'s content under `existing`'s identity and user state.
    private func keepingUserState(_ existing: RecipeRecord, _ fresh: RecipeRecord) -> RecipeRecord {
        var updated = fresh
        updated.id = existing.id
        updated.uid = existing.uid
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
        return updated
    }

    /// `ContentOrigin.parsed`, as stored.
    static let originParsed = "PARSED"
}
