import Foundation

/// The recipe-side SQL, ported from Android's RecipeDao. Synchronous: callers run it inside
/// `AppDatabase.read` / `write`, which puts it on the database queue (and, for writes, in a
/// transaction — so `upsert`'s find/insert-or-update/cull is atomic).
struct RecipeDao {
    let db: SQLiteConnection

    /// Shared by every summary query. "Saved" is derived: a recipe is saved when at least one
    /// list contains it. There is deliberately no isSaved column.
    private static let summaryHead = """
        id, title, \(RecipeImageSql.plain) AS imageUrl, totalTime, lastViewedAt,
        EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isSaved,
        contentOrigin = 'CLIPPED' AS isClipped,
        (SELECT MAX(p.day) FROM cooked_photos p WHERE p.recipeId = recipes.id) AS lastCookedDay,
        sourceUrl,
        contentOrigin = 'CHAT' AS isFromChat,
        prepTime, cookTime, language, uid
        """

    /// The summary columns, with no ingredient lines (column 12 is NULL): a row's image is the
    /// title's letter (#403), which needs only the uid and language.
    private static let summaryColumns = summaryHead + ", NULL AS ingredients"

    /// The same with the ingredient lines, for Home's card (#403): its image is the first names.
    private static let recentColumns = summaryHead + ", ingredients"

    func get(_ id: Int64) throws -> RecipeRecord? {
        try db.queryOne("SELECT \(RecipeRecord.columns) FROM recipes WHERE id = ?", id, map: RecipeRecord.init(row:))
    }

    func findByUrl(_ url: String) throws -> RecipeRecord? {
        try db.queryOne("SELECT \(RecipeRecord.columns) FROM recipes WHERE sourceUrl = ?", url, map: RecipeRecord.init(row:))
    }

    /// The rows with `ids`, in one statement per 500 (SQLite caps a statement's parameters).
    func byIds(_ ids: [Int64]) throws -> [RecipeRecord] {
        try stride(from: 0, to: ids.count, by: 500).flatMap { start -> [RecipeRecord] in
            let chunk = Array(ids[start..<min(start + 500, ids.count)])
            let marks = chunk.map { _ in "?" }.joined(separator: ", ")
            return try db.query("SELECT \(RecipeRecord.columns) FROM recipes WHERE id IN (\(marks))",
                                arguments: chunk.map { $0 as SQLiteBindable? }, map: RecipeRecord.init(row:))
        }
    }

