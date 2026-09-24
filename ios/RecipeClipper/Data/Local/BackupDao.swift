import Foundation

/// Everything an export holds, read in one go so it is one consistent moment.
struct BackupSnapshot {
    var recipes: [RecipeRecord]
    var lists: [ListRow]
    var memberships: [ListMembership]

    /// One row of `lists`, every column (ListRecord is the screen's shape, with counts).
    struct ListRow: Equatable {
        var id: Int64
        var uid: String
        var name: String
        var isBuiltIn: Bool
        var isFavorites: Bool
        var sortOrder: Int
        var createdAt: Int64
    }
}

/// Export and import (#26), Android's BackupDao. Synchronous: run `snapshot` inside
/// `AppDatabase.read` and `importBackup` inside `AppDatabase.write`, which makes the import one
/// transaction: if any write fails, nothing was imported. Import only ever inserts (recipes,
/// lists, memberships) and fills empty notes; it never deletes and runs no history cull (see
/// BackupMerger for how the cap is respected).
struct BackupDao {
    let db: SQLiteConnection

    func snapshot() throws -> BackupSnapshot {
        let recipes = try db.query(
            "SELECT \(RecipeRecord.columns) FROM recipes ORDER BY lastViewedAt DESC, id DESC",
            map: RecipeRecord.init(row:)
        )
        let lists = try db.query(
            """
            SELECT id, uid, name, isBuiltIn, isFavorites, sortOrder, createdAt FROM lists
            ORDER BY isBuiltIn DESC, sortOrder ASC, id ASC
            """
        ) { row in
            BackupSnapshot.ListRow(
                id: row.int64(0), uid: row.string(1), name: row.string(2), isBuiltIn: row.bool(3),
                isFavorites: row.bool(4), sortOrder: row.int(5), createdAt: row.int64(6)
            )
        }
        let memberships = try db.query(
            "SELECT recipeId, listId, addedAt FROM recipe_list_cross_ref ORDER BY listId ASC, addedAt ASC, recipeId ASC",
            map: ListMembership.init(row:)
        )
        return BackupSnapshot(recipes: recipes, lists: lists, memberships: memberships)
    }

    func existingRecipes() throws -> [ExistingRecipe] {
        try db.query(
            """
            SELECT id, uid, sourceUrl,
                   (notes IS NOT NULL AND trim(notes) != '') AS hasNotes,
                   EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isListed
            FROM recipes
            """
        ) { row in
            ExistingRecipe(
                id: row.int64(0), uid: row.string(1), sourceUrl: row.string(2),
                hasNotes: row.bool(3), isListed: row.bool(4)
            )
        }
    }

    func existingLists() throws -> [ExistingList] {
        try db.query("SELECT id, uid, name, isFavorites FROM lists ORDER BY isBuiltIn DESC, sortOrder ASC, id ASC") { row in
            ExistingList(id: row.int64(0), uid: row.string(1), name: row.string(2), isFavorites: row.bool(3))
        }
    }

    func maxSortOrder() throws -> Int {
        try db.queryOne("SELECT COALESCE(MAX(sortOrder), -1) FROM lists") { $0.int(0) } ?? -1
    }

    func importBackup(_ backup: Backup, historyLimit: Int, newUid: () -> String) throws -> ImportSummary {
        let plan = BackupMerger.plan(
            backup,
            existingRecipes: try existingRecipes(),
            existingLists: try existingLists(),
            maxSortOrder: try maxSortOrder(),
            historyLimit: historyLimit,
            newUid: newUid
        )

        let recipes = RecipeDao(db: db)
        var newRecipeIds: [String: Int64] = [:]
        for r in plan.newRecipes {
            newRecipeIds[r.id] = try recipes.insert(RecipeRecord(
                sourceUrl: r.sourceUrl, title: r.title, imageUrl: r.imageUrl,
                ingredients: r.ingredients, instructions: r.instructions,
                prepTime: r.prepTime, cookTime: r.cookTime, totalTime: r.totalTime, servings: r.servings,
                sourceType: r.sourceType, lastViewedAt: r.lastViewedAt,
                checkedIngredients: r.checkedIngredients, notes: r.notes, uid: r.id
            ))
        }
        for update in plan.noteUpdates {
            // Only ever fills an empty note; the plan never names a recipe that has one.
            try db.run(
                "UPDATE recipes SET notes = ? WHERE id = ? AND (notes IS NULL OR trim(notes) = '')",
                update.notes, update.recipeId
            )
        }

        var newListIds: [String: Int64] = [:]
        for l in plan.newLists {
            try db.run(
                """
                INSERT INTO lists (uid, name, isBuiltIn, isFavorites, sortOrder, createdAt)
                VALUES (?, ?, ?, 0, ?, ?)
                """,
                l.uid, l.name, l.isBuiltIn, l.sortOrder, l.createdAt
            )
            newListIds[l.uid] = db.lastInsertRowId
        }

        func rowId(_ target: MergeTarget, _ new: [String: Int64]) -> Int64 {
            switch target {
            case .existing(let id): return id
            case .new(let uid): return new[uid]!
            }
        }
        let lists = ListDao(db: db)
        for m in plan.memberships {
            // Insert-or-ignore: a membership already here keeps its addedAt.
            try lists.addToList(ListMembership(
                recipeId: rowId(m.recipe, newRecipeIds), listId: rowId(m.list, newListIds), addedAt: m.addedAt
            ))
        }
        return plan.summary
    }
}
