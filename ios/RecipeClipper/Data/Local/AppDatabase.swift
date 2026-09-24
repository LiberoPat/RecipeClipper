import Combine
import Foundation
import os
import SQLite3

let dataLog = Logger(subsystem: "com.liberopat.recipeclipper", category: "data")

/// The app's one SQLite database: schema, migrations, serialized access and change
/// notification. The iOS counterpart of Android's `RecipeDatabase` (Room).
///
/// Every access goes through one serial queue, so the single connection is never used from two
/// threads at once. `read` / `write` are async and never block the caller; `write` wraps its
/// body in a transaction and, once it has committed a real change, fires `didChange`, which is
/// what the `observe*` publishers re-query on (Room's invalidation tracker, in miniature).
final class AppDatabase: @unchecked Sendable {

    /// The schema version this build writes. `PRAGMA user_version` holds the one on disk.
    static var schemaVersion: Int { migrations.count }

    /// Fired on the database queue after every committed write that changed at least one row.
    let didChange = PassthroughSubject<Void, Never>()

    private let connection: SQLiteConnection
    private let queue = DispatchQueue(label: "com.liberopat.recipeclipper.AppDatabase")

    /// `path == nil` opens an in-memory database (tests). Seeds built-in lists on create.
    init(path: String?) throws {
        connection = try SQLiteConnection(path: path ?? ":memory:")
        // Off by default in SQLite, per connection; without it the cross-ref cascades don't
        // happen. Must be set outside a transaction, hence before migrating.
        try connection.execute("PRAGMA foreign_keys = ON")
        if path != nil {
            // The share extension opens this file from its own process, so wait out the other
            // side's lock (set first: switching to WAL takes a lock too) rather than failing.
            try connection.execute("PRAGMA busy_timeout = 5000")
            _ = try connection.query("PRAGMA journal_mode = WAL") { $0.optionalString(0) }
        }
        try Self.migrate(connection)
    }

    /// `recipe_clipper.sqlite` in the App Group container, which the share extension writes to
    /// as well; nil when the group container isn't available to this build.
    static func sharedPath() -> String? {
        AppGroup.containerURL?.appendingPathComponent("recipe_clipper.sqlite").path
    }