    /// An `id` of 0 lets SQLite assign one (Room's autoGenerate); a non-zero id is honoured,
    /// which is what `restore` relies on.
    @discardableResult
    func insert(_ r: RecipeRecord) throws -> Int64 {
        try db.run(
            """
            INSERT INTO recipes (\(RecipeRecord.columns))
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            r.id == 0 ? nil : r.id, r.sourceUrl, r.title, r.imageUrl,
            JSONColumns.encode(r.ingredients), JSONColumns.encode(r.instructions),
            r.prepTime, r.cookTime, r.totalTime, r.servings, r.sourceType, r.lastViewedAt,
            JSONColumns.encode(r.checkedIngredients), r.notes, r.uid, r.language, r.cookState, r.servingsTarget,
            r.contentOrigin, r.editedAt, r.basedOnUid, r.photoFile, JSONColumns.encode(r.tips)
        )
        return db.lastInsertRowId
    }

    func update(_ r: RecipeRecord) throws {
        try db.run(
            """
            UPDATE recipes SET sourceUrl = ?, title = ?, imageUrl = ?, ingredients = ?,
                instructions = ?, prepTime = ?, cookTime = ?, totalTime = ?, servings = ?,
                sourceType = ?, lastViewedAt = ?, checkedIngredients = ?, notes = ?, language = ?,
                cookState = ?, servingsTarget = ?, contentOrigin = ?, editedAt = ?, tips = ?
            WHERE id = ?
            """,
            r.sourceUrl, r.title, r.imageUrl,
            JSONColumns.encode(r.ingredients), JSONColumns.encode(r.instructions),
            r.prepTime, r.cookTime, r.totalTime, r.servings, r.sourceType, r.lastViewedAt,
            JSONColumns.encode(r.checkedIngredients), r.notes, r.language, r.cookState, r.servingsTarget,
            r.contentOrigin, r.editedAt, JSONColumns.encode(r.tips), r.id
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

    /// The three times alone (#179: the tour's sample, saved before they were formatted).
    func setTimes(_ id: Int64, prep: String?, cook: String?, total: String?) throws {
        try db.run("UPDATE recipes SET prepTime = ?, cookTime = ?, totalTime = ? WHERE id = ?", prep, cook, total, id)
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

    /// `delete` for a recipe the cook deleted (#354): the versions of it keep their place, but
    /// their link clears, so none points at a recipe that is gone. Call inside a write.
    func deleteWithLinks(_ id: Int64) throws {
        try delete(id)
        try clearOrphanedLinks()
    }

    /// Clears every version link whose original is no longer here (#354): after any delete.
    func clearOrphanedLinks() throws {
        try db.run(
            """
            UPDATE recipes SET basedOnUid = NULL
            WHERE basedOnUid IS NOT NULL AND basedOnUid NOT IN (SELECT uid FROM recipes)
            """
        )
    }

    /// The versions of the recipe with `uid`, read before its delete so an undo can link them again.
    func versionIdsOf(_ uid: String) throws -> [Int64] {
        try db.query("SELECT id FROM recipes WHERE basedOnUid = ?", uid) { $0.int64(0) }
    }

    /// Links the recipes `ids` to the original `uid` again: an undo of its delete.
    func relink(_ ids: [Int64], uid: String) throws {
        for id in ids { try db.run("UPDATE recipes SET basedOnUid = ? WHERE id = ?", uid, id) }
    }

    /// The recipe `id` is a version of (#354); nil for none.
    func original(of id: Int64) throws -> RecipeLink? {
        try db.queryOne(
            """
            SELECT o.id, o.title FROM recipes v
            JOIN recipes o ON o.uid = v.basedOnUid
            WHERE v.id = ?
            """,
            id
        ) { RecipeLink(id: $0.int64(0), title: $0.string(1)) }
    }

    /// The versions of recipe `id` (#354), newest first.
    func versions(of id: Int64) throws -> [RecipeLink] {
        try db.query(
            """
            SELECT v.id, v.title FROM recipes v
            JOIN recipes o ON o.uid = v.basedOnUid
            WHERE o.id = ?
            ORDER BY v.id DESC
            """,
            id
        ) { RecipeLink(id: $0.int64(0), title: $0.string(1)) }
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
        menuEntries: [MenuEntryRecord] = [], cookedPhotos: [CookedPhotoRecord] = [], versionIds: [Int64] = []
    ) throws {
        try insert(recipe)
        // Its versions link to it again (#354); its own link only if its original is still here.
        try relink(versionIds, uid: recipe.uid)
        try clearOrphanedLinks()
        // Its own photos (#116): their files were kept for exactly this.
        let photos = CookedPhotoDao(db: db)
        for photo in cookedPhotos { try photos.insert(photo) }
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

    /// What a delete took of one recipe, for `restoreAll`.
    struct DeletedRows {
        let recipe: RecipeRecord
        let crossRefs: [ListMembership]
        let planEntries: [MealPlanEntryRecord]
        let menuEntries: [MenuEntryRecord]
        let cookedPhotos: [CookedPhotoRecord]
        /// The rows that were versions of it (#354), linked again by an undo.
        var versionIds: [Int64] = []
    }

    /// Undoes a burst of deletes, all or nothing, under `limit` (#345). A `.capped` library (the
    /// free tier, locked) restores none of them when they would take it past its limit, the same
    /// count as `upsert`'s (the tour's sample counts toward nothing): without this an Undo would let
    /// delete, add one, Undo grow the library by one each time. Anything else restores. Returns
    /// whether it restored. Call inside a write (one transaction).
    func restoreAll(_ rows: [DeletedRows], limit: LibraryLimit) throws -> Bool {
        if case .capped(let max) = limit {
            let adding = rows.filter { $0.recipe.sourceUrl != SampleRecipe.sourceUrl }.count
            if try count() + adding > max { return false }
        }
        for row in rows {
            try restore(
                row.recipe, crossRefs: row.crossRefs, planEntries: row.planEntries,
                menuEntries: row.menuEntries, cookedPhotos: row.cookedPhotos, versionIds: row.versionIds
            )
        }
        return true
    }

    /// Titles or ingredients containing `query`, case-insensitively; everything when `query`
    /// is empty — one code path, not two queries picked in Swift.
    ///
    /// `instr()`, never `LIKE`: LIKE treats `%` and `_` as wildcards, so "100%" would
    /// misbehave. It matches the ingredients column as stored (parsed, not scaled/converted).
    ///
    /// `listed` is the Recipes screen's filter (#345): `listedAny`, `listedYes` (in at least one
    /// list) or `listedNo`. It is the same `EXISTS` as the `isSaved` column, so a row's bookmark
    /// and the filter can't disagree; the search still applies inside it.
    /// `cooked` = 1 keeps the recipes with a cooking marked (the user's own photos, or "Mark as
    /// cooked", #116, #173); `typed` = 1 those typed, scanned or read in by hand (`contentOrigin`
    /// MANUAL, not the tour's sample), `chat` = 1 those read from a chat's JSON (`contentOrigin`
    /// CHAT, #355): V4's views.
    func history(
        query: String, listed: Int = RecipeDao.listedAny, cooked: Int = 0, typed: Int = 0, chat: Int = 0
    ) throws -> [RecipeSummaryRecord] {
        try db.query(
            """
            SELECT \(Self.summaryColumns)
            FROM recipes
            WHERE (?1 = ''
               OR instr(lower(title), lower(?1)) > 0
               OR instr(lower(ingredients), lower(?1)) > 0)
              AND (?2 = -1
               OR EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) = ?2)
              AND (?3 = 0
               OR EXISTS(SELECT 1 FROM cooked_photos p WHERE p.recipeId = recipes.id))
              AND (?4 = 0
               OR (contentOrigin = 'MANUAL' AND sourceUrl != 'manual:sample'))
              AND (?5 = 0
               OR contentOrigin = 'CHAT')
            ORDER BY lastViewedAt DESC, id DESC
            """,
            query, listed, cooked, typed, chat, map: RecipeSummaryRecord.init(row:)
        )
    }

    /// `history`'s `listed` argument (#345): any recipe, one in a list, one in none.
    static let listedAny = -1
    static let listedYes = 1
    static let listedNo = 0

    /// How many recipes there are, in a list, cooked and typed in, for the filter's and the strip's counts (#345).
    func libraryCounts() throws -> RecipeCounts {
        try db.queryOne(
            """
            SELECT COUNT(*),
                   COALESCE(SUM(EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id)), 0),
                   COALESCE(SUM(EXISTS(SELECT 1 FROM cooked_photos p WHERE p.recipeId = recipes.id)), 0),
                   COALESCE(SUM(contentOrigin = 'MANUAL' AND sourceUrl != 'manual:sample'), 0),
                   COALESCE(SUM(contentOrigin = 'CHAT'), 0)
            FROM recipes
            """
        ) { RecipeCounts(all: $0.int(0), inList: $0.int(1), cooked: $0.int(2), typed: $0.int(3), chat: $0.int(4)) }
            ?? RecipeCounts(all: 0, inList: 0)
    }

    /// Every recipe's ingredient lines as stored, for "Uses what's in my pantry" (#378): read only
    /// while that chip is on.
    func recipeLines() throws -> [SavedRecipe] {
        try db.query(
            "SELECT id, title, language, ingredients FROM recipes ORDER BY id",
            map: { row in
                SavedRecipe(
                    id: row.int64(0), title: row.string(1),
                    language: row.optionalString(2), lines: JSONColumns.decodeStrings(row.string(3))
                )
            }
        )
    }

    func recent(limit: Int) throws -> [RecipeSummaryRecord] {
        try db.query(
            """
            SELECT \(Self.recentColumns)
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
    /// nor one typed in by hand (#102, origin MANUAL, or CHAT, #355): it has no link to bring it back, nor one
    /// with the user's own photos (#116).
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
                  AND contentOrigin NOT IN ('MANUAL', 'CHAT')
                  AND id NOT IN (SELECT recipeId FROM cooked_photos)
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
              AND contentOrigin NOT IN ('MANUAL', 'CHAT')
              AND id NOT IN (SELECT recipeId FROM cooked_photos)
            ORDER BY lastViewedAt ASC, id ASC
            LIMIT 1
            """,
            today
        ) { $0.int64(0) }
    }

    /// Every recipe, of every kind but the tour's sample (#107: the Recipes screen's count;
    /// #151): the sample never takes a free-tier place.
    func count() throws -> Int {
        try db.queryOne("SELECT COUNT(*) FROM recipes WHERE sourceUrl != ?1", SampleRecipe.sourceUrl) { $0.int(0) } ?? 0
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
    /// written and `notKept` is returned. A `.capped` one (#345) never removes anything: a new
    /// recipe at the limit is `notKept`. Updating a recipe already here never removes one, and is
    /// allowed at the limit (it is already counted). Call inside a write.
    ///
    /// A row that is the user's version (#29: `contentOrigin` neither PARSED nor EXTRACTED) keeps its content:
    /// the re-share only counts as a view. `replaceUsersVersion` is "Update from source", which
    /// does replace it, and makes it PARSED again (`fresh` is).
    func upsert(
        _ fresh: RecipeRecord, limit: LibraryLimit, replaceUsersVersion: Bool = false,
        today: Int64 = noPlanProtection
    ) throws -> Int64 {
        let id: Int64
        if let existing = try findByUrl(fresh.sourceUrl) {
            if !ContentOrigin.isSources(existing.contentOrigin) && !replaceUsersVersion {
                try touch(existing.id, now: fresh.lastViewedAt)
            } else {
                try update(keepingUserState(existing, fresh))
            }
            id = existing.id
        } else {
            // One cookbook (#345): at the limit a new recipe is shown, not kept; nothing is removed.
            if case .capped(let max) = limit, try count() >= max { return Self.notKept }
            if case .free(let max) = limit, try count() >= max {
                guard let oldest = try oldestCullable(today: today) else { return Self.notKept }
                try deleteWithLinks(oldest)
            }
            var inserted = fresh
            inserted.id = 0
            id = try insert(inserted)
        }
        if case .history(let keep) = limit {
            try cullHistory(keep: keep, today: today)
            try clearOrphanedLinks()
        }
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
}
