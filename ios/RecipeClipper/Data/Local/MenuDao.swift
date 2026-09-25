import Foundation

/// Reusable weekly menus (#52), ported from Android's MenuDao. Saving copies a week's meals into
/// a new menu; applying copies a menu's meals into a week, after what is already planned there.
/// Neither ever changes or removes a planned meal. Run inside `AppDatabase.write`, so each is
/// one transaction.
struct MenuDao {
    let db: SQLiteConnection

    /// Every menu by name, with how many meals it holds.
    func menus() throws -> [WeekMenu] {
        try db.query(
            """
            SELECT m.id, m.name, (SELECT COUNT(*) FROM menu_entries e WHERE e.menuId = m.id)
            FROM menus m
            ORDER BY m.name COLLATE NOCASE ASC, m.id ASC
            """
        ) { WeekMenu(id: $0.int64(0), name: $0.string(1), mealCount: $0.int(2)) }
    }

    func entries(menuId: Int64) throws -> [MenuEntryRecord] {
        try db.query(
            """
            SELECT \(MenuEntryRecord.columns) FROM menu_entries WHERE menuId = ?
            ORDER BY dayOffset ASC, mealTypeId ASC, sortOrder ASC, id ASC
            """,
            menuId, map: MenuEntryRecord.init(row:)
        )
    }

    /// Saves the seven days from `weekStart` as a new menu called `name` and returns its id. An
    /// empty week saves nothing and returns nil.
    func saveWeek(name: String, weekStart: Int64, now: Int64) throws -> Int64? {
        let meals = try db.query(
            """
            SELECT \(MealPlanEntryRecord.columns) FROM meal_plan_entries WHERE day BETWEEN ? AND ?
            ORDER BY day ASC, mealTypeId ASC, sortOrder ASC, id ASC
            """,
            weekStart, weekStart + 6, map: MealPlanEntryRecord.init(row:)
        )
        if meals.isEmpty { return nil }
        let menuId = try insertMenu(MenuRecord(name: name, updatedAt: now))
        for (index, meal) in meals.enumerated() {
            try insertEntry(MenuEntryRecord(
                menuId: menuId, dayOffset: Int(meal.day - weekStart), mealTypeId: meal.mealTypeId,
                recipeId: meal.recipeId, servings: meal.servings, note: meal.note, sortOrder: index, updatedAt: now
            ))
        }
        return menuId
    }

    /// Adds every meal of `menuId` to the week from `weekStart`, each at the end of its day and
    /// meal type. Returns how many it added.
    func apply(menuId: Int64, weekStart: Int64, now: Int64) throws -> Int {
        let meals = try entries(menuId: menuId)
        let plan = MealPlanDao(db: db)
        for meal in meals {
            try plan.add(MealPlanEntryRecord(
                day: weekStart + Int64(meal.dayOffset), mealTypeId: meal.mealTypeId, recipeId: meal.recipeId,
                servings: meal.servings, note: meal.note, sortOrder: 0, updatedAt: now
            ))
        }
        return meals.count
    }

    func rename(_ id: Int64, name: String, now: Int64) throws {
        try db.run("UPDATE menus SET name = ?, updatedAt = ? WHERE id = ?", name, now, id)
    }

    /// Deletes a menu and its meals (cascade). The plan and the recipes are untouched.
    func delete(_ id: Int64) throws {
        try db.run("DELETE FROM menus WHERE id = ?", id)
    }
}

extension MenuDao {
    /// An `id` of 0 lets SQLite assign one.
    @discardableResult
    func insertMenu(_ m: MenuRecord) throws -> Int64 {
        try db.run(
            "INSERT INTO menus (\(MenuRecord.columns)) VALUES (?, ?, ?, ?)",
            m.id == 0 ? nil : m.id, m.name, m.updatedAt, m.uid
        )
        return db.lastInsertRowId
    }

    /// An `id` of 0 lets SQLite assign one; a non-zero id is honoured (what undo relies on).
    @discardableResult
    func insertEntry(_ e: MenuEntryRecord) throws -> Int64 {
        try db.run(
            "INSERT INTO menu_entries (\(MenuEntryRecord.columns)) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            e.id == 0 ? nil : e.id, e.menuId, e.dayOffset, e.mealTypeId, e.recipeId, e.servings, e.note,
            e.sortOrder, e.updatedAt, e.uid
        )
        return db.lastInsertRowId
    }
}
