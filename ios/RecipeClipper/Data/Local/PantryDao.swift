import Foundation

/// The pantry's SQL (#51), ported from Android's PantryDao. Synchronous; run inside
/// `AppDatabase.read` / `write` (a write is one transaction).
struct PantryDao {
    let db: SQLiteConnection

    func items() throws -> [PantryItemRecord] {
        try db.query("SELECT \(PantryItemRecord.columns) FROM pantry_items ORDER BY id ASC", map: PantryItemRecord.init(row:))
    }

    func item(_ id: Int64) throws -> PantryItemRecord? {
        try db.queryOne("SELECT \(PantryItemRecord.columns) FROM pantry_items WHERE id = ?", id, map: PantryItemRecord.init(row:))
    }

    /// An `id` of 0 lets SQLite assign one; a non-zero id is honoured (what undo relies on).
    @discardableResult
    func insert(_ e: PantryItemRecord) throws -> Int64 {
        try db.run(
            "INSERT INTO pantry_items (\(PantryItemRecord.columns)) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            e.id == 0 ? nil : e.id, e.name, e.quantity, e.language, e.aisle, e.inStock, e.alwaysHave,
            e.purchasedDay, e.expiresDay, e.updatedAt, e.uid
        )
        return db.lastInsertRowId
    }

    func setInStock(_ ids: [Int64], inStock: Bool, now: Int64) throws {
        for id in ids {
            try db.run("UPDATE pantry_items SET inStock = ?, updatedAt = ? WHERE id = ?", inStock, now, id)
        }
    }

    /// Back in stock, bought on `day`.
    func restock(_ ids: [Int64], day: Int64, now: Int64) throws {
        for id in ids {
            try db.run("UPDATE pantry_items SET inStock = 1, purchasedDay = ?, updatedAt = ? WHERE id = ?", day, now, id)
        }
    }

    func edit(_ id: Int64, name: String, quantity: String?, alwaysHave: Bool, expiresDay: Int64?, now: Int64) throws {
        try db.run(
            "UPDATE pantry_items SET name = ?, quantity = ?, alwaysHave = ?, expiresDay = ?, updatedAt = ? WHERE id = ?",
            name, quantity, alwaysHave, expiresDay, now, id
        )
    }

    func delete(_ id: Int64) throws {
        try db.run("DELETE FROM pantry_items WHERE id = ?", id)
    }

    /// Undoes a delete, a restock or a stock change: the rows exactly as they were.
    func put(_ items: [PantryItemRecord]) throws {
        for item in items {
            try db.run("DELETE FROM pantry_items WHERE id = ?", item.id)
            try insert(item)
        }
    }
}
