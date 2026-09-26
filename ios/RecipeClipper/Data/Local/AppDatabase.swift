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
            // Switching to WAL needs an exclusive lock, and SQLite reports that as busy at
            // once, without the busy handler, when the other process is opening the file too.
            // So retry it (up to ~5 s, like the timeout); once the file is WAL it's a no-op.
            var attempt = 0
            while true {
                do {
                    _ = try connection.query("PRAGMA journal_mode = WAL") { $0.optionalString(0) }
                    break
                } catch let error as SQLiteError where (error.code & 0xFF) == SQLITE_BUSY && attempt < 100 {
                    attempt += 1
                    Thread.sleep(forTimeInterval: 0.05)
                }
            }
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
        addNotes,
        addUids,
        addLanguage,
        addCookState,
        addContentOrigin,
        addMealPlan,
        addGroceries,
        addPantry,
        addMenus,
        addShortSteps,
        addAiDecisions,
    ]

    /// Brings `db` up to `target` (the current version unless a test asks to stop early, to
    /// build an old database the way an old build would have and then migrate it for real).
    static func migrate(_ db: SQLiteConnection, upTo target: Int = migrations.count) throws {
        let current = try db.queryOne("PRAGMA user_version") { $0.int(0) } ?? 0
        guard current <= migrations.count else {
            // A newer build wrote this file. Refuse rather than guess (and never wipe it).
            throw SQLiteError(
                code: SQLITE_ERROR,
                message: "database is at schema version \(current); this build knows \(migrations.count)"
            )
        }
        for version in current..<max(current, target) {
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

    /// Version 2 (Android's Room version 3, `MIGRATION_2_3`): the user's personal note on a
    /// recipe. Nullable with no default, so every existing recipe simply has no note yet.
    private static func addNotes(_ db: SQLiteConnection) throws {
        try db.execute("ALTER TABLE recipes ADD COLUMN notes TEXT")
    }

    /// Version 3 (Android's Room version 4, `MIGRATION_3_4`): a stable `uid` on every recipe and
    /// list (#26), what an export file calls them, so a list keeps its identity through a rename
    /// and a later import or sync (#53) can recognise it. Existing rows (the lists seeded by
    /// version 1 included) are backfilled with random version-4 UUIDs; the `''` default exists
    /// only so the column can be added NOT NULL. The same SQL as Android.
    private static func addUids(_ db: SQLiteConnection) throws {
        for table in ["recipes", "lists"] {
            try db.execute("ALTER TABLE \(table) ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
            try db.execute("UPDATE \(table) SET uid = \(randomUuidSql)")
            try db.execute("CREATE UNIQUE INDEX IF NOT EXISTS index_\(table)_uid ON \(table) (uid)")
        }
    }

    /// A random version-4 UUID, lowercase, evaluated afresh for every row.
    private static let randomUuidSql = """
        lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || \
        substr(lower(hex(randomblob(2))), 2) || '-' || \
        substr('89ab', 1 + (abs(random()) % 4), 1) || substr(lower(hex(randomblob(2))), 2) || '-' || \
        lower(hex(randomblob(6)))
        """

    /// Version 4 (Android's Room version 5, `MIGRATION_4_5`): the recipe's language tag (#14).
    /// Nullable with no default: a recipe stored before has none and is detected from its own
    /// words when shown. A re-share fills it in.
    private static func addLanguage(_ db: SQLiteConnection) throws {
        try db.execute("ALTER TABLE recipes ADD COLUMN language TEXT")
    }

    /// Version 5 (Android's Room version 6, `MIGRATION_5_6`): saved cook progress and the chosen
    /// servings (#10). Both nullable with no default: an existing recipe has no cook in
    /// progress and uses its own yield.
    private static func addCookState(_ db: SQLiteConnection) throws {
        try db.execute("ALTER TABLE recipes ADD COLUMN cookState TEXT")
        try db.execute("ALTER TABLE recipes ADD COLUMN servingsTarget INTEGER")
    }

    /// Version 6 (Android's Room version 7, `MIGRATION_6_7`): whose words a recipe is (#29),
    /// `contentOrigin` (PARSED, EDITED, CLIPPED or MANUAL, by name) and `editedAt`. Everything
    /// stored before was parsed from its link and never edited: PARSED and null.
    private static func addContentOrigin(_ db: SQLiteConnection) throws {
        try db.execute("ALTER TABLE recipes ADD COLUMN contentOrigin TEXT NOT NULL DEFAULT 'PARSED'")
        try db.execute("ALTER TABLE recipes ADD COLUMN editedAt INTEGER")
    }

    /// Version 7 (Android's Room version 8, `MIGRATION_7_8`): the week meal plan (#49).
    /// `meal_types`, seeded with Breakfast, Lunch, Dinner and Snack (each with a `builtInKey`
    /// that survives a rename), and `meal_plan_entries`. Both new, so nothing existing changes.
    /// Every row has a stable `uid` and an `updatedAt`, for export and a later sync (#53). The
    /// same tables as Android's.
    private static func addMealPlan(_ db: SQLiteConnection) throws {
        try db.execute("""
            CREATE TABLE meal_types (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                builtInKey TEXT,
                sortOrder INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                uid TEXT NOT NULL
            );
            CREATE UNIQUE INDEX index_meal_types_uid ON meal_types (uid);

            CREATE TABLE meal_plan_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                day INTEGER NOT NULL,
                mealTypeId INTEGER NOT NULL,
                recipeId INTEGER,
                servings INTEGER,
                note TEXT,
                sortOrder INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                uid TEXT NOT NULL,
                FOREIGN KEY (mealTypeId) REFERENCES meal_types (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY (recipeId) REFERENCES recipes (id) ON UPDATE NO ACTION ON DELETE CASCADE
            );
            CREATE UNIQUE INDEX index_meal_plan_entries_uid ON meal_plan_entries (uid);
            CREATE INDEX index_meal_plan_entries_day ON meal_plan_entries (day);
            CREATE INDEX index_meal_plan_entries_mealTypeId ON meal_plan_entries (mealTypeId);
            CREATE INDEX index_meal_plan_entries_recipeId ON meal_plan_entries (recipeId);
            """)
        let now = SystemClock().now()
        for (index, (key, name)) in builtInMealTypes.enumerated() {
            try db.run(
                "INSERT INTO meal_types (name, builtInKey, sortOrder, updatedAt, uid) VALUES (?, ?, ?, ?, ?)",
                name, key, index, now, newUid()
            )
        }
    }

    /// Version 8 (Android's Room version 9, `MIGRATION_8_9`): the grocery list (#50).
    /// `grocery_items`, new, so nothing existing changes. One list for now (`listId` 1); a
    /// recipe's items outlive it (SET NULL). Every row has a stable `uid` and an `updatedAt`,
    /// for export and a later sync (#53). The same table as Android's.
    private static func addGroceries(_ db: SQLiteConnection) throws {
        try db.execute("""
            CREATE TABLE grocery_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                listId INTEGER NOT NULL,
                text TEXT NOT NULL,
                language TEXT,
                aisle TEXT NOT NULL,
                checked INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL,
                recipeId INTEGER,
                plannedDay INTEGER,
                updatedAt INTEGER NOT NULL,
                uid TEXT NOT NULL,
                FOREIGN KEY (recipeId) REFERENCES recipes (id) ON UPDATE NO ACTION ON DELETE SET NULL
            );
            CREATE UNIQUE INDEX index_grocery_items_uid ON grocery_items (uid);
            CREATE INDEX index_grocery_items_listId ON grocery_items (listId);
            CREATE INDEX index_grocery_items_recipeId ON grocery_items (recipeId);
            """)
    }

    /// Version 9 (Android's Room version 10, `MIGRATION_9_10`): the pantry (#51). `pantry_items`,
    /// new, so nothing existing changes. Every row has a stable `uid` and an `updatedAt`, for
    /// export and a later sync (#53). The same table as Android's.
    private static func addPantry(_ db: SQLiteConnection) throws {
        try db.execute("""
            CREATE TABLE pantry_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                quantity TEXT,
                language TEXT,
                aisle TEXT NOT NULL,
                inStock INTEGER NOT NULL,
                alwaysHave INTEGER NOT NULL,
                purchasedDay INTEGER,
                expiresDay INTEGER,
                updatedAt INTEGER NOT NULL,
                uid TEXT NOT NULL
            );
            CREATE UNIQUE INDEX index_pantry_items_uid ON pantry_items (uid);
            """)
    }

    /// Version 10 (Android's Room version 11, `MIGRATION_10_11`): reusable weekly menus (#52).
    /// `menus` and `menu_entries`, new, so nothing existing changes. Every row has a stable `uid`
    /// and an `updatedAt`, for export and a later sync (#53). The same tables as Android's.
    /// Version 11 (Android's Room version 12, `MIGRATION_11_12`): Chef mode's short steps (#100),
    /// derived data keyed by recipe, the step's text (SHA-256) and language. Never exported.
    private static func addShortSteps(_ db: SQLiteConnection) throws {
        try db.execute("""
            CREATE TABLE short_steps (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                recipeId INTEGER NOT NULL,
                stepHash TEXT NOT NULL,
                language TEXT NOT NULL,
                shortText TEXT,
                updatedAt INTEGER NOT NULL,
                uid TEXT NOT NULL,
                FOREIGN KEY (recipeId) REFERENCES recipes (id) ON UPDATE NO ACTION ON DELETE CASCADE
            );
            CREATE UNIQUE INDEX index_short_steps_uid ON short_steps (uid);
            CREATE UNIQUE INDEX index_short_steps_recipeId_stepHash_language ON short_steps (recipeId, stepHash, language);
            """)
    }

    /// Version 12 (Android's Room version 13, `MIGRATION_12_13`): the on-device model's typed
    /// decisions (#104), one row per question (kind, normalised input, language). Never exported.
    private static func addAiDecisions(_ db: SQLiteConnection) throws {
        try db.execute("""
            CREATE TABLE ai_decisions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                kind TEXT NOT NULL,
                input TEXT NOT NULL,
                language TEXT NOT NULL,
                answer TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                uid TEXT NOT NULL
            );
            CREATE UNIQUE INDEX index_ai_decisions_uid ON ai_decisions (uid);
            CREATE UNIQUE INDEX index_ai_decisions_kind_input_language ON ai_decisions (kind, input, language);
            """)
    }

    private static func addMenus(_ db: SQLiteConnection) throws {
        try db.execute("""
            CREATE TABLE menus (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                uid TEXT NOT NULL
            );
            CREATE UNIQUE INDEX index_menus_uid ON menus (uid);

            CREATE TABLE menu_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                menuId INTEGER NOT NULL,
                dayOffset INTEGER NOT NULL,
                mealTypeId INTEGER NOT NULL,
                recipeId INTEGER,
                servings INTEGER,
                note TEXT,
                sortOrder INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                uid TEXT NOT NULL,
                FOREIGN KEY (menuId) REFERENCES menus (id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY (mealTypeId) REFERENCES meal_types (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY (recipeId) REFERENCES recipes (id) ON UPDATE NO ACTION ON DELETE CASCADE
            );
            CREATE UNIQUE INDEX index_menu_entries_uid ON menu_entries (uid);
            CREATE INDEX index_menu_entries_menuId ON menu_entries (menuId);
            CREATE INDEX index_menu_entries_mealTypeId ON menu_entries (mealTypeId);
            CREATE INDEX index_menu_entries_recipeId ON menu_entries (recipeId);
            """)
    }

    /// The seeded meal types (#49), in the Week's order. Dinner is where new meals default and
    /// where a deleted type's meals go.
    static let builtInMealTypes: [(String, String)] = [
        ("breakfast", "Breakfast"), ("lunch", "Lunch"), (MealType.dinner, "Dinner"), ("snack", "Snack"),
    ]

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
