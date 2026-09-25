import Foundation

/// The grocery list's SQL (#50), ported from Android's GroceryDao. Synchronous; run inside
/// `AppDatabase.read` / `write` (a write is one transaction).
struct GroceryDao {
    let db: SQLiteConnection

    func items(listId: Int64 = GroceryItemRecord.defaultList) throws -> [GroceryItemRecord] {
        try db.query(
            "SELECT \(GroceryItemRecord.columns) FROM grocery_items WHERE listId = ? ORDER BY sortOrder ASC, id ASC",
            listId, map: GroceryItemRecord.init(row:)
        )
    }

    func items(ids: [Int64]) throws -> [GroceryItemRecord] {
        try ids.compactMap { id in
            try db.queryOne(
                "SELECT \(GroceryItemRecord.columns) FROM grocery_items WHERE id = ?", id,
                map: GroceryItemRecord.init(row:)
            )
        }
    }

    func checkedItems(listId: Int64 = GroceryItemRecord.defaultList) throws -> [GroceryItemRecord] {
        try db.query(
            "SELECT \(GroceryItemRecord.columns) FROM grocery_items WHERE listId = ? AND checked = 1 ORDER BY sortOrder ASC, id ASC",
            listId, map: GroceryItemRecord.init(row:)
        )
    }

    /// Adds `items` at the end of their list, in the order given, whatever their sortOrder said.
    /// Call inside a write.
    func add(_ items: [GroceryItemRecord]) throws {
        guard let first = items.first else { return }
        let start = try db.queryOne(
            "SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM grocery_items WHERE listId = ?", first.listId
        ) { $0.int(0) } ?? 0
        for (index, item) in items.enumerated() {
            var row = item
            row.id = 0
            row.sortOrder = start + index
            try insert(row)
        }
    }

    /// An `id` of 0 lets SQLite assign one; a non-zero id is honoured (what undo relies on).
    /// A recipe that went meanwhile leaves the item without a source rather than failing.
    func insert(_ e: GroceryItemRecord) throws {
        try db.run(
            """
            INSERT INTO grocery_items (\(GroceryItemRecord.columns))
            VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7,
                    CASE WHEN EXISTS (SELECT 1 FROM recipes WHERE id = ?8) THEN ?8 END, ?9, ?10, ?11)
            """,
            e.id == 0 ? nil : e.id, e.listId, e.text, e.language, e.aisle, e.checked, e.sortOrder,
            e.recipeId, e.plannedDay, e.updatedAt, e.uid
        )
    }

    /// Undoes a delete: the same rows back, ids, uids and places included.
    func restore(_ items: [GroceryItemRecord]) throws {
        for item in items { try insert(item) }
    }

    func setChecked(_ ids: [Int64], checked: Bool, now: Int64) throws {
        for id in ids {
            try db.run("UPDATE grocery_items SET checked = ?, updatedAt = ? WHERE id = ?", checked, now, id)
        }
    }

    func setAisle(_ ids: [Int64], aisle: String, now: Int64) throws {
        for id in ids {
            try db.run("UPDATE grocery_items SET aisle = ?, updatedAt = ? WHERE id = ?", aisle, now, id)
        }
    }

    func delete(_ ids: [Int64]) throws {
        for id in ids { try db.run("DELETE FROM grocery_items WHERE id = ?", id) }
    }

    /// Every recipe planned from day `start` to `end` inclusive, in plan order (as the Week
    /// shows it). Notes have no ingredients, so they are left out.
    func plannedIngredients(start: Int64, end: Int64) throws -> [PlannedIngredients] {
        try db.query(
            """
            SELECT e.id, e.day, e.servings, r.id, r.title, r.ingredients, r.servings, r.language
            FROM meal_plan_entries e
            JOIN meal_types t ON t.id = e.mealTypeId
            JOIN recipes r ON r.id = e.recipeId
            WHERE e.day BETWEEN ? AND ?
            ORDER BY e.day ASC, t.sortOrder ASC, t.id ASC, e.sortOrder ASC, e.id ASC
            """,
            start, end
        ) { row in
            PlannedIngredients(
                entryId: row.int64(0),
                day: row.int64(1),
                servings: row.isNull(2) ? nil : row.int(2),
                recipeId: row.int64(3),
                title: row.string(4),
                ingredients: JSONColumns.decodeStrings(row.string(5)),
                yield: row.optionalString(6),
                language: row.optionalString(7)
            )
        }
    }
}
