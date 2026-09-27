import Foundation
import SQLite3

// A deliberately small wrapper over the system SQLite3 C API: prepared statements, binding,
// stepping, transactions. No third-party dependency, so the Android (Room) SQL ports almost
// verbatim.
//
// NOT thread-safe on its own. Every SQLiteConnection is owned by an AppDatabase and only ever
// touched on that database's serial queue.

struct SQLiteError: Error, CustomStringConvertible {
    let code: Int32
    let message: String

    var description: String { "SQLite error \(code): \(message)" }

    /// True for UNIQUE / FOREIGN KEY / NOT NULL / CHECK violations.
    var isConstraintViolation: Bool { (code & 0xFF) == SQLITE_CONSTRAINT }
}

/// SQLITE_TRANSIENT: tells SQLite to copy bound text immediately.
private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// A Swift value that can be bound to a `?` placeholder. `nil` binds NULL.
protocol SQLiteBindable {
    func bind(to statement: OpaquePointer, at index: Int32) -> Int32
}

extension Int64: SQLiteBindable {
    func bind(to statement: OpaquePointer, at index: Int32) -> Int32 {
        sqlite3_bind_int64(statement, index, self)
    }
}

extension Int: SQLiteBindable {
    func bind(to statement: OpaquePointer, at index: Int32) -> Int32 {
        sqlite3_bind_int64(statement, index, Int64(self))
    }
}

extension Bool: SQLiteBindable {
    func bind(to statement: OpaquePointer, at index: Int32) -> Int32 {
        sqlite3_bind_int64(statement, index, self ? 1 : 0)
    }
}

extension Double: SQLiteBindable {
    func bind(to statement: OpaquePointer, at index: Int32) -> Int32 {
        sqlite3_bind_double(statement, index, self)
    }
}

extension String: SQLiteBindable {
    func bind(to statement: OpaquePointer, at index: Int32) -> Int32 {
        sqlite3_bind_text(statement, index, self, -1, sqliteTransient)
    }
}

/// One result row, read by column index (every SELECT here names its columns explicitly).
struct SQLiteRow {
    fileprivate let statement: OpaquePointer

    func isNull(_ index: Int32) -> Bool { sqlite3_column_type(statement, index) == SQLITE_NULL }
    func int64(_ index: Int32) -> Int64 { sqlite3_column_int64(statement, index) }
    func int(_ index: Int32) -> Int { Int(sqlite3_column_int64(statement, index)) }
    func bool(_ index: Int32) -> Bool { sqlite3_column_int64(statement, index) != 0 }

    func optionalString(_ index: Int32) -> String? {
        guard let text = sqlite3_column_text(statement, index) else { return nil }
        return String(cString: text)
    }

    /// For NOT NULL text columns.
    func string(_ index: Int32) -> String { optionalString(index) ?? "" }
}

final class SQLiteConnection {
    private var handle: OpaquePointer?

    /// Opens (creating if needed) the database at `path`; ":memory:" for an in-memory one.
    init(path: String) throws {
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        let rc = sqlite3_open_v2(path, &handle, flags, nil)
        guard rc == SQLITE_OK else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "cannot open"
            sqlite3_close_v2(handle)
            handle = nil
            throw SQLiteError(code: rc, message: message)
        }
        sqlite3_extended_result_codes(handle, 1)
    }

    deinit {
        sqlite3_close_v2(handle)
    }

    var lastInsertRowId: Int64 { sqlite3_last_insert_rowid(handle) }

    /// Rows changed by the most recent INSERT/UPDATE/DELETE.
    var changes: Int { Int(sqlite3_changes(handle)) }

    /// Rows changed since the connection opened. Used to tell a no-op write from a real one.
    var totalChanges: Int { Int(sqlite3_total_changes(handle)) }

    var isInTransaction: Bool { sqlite3_get_autocommit(handle) == 0 }

    /// Runs one or more statements with no parameters and no results (DDL, PRAGMA setters).
    func execute(_ sql: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(handle, sql, nil, nil, &error)
        if rc != SQLITE_OK {
            let message = error.map { String(cString: $0) } ?? lastErrorMessage
            sqlite3_free(error)
            throw SQLiteError(code: rc, message: message)
        }
    }

    /// Runs a single statement, discarding any rows it returns.
    func run(_ sql: String, _ args: SQLiteBindable?...) throws {
        try withStatement(sql, args) { statement in
            while try step(statement) {}
        }
    }

    /// `run` with its arguments as an array, for a statement with a variable number of `?`.
    func run(_ sql: String, arguments: [SQLiteBindable?]) throws {
        try withStatement(sql, arguments) { statement in
            while try step(statement) {}
        }
    }

    func query<T>(_ sql: String, _ args: SQLiteBindable?..., map: (SQLiteRow) throws -> T) throws -> [T] {
        try withStatement(sql, args) { statement in
            var result: [T] = []
            while try step(statement) {
                result.append(try map(SQLiteRow(statement: statement)))
            }
            return result
        }
    }

    func queryOne<T>(_ sql: String, _ args: SQLiteBindable?..., map: (SQLiteRow) throws -> T) throws -> T? {
        try withStatement(sql, args) { statement in
            guard try step(statement) else { return nil }
            return try map(SQLiteRow(statement: statement))
        }
    }

    /// Runs `body` in a transaction: committed if it returns, rolled back if it throws. A call
    /// made while a transaction is already open simply joins it (no savepoints needed here).
    func transaction<T>(_ body: () throws -> T) throws -> T {
        if isInTransaction { return try body() }
        try execute("BEGIN IMMEDIATE")
        do {
            let result = try body()
            try execute("COMMIT")
            return result
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    // MARK: - Internals

    private var lastErrorMessage: String {
        handle.map { String(cString: sqlite3_errmsg($0)) } ?? "no connection"
    }

    private func withStatement<T>(
        _ sql: String,
        _ args: [SQLiteBindable?],
        _ body: (OpaquePointer) throws -> T
    ) throws -> T {
        var statement: OpaquePointer?
        let rc = sqlite3_prepare_v2(handle, sql, -1, &statement, nil)
        guard rc == SQLITE_OK, let statement else {
            throw SQLiteError(code: rc, message: "\(lastErrorMessage) — in: \(sql)")
        }
        defer { sqlite3_finalize(statement) }

        let expected = sqlite3_bind_parameter_count(statement)
        guard expected == Int32(args.count) else {
            throw SQLiteError(
                code: SQLITE_RANGE,
                message: "expected \(expected) arguments, got \(args.count) — in: \(sql)"
            )
        }
        for (offset, arg) in args.enumerated() {
            let index = Int32(offset + 1)
            let bound = arg?.bind(to: statement, at: index) ?? sqlite3_bind_null(statement, index)
            guard bound == SQLITE_OK else {
                throw SQLiteError(code: bound, message: lastErrorMessage)
            }
        }
        return try body(statement)
    }

    /// True while there is a row to read; false once the statement is done.
    private func step(_ statement: OpaquePointer) throws -> Bool {
        switch sqlite3_step(statement) {
        case SQLITE_ROW: return true
        case SQLITE_DONE: return false
        case let rc: throw SQLiteError(code: rc, message: lastErrorMessage)
        }
    }
}
