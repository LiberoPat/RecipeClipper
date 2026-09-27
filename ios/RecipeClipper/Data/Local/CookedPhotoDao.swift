import Foundation

/// One "I made this" row (#116; Android's CookedPhotoEntity): the user's photo of a recipe they
/// cooked, the `day` (an epoch day) and an optional short note. `fileName` names the JPEG in the
/// photo store, never a path. Deleted with its recipe (CASCADE); its file goes once that stands.
struct CookedPhotoRecord: Equatable {
    var id: Int64 = 0
    var recipeId: Int64
    var fileName: String
    var day: Int64
    var note: String?
    var createdAt: Int64
    var updatedAt: Int64
    var uid: String = newUid()

    static let columns = "id, recipeId, fileName, day, note, createdAt, updatedAt, uid"

    init(
        id: Int64 = 0, recipeId: Int64, fileName: String, day: Int64, note: String?, createdAt: Int64,
        updatedAt: Int64, uid: String = newUid()
    ) {
        self.id = id
        self.recipeId = recipeId
        self.fileName = fileName
        self.day = day
        self.note = note
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.uid = uid
    }

    init(row: SQLiteRow) {
        id = row.int64(0)
        recipeId = row.int64(1)
        fileName = row.string(2)
        day = row.int64(3)
        note = row.optionalString(4)
        createdAt = row.int64(5)
        updatedAt = row.int64(6)
        uid = row.string(7)
    }
}

/// The SQL of "I made this" (#116), ported from Android's CookedPhotoDao. Synchronous; run
/// inside `AppDatabase.read` / `write`.
struct CookedPhotoDao {
    let db: SQLiteConnection

    func photosFor(_ recipeId: Int64) throws -> [CookedPhotoRecord] {
        try db.query(
            """
            SELECT \(CookedPhotoRecord.columns) FROM cooked_photos WHERE recipeId = ?
            ORDER BY day DESC, createdAt DESC, id DESC
            """,
            recipeId, map: CookedPhotoRecord.init(row:)
        )
    }

    func get(_ id: Int64) throws -> CookedPhotoRecord? {
        try db.queryOne("SELECT \(CookedPhotoRecord.columns) FROM cooked_photos WHERE id = ?", id, map: CookedPhotoRecord.init(row:))
    }

    func all() throws -> [CookedPhotoRecord] {
        try db.query(
            "SELECT \(CookedPhotoRecord.columns) FROM cooked_photos ORDER BY recipeId ASC, day DESC, createdAt DESC, id DESC",
            map: CookedPhotoRecord.init(row:)
        )
    }

    /// Every file a row still names: what the orphan sweep must leave alone.
    func fileNames() throws -> Set<String> {
        Set(try db.query("SELECT fileName FROM cooked_photos") { $0.string(0) })
    }

    /// An `id` of 0 lets SQLite assign one; a non-zero id is honoured (what undo relies on).
    @discardableResult
    func insert(_ p: CookedPhotoRecord) throws -> Int64 {
        try db.run(
            "INSERT INTO cooked_photos (\(CookedPhotoRecord.columns)) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            p.id == 0 ? nil : p.id, p.recipeId, p.fileName, p.day, p.note, p.createdAt, p.updatedAt, p.uid
        )
        return db.lastInsertRowId
    }

    func edit(_ id: Int64, day: Int64, note: String?, now: Int64) throws {
        try db.run("UPDATE cooked_photos SET day = ?, note = ?, updatedAt = ? WHERE id = ?", day, note, now, id)
    }

    func delete(_ id: Int64) throws {
        try db.run("DELETE FROM cooked_photos WHERE id = ?", id)
    }
}
