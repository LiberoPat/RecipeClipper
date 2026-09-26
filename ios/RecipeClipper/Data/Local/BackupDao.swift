import Foundation

/// Everything an export holds, read in one go so it is one consistent moment.
struct BackupSnapshot {
    var recipes: [RecipeRecord]
    var lists: [ListRow]
    var memberships: [ListMembership]
    var pantry: [PantryItemRecord] = []
    var groceries: [GroceryItemRecord] = []
    var mealTypes: [MealTypeRecord] = []
    var mealPlan: [MealPlanEntryRecord] = []
    var menus: [MenuRecord] = []
    var menuEntries: [MenuEntryRecord] = []
    var cookedPhotos: [CookedPhotoRecord] = []

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
/// lists, memberships, pantry and grocery items, meal types and planned meals) and fills empty
/// notes; it never deletes and runs no history cull (see
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
        let pantry = try PantryDao(db: db).items()
        let groceries = try db.query(
            "SELECT \(GroceryItemRecord.columns) FROM grocery_items ORDER BY listId ASC, sortOrder ASC, id ASC",
            map: GroceryItemRecord.init(row:)
        )
        let mealTypes = try MealPlanDao(db: db).mealTypes()
        let mealPlan = try db.query(
            "SELECT \(MealPlanEntryRecord.columns) FROM meal_plan_entries ORDER BY day ASC, mealTypeId ASC, sortOrder ASC, id ASC",
            map: MealPlanEntryRecord.init(row:)
        )
        let menus = try db.query("SELECT \(MenuRecord.columns) FROM menus ORDER BY id ASC", map: MenuRecord.init(row:))
        let menuEntries = try db.query(
            "SELECT \(MenuEntryRecord.columns) FROM menu_entries ORDER BY menuId ASC, dayOffset ASC, mealTypeId ASC, sortOrder ASC, id ASC",
            map: MenuEntryRecord.init(row:)
        )
        return BackupSnapshot(
            recipes: recipes, lists: lists, memberships: memberships, pantry: pantry, groceries: groceries,
            mealTypes: mealTypes, mealPlan: mealPlan, menus: menus, menuEntries: menuEntries,
            cookedPhotos: try CookedPhotoDao(db: db).all()
        )
    }

    func existingMealTypes() throws -> [ExistingMealType] {
        try db.query("SELECT id, uid, name, builtInKey FROM meal_types ORDER BY sortOrder ASC, id ASC") { row in
            ExistingMealType(id: row.int64(0), uid: row.string(1), name: row.string(2), builtInKey: row.optionalString(3))
        }
    }

    func maxMealTypeOrder() throws -> Int {
        try db.queryOne("SELECT COALESCE(MAX(sortOrder), -1) FROM meal_types") { $0.int(0) } ?? -1
    }

    func existingPlanUids() throws -> Set<String> {
        Set(try db.query("SELECT uid FROM meal_plan_entries") { $0.string(0) })
    }

    func existingMenuUids() throws -> Set<String> {
        Set(try db.query("SELECT uid FROM menus") { $0.string(0) })
    }

    func existingMenuEntryUids() throws -> Set<String> {
        Set(try db.query("SELECT uid FROM menu_entries") { $0.string(0) })
    }

    func existingPantry() throws -> [ExistingPantryItem] {
        try db.query("SELECT uid, name, language FROM pantry_items") { row in
            ExistingPantryItem(uid: row.string(0), name: row.string(1), language: row.optionalString(2))
        }
    }

    func existingGroceryUids() throws -> Set<String> {
        Set(try db.query("SELECT uid FROM grocery_items") { $0.string(0) })
    }

    func existingRecipes() throws -> [ExistingRecipe] {
        try db.query(
            """
            SELECT id, uid, sourceUrl,
                   (notes IS NOT NULL AND trim(notes) != '') AS hasNotes,
                   (EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id)
                    OR contentOrigin = 'MANUAL'
                    OR EXISTS(SELECT 1 FROM cooked_photos p WHERE p.recipeId = recipes.id)) AS isListed
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

    /// `storedPhotos` (#116): the package's pictures, by path in the zip, already copied into
    /// the photo store, to their stored names.
    func importBackup(
        _ backup: Backup, limit: LibraryLimit, today: Int64?, storedPhotos: [String: String] = [:], now: Int64 = 0,
        newUid: () -> String
    ) throws -> ImportSummary {
        // Unlimited (#107) leaves nothing out: every recipe comes in.
        let historyLimit: Int = switch limit {
        case .history(let keep): keep
        case .free(let max): max
        case .unlimited: Int.max
        }
        var countsEveryRecipe = false
        if case .free = limit { countsEveryRecipe = true }
        let plan = BackupMerger.plan(
            backup,
            existingRecipes: try existingRecipes(),
            existingLists: try existingLists(),
            maxSortOrder: try maxSortOrder(),
            historyLimit: historyLimit,
            newUid: newUid,
            existingPantry: try existingPantry(),
            existingGroceryUids: try existingGroceryUids(),
            existingMealTypes: try existingMealTypes(),
            maxMealTypeSortOrder: try maxMealTypeOrder(),
            existingPlanUids: try existingPlanUids(),
            today: today,
            existingMenuUids: try existingMenuUids(),
            existingMenuEntryUids: try existingMenuEntryUids(),
            countsEveryRecipe: countsEveryRecipe,
            existingCookedPhotoUids: Set(try db.query("SELECT uid FROM cooked_photos") { $0.string(0) }),
            availablePhotoFiles: Set(storedPhotos.keys)
        )

        let recipes = RecipeDao(db: db)
        var newRecipeIds: [String: Int64] = [:]
        for r in plan.newRecipes {
            newRecipeIds[r.id] = try recipes.insert(RecipeRecord(
                sourceUrl: r.sourceUrl, title: r.title, imageUrl: r.imageUrl,
                ingredients: r.ingredients, instructions: r.instructions,
                prepTime: r.prepTime, cookTime: r.cookTime, totalTime: r.totalTime, servings: r.servings,
                sourceType: r.sourceType, lastViewedAt: r.lastViewedAt,
                checkedIngredients: r.checkedIngredients, notes: r.notes, uid: r.id, language: r.language,
                contentOrigin: r.contentOrigin, editedAt: r.editedAt
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

        let pantry = PantryDao(db: db)
        for p in plan.newPantry {
            try pantry.insert(PantryItemRecord(
                name: p.name, quantity: p.quantity, language: p.language, aisle: p.aisle, inStock: p.inStock,
                alwaysHave: p.alwaysHave, purchasedDay: p.purchasedDay, expiresDay: p.expiresDay,
                updatedAt: p.updatedAt, uid: p.id
            ))
        }

        let groceries = GroceryDao(db: db)
        var order = try db.queryOne(
            "SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM grocery_items WHERE listId = ?", GroceryItemRecord.defaultList
        ) { $0.int(0) } ?? 0
        for g in plan.newGroceries {
            try groceries.insert(GroceryItemRecord(
                text: g.item.text, language: g.item.language, aisle: g.item.aisle, checked: g.item.checked,
                sortOrder: order, recipeId: g.recipe.map { rowId($0, newRecipeIds) }, plannedDay: g.item.plannedDay,
                updatedAt: g.item.updatedAt, uid: g.item.id
            ))
            order += 1
        }

        var newTypeIds: [String: Int64] = [:]
        for t in plan.newMealTypes {
            try db.run(
                "INSERT INTO meal_types (name, builtInKey, sortOrder, updatedAt, uid) VALUES (?, NULL, ?, ?, ?)",
                t.name, t.sortOrder, t.updatedAt, t.uid
            )
            newTypeIds[t.uid] = db.lastInsertRowId
        }
        let mealPlan = MealPlanDao(db: db)
        for p in plan.newPlanEntries {
            // `add` puts it at the end of its day and meal type.
            try mealPlan.add(MealPlanEntryRecord(
                day: p.entry.day, mealTypeId: rowId(p.mealType, newTypeIds),
                recipeId: p.recipe.map { rowId($0, newRecipeIds) }, servings: p.entry.servings, note: p.entry.note,
                sortOrder: 0, updatedAt: p.entry.updatedAt, uid: p.entry.id
            ))
        }
        let menus = MenuDao(db: db)
        for m in plan.newMenus {
            let menuId = try menus.insertMenu(MenuRecord(name: m.menu.name, updatedAt: m.menu.updatedAt, uid: m.menu.id))
            for e in m.entries {
                try menus.insertEntry(MenuEntryRecord(
                    menuId: menuId, dayOffset: e.entry.dayOffset, mealTypeId: rowId(e.mealType, newTypeIds),
                    recipeId: e.recipe.map { rowId($0, newRecipeIds) }, servings: e.entry.servings, note: e.entry.note,
                    sortOrder: e.entry.sortOrder, updatedAt: e.entry.updatedAt, uid: e.entry.id
                ))
            }
        }
        let photos = CookedPhotoDao(db: db)
        for p in plan.newCookedPhotos {
            guard let fileName = storedPhotos[p.photo.file] else { continue }
            try photos.insert(CookedPhotoRecord(
                recipeId: rowId(p.recipe, newRecipeIds), fileName: fileName, day: p.photo.day, note: p.photo.note,
                createdAt: p.photo.createdAt > 0 ? p.photo.createdAt : now,
                updatedAt: p.photo.updatedAt > 0 ? p.photo.updatedAt : now, uid: p.photo.id
            ))
        }
        return plan.summary
    }
}
