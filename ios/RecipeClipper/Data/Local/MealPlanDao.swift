import Foundation

/// The meal plan's SQL (#49), ported from Android's MealPlanDao. Synchronous; run inside
/// `AppDatabase.read` / `write` (a write is one transaction, which `deleteType` relies on).
struct MealPlanDao {
    let db: SQLiteConnection

    func mealTypes() throws -> [MealTypeRecord] {
        try db.query(
            "SELECT \(MealTypeRecord.columns) FROM meal_types ORDER BY sortOrder ASC, id ASC",
            map: MealTypeRecord.init(row:)
        )
    }

    /// Days `start` to `end` inclusive, by day, then meal type order, then the order meals were
    /// added to their slot. A note has no recipe, so the recipe is a LEFT JOIN.
    func days(start: Int64, end: Int64) throws -> [PlannedMeal] {
        try db.query(
            """
            SELECT e.id, e.day, e.mealTypeId, e.recipeId, e.servings, e.note, r.title, r.imageUrl, e.uid
            FROM meal_plan_entries e
            JOIN meal_types t ON t.id = e.mealTypeId
            LEFT JOIN recipes r ON r.id = e.recipeId
            WHERE e.day BETWEEN ? AND ?
            ORDER BY e.day ASC, t.sortOrder ASC, t.id ASC, e.sortOrder ASC, e.id ASC
            """,
            start, end
        ) { row in
            PlannedMeal(
                id: row.int64(0),
                day: row.int64(1),
                mealTypeId: row.int64(2),
                recipeId: row.isNull(3) ? nil : row.int64(3),
                title: row.optionalString(6),
                imageUrl: row.optionalString(7),
                servings: row.isNull(4) ? nil : row.int(4),
                note: row.optionalString(5),
                uid: row.string(8)
            )
        }
    }

    func entry(_ id: Int64) throws -> MealPlanEntryRecord? {
        try db.queryOne(
            "SELECT \(MealPlanEntryRecord.columns) FROM meal_plan_entries WHERE id = ?",
            id, map: MealPlanEntryRecord.init(row:)
        )
    }

    private func nextEntryOrder(day: Int64, mealTypeId: Int64) throws -> Int {
        try db.queryOne(
            "SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM meal_plan_entries WHERE day = ? AND mealTypeId = ?",
            day, mealTypeId
        ) { $0.int(0) } ?? 0
    }

    /// Adds `entry` at the end of its day and meal type, whatever its sortOrder said.
    @discardableResult
    func add(_ entry: MealPlanEntryRecord) throws -> Int64 {
        var row = entry
        row.id = 0
        row.sortOrder = try nextEntryOrder(day: entry.day, mealTypeId: entry.mealTypeId)
        return try insert(row)
    }

    /// An `id` of 0 lets SQLite assign one; a non-zero id is honoured (what undo relies on).
    @discardableResult
    func insert(_ e: MealPlanEntryRecord) throws -> Int64 {
        try db.run(
            """
            INSERT INTO meal_plan_entries (\(MealPlanEntryRecord.columns))
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            e.id == 0 ? nil : e.id, e.day, e.mealTypeId, e.recipeId, e.servings, e.note,
            e.sortOrder, e.updatedAt, e.uid
        )
        return db.lastInsertRowId
    }

    /// Undoes a delete: the same row back. Skipped if its recipe or meal type went meanwhile,
    /// rather than failing the whole write on a foreign key.
    func restore(_ e: MealPlanEntryRecord) throws {
        try db.run(
            """
            INSERT INTO meal_plan_entries (\(MealPlanEntryRecord.columns))
            SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9
            WHERE EXISTS (SELECT 1 FROM meal_types WHERE id = ?3)
              AND (?4 IS NULL OR EXISTS (SELECT 1 FROM recipes WHERE id = ?4))
            """,
            e.id, e.day, e.mealTypeId, e.recipeId, e.servings, e.note, e.sortOrder, e.updatedAt, e.uid
        )
    }

    /// Moves a meal to the end of another day or meal type.
    func move(_ id: Int64, day: Int64, mealTypeId: Int64, now: Int64) throws {
        guard let current = try entry(id) else { return }
        if current.day == day && current.mealTypeId == mealTypeId { return }
        let order = try nextEntryOrder(day: day, mealTypeId: mealTypeId)
        try db.run(
            "UPDATE meal_plan_entries SET day = ?, mealTypeId = ?, sortOrder = ?, updatedAt = ? WHERE id = ?",
            day, mealTypeId, order, now, id
        )
    }

    func deleteEntry(_ id: Int64) throws {
        try db.run("DELETE FROM meal_plan_entries WHERE id = ?", id)
    }

    // MARK: - Meal types

    /// A user's own meal type, last in the order.
    @discardableResult
    func addType(name: String, now: Int64) throws -> Int64 {
        try db.run(
            """
            INSERT INTO meal_types (name, builtInKey, sortOrder, updatedAt, uid)
            VALUES (?, NULL, (SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM meal_types), ?, ?)
            """,
            name, now, newUid()
        )
        return db.lastInsertRowId
    }

    /// Any type can be renamed, a seeded one included: its key, not its name, identifies it.
    func renameType(_ id: Int64, name: String, now: Int64) throws {
        try db.run("UPDATE meal_types SET name = ?, updatedAt = ? WHERE id = ?", name, now, id)
    }

    /// Numbers `orderedIds` 0, 1, 2… in the order given. Call inside a write.
    func reorderTypes(_ orderedIds: [Int64], now: Int64) throws {
        for (index, id) in orderedIds.enumerated() {
            try db.run("UPDATE meal_types SET sortOrder = ?, updatedAt = ? WHERE id = ?", index, now, id)
        }
    }

    /// Deletes a user's meal type. Its meals are never deleted with it: they move to Dinner
    /// first. The guard is `builtInKey IS NULL`, in the SQL, so a seeded type (and its meals)
    /// is left alone. Call inside a write, so both happen or neither.
    func deleteType(_ id: Int64, now: Int64) throws {
        try db.run(
            """
            UPDATE meal_plan_entries
            SET mealTypeId = (SELECT id FROM meal_types WHERE builtInKey = '\(MealType.dinner)'),
                updatedAt = ?1
            WHERE mealTypeId = ?2
              AND EXISTS (SELECT 1 FROM meal_types WHERE id = ?2 AND builtInKey IS NULL)
            """,
            now, id
        )
        try db.run("DELETE FROM meal_types WHERE id = ? AND builtInKey IS NULL", id)
    }
}