    /// Where the app keeps its database: the shared container or, if this build isn't
    /// entitled to the App Group, Application Support, so the app still works on its own (only
    /// the share extension's saves go missing, and that is logged).
    static func defaultPath() -> String {
        if let shared = sharedPath() { return shared }
        dataLog.error("App Group container unavailable; using the app's own database")
        let fm = FileManager.default
        let base = (try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                appropriateFor: nil, create: true))
            ?? fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? fm.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("recipe_clipper.sqlite").path
    }

    /// Re-runs every `observe` query. The app calls it when it comes to the foreground: the
    /// share extension writes the same file from its own process, and those writes never fire
    /// this process's `didChange`.
    func refreshObservers() {
        queue.async { self.didChange.send(()) }
    }

    // MARK: - Access

    /// Runs `body` on the database queue. For queries.
    func read<T>(_ body: @escaping (SQLiteConnection) throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                continuation.resume(with: Result { try body(self.connection) })
            }
        }
    }

    /// Runs `body` on the database queue inside one transaction (rolled back if it throws),
    /// then notifies observers if anything actually changed. Observers are notified *before*
    /// the caller resumes, so their re-query is already queued behind this write.
    func write<T>(_ body: @escaping (SQLiteConnection) throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                let before = self.connection.totalChanges
                let result = Result { try self.connection.transaction { try body(self.connection) } }
                if case .success = result, self.connection.totalChanges != before {
                    self.didChange.send(())
                }
                continuation.resume(with: result)
            }
        }
    }

    /// A publisher that runs `query` now and again after every committed change, delivering
    /// each result on the main queue. The query itself runs on the database queue, never main.
    /// A failing query is logged and skipped rather than terminating the stream.
    func observe<T>(_ query: @escaping (SQLiteConnection) throws -> T) -> AnyPublisher<T, Never> {
        Just(())
            .merge(with: didChange)
            .receive(on: queue)
            .compactMap { [weak self] _ -> T? in
                guard let self else { return nil }
                do {
                    return try query(self.connection)
                } catch {
                    dataLog.error("observed query failed: \(String(describing: error), privacy: .public)")
                    return nil
                }
            }
            .receive(on: DispatchQueue.main)
            .eraseToAnyPublisher()
    }

    // MARK: - Schema and migrations

    // NEVER use destructive migration. Every schema or seed-data change is a new entry appended
    // to `migrations` that upgrades a real, populated database in place — a user's history and
    // lists are their data. Existing entries are never edited once shipped: a device already
    // past that version never runs it again (the same reason Android needed MIGRATION_1_2 to
    // add Breakfast and Snacks: seeding runs once, at create).
    //
    // `migrations[n]` upgrades user_version n to n + 1, each in its own transaction together
    // with the version bump, so a crash mid-migration leaves the previous version intact.
    private static let migrations: [(SQLiteConnection) throws -> Void] = [
        createVersion1,
    ]

    private static func migrate(_ db: SQLiteConnection) throws {
        let current = try db.queryOne("PRAGMA user_version") { $0.int(0) } ?? 0
        guard current <= migrations.count else {
            // A newer build wrote this file. Refuse rather than guess (and never wipe it).
            throw SQLiteError(
                code: SQLITE_ERROR,
                message: "database is at schema version \(current); this build knows \(migrations.count)"
            )
        }
        for version in current..<migrations.count {
            try db.transaction {
                // Re-read under the write lock (BEGIN IMMEDIATE): the app and the share
                // extension can open a new file at the same moment, and the second must not
                // rerun a migration the first has just committed.
                guard try db.queryOne("PRAGMA user_version", map: { $0.int(0) }) == version else { return }
                try migrations[version](db)
                try db.execute("PRAGMA user_version = \(version + 1)")
            }
        }
    }

    /// Version 1: the same three tables as Android's Room schema (version 2 there — its two
    /// versions have identical tables), plus the six built-in lists, seeded in the same
    /// transaction as creation.
    private static func createVersion1(_ db: SQLiteConnection) throws {
        try db.execute("""
            CREATE TABLE recipes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sourceUrl TEXT NOT NULL,
                title TEXT NOT NULL,
                imageUrl TEXT,
                ingredients TEXT NOT NULL,
                instructions TEXT NOT NULL,
                prepTime TEXT,
                cookTime TEXT,
                totalTime TEXT,
                servings TEXT,
                sourceType TEXT NOT NULL,
                lastViewedAt INTEGER NOT NULL,
                checkedIngredients TEXT NOT NULL
            );
            CREATE UNIQUE INDEX index_recipes_sourceUrl ON recipes (sourceUrl);

            CREATE TABLE lists (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                isBuiltIn INTEGER NOT NULL,
                isFavorites INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL,
                createdAt INTEGER NOT NULL
            );

            CREATE TABLE recipe_list_cross_ref (
                recipeId INTEGER NOT NULL,
                listId INTEGER NOT NULL,
                addedAt INTEGER NOT NULL,
                PRIMARY KEY (recipeId, listId),
                FOREIGN KEY (recipeId) REFERENCES recipes (id) ON DELETE CASCADE,
                FOREIGN KEY (listId) REFERENCES lists (id) ON DELETE CASCADE
            );
            CREATE INDEX index_recipe_list_cross_ref_listId ON recipe_list_cross_ref (listId);
            """)
        try seedBuiltInLists(db)
    }

    /// The seeded lists. Only Favorites is protected from deletion, identified by its
    /// `isFavorites` column, never its name or position — all six can be renamed. The rest are
    /// starting suggestions: `isBuiltIn` means only "seeded, and sorts before user lists".
    static let builtInLists = ["Favorites", "Lunch", "Dinner", "Desserts", "Breakfast", "Snacks"]

    private static func seedBuiltInLists(_ db: SQLiteConnection) throws {
        let now = SystemClock().now()
        for (index, name) in builtInLists.enumerated() {
            try db.run(
                "INSERT INTO lists (name, isBuiltIn, isFavorites, sortOrder, createdAt) VALUES (?, 1, ?, ?, ?)",
                name, index == 0, index, now
            )
        }
    }
}
