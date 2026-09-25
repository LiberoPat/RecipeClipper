import XCTest
@testable import RecipeClipper

/// Port of Android's RecipeDaoTest: the database rules from CLAUDE.md against real SQLite (an
/// in-memory AppDatabase, created by the same migration production runs).
final class RecipeDaoTests: XCTestCase {
    private var db: AppDatabase!

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
    }

    override func tearDown() {
        db = nil
    }

    // MARK: - Schema and seeding

    func testBuiltInListsAreSeededOnFirstCreate() async throws {
        let all = try await db.allLists()
        XCTAssertEqual(all.map(\.name), ["Favorites", "Lunch", "Dinner", "Desserts", "Breakfast", "Snacks"])
        XCTAssertTrue(all.allSatisfy(\.isBuiltIn))
    }

    func testExactlyOneListIsFavoritesAndItIsFlaggedNotNamed() async throws {
        let all = try await db.allLists()
        XCTAssertEqual(all.filter(\.isFavorites).count, 1)
        XCTAssertEqual(all.first { $0.isFavorites }?.name, "Favorites")
    }

    func testSchemaVersionIsRecorded() async throws {
        let version = try await db.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        XCTAssertEqual(AppDatabase.schemaVersion, 9)
    }

    /// Version 2 adds the personal note (and version 3 the uids). A version-1 file, built by the real version-1
    /// migration exactly as an old build left it, opens with its recipe, ticks and list
    /// membership intact and no note — the iOS counterpart of Android's MigrationTest.
    func testAVersion1DatabaseMigratesToVersion2KeepingItsData() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try old.execute("PRAGMA foreign_keys = ON")
            try AppDatabase.migrate(old, upTo: 1)
            // Written with version 1's own columns: there is no notes column yet.
            try old.run(
                """
                INSERT INTO recipes (id, sourceUrl, title, imageUrl, ingredients, instructions,
                    prepTime, cookTime, totalTime, servings, sourceType, lastViewedAt, checkedIngredients)
                VALUES (7, 'https://example.com/a', 'Adobo', NULL, '["1 cup soy sauce"]', '["Simmer."]',
                    NULL, NULL, NULL, '4', 'BLOG', 123, '[0]')
                """
            )
            try old.run("INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (7, 1, 5)")
            XCTAssertEqual(try old.queryOne("PRAGMA user_version") { $0.int(0) }, 1)
        } // closed here, as the old build would have left it

        let migrated = try AppDatabase(path: path)

        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        let row = try await migrated.get(7)
        XCTAssertEqual(row?.title, "Adobo")
        XCTAssertEqual(row?.ingredients, ["1 cup soy sauce"])
        XCTAssertEqual(row?.checkedIngredients, [0])
        XCTAssertEqual(row?.lastViewedAt, 123)
        XCTAssertNil(row?.notes)
        let refs = try await migrated.crossRefs(7)
        XCTAssertEqual(refs.map(\.listId), [1])
        let lists = try await migrated.allLists()
        XCTAssertEqual(lists.count, 6, "migrating must not reseed the built-in lists")

        // The new column is writable and survives a re-share.
        try await migrated.write { try RecipeDao(db: $0).setNotes(7, notes: "Less salt") }
        try await migrated.upsert(dataRecipeRecord("https://example.com/a", viewedAt: 900, title: "Chicken adobo"))
        let notes = try await migrated.get(7)?.notes
        XCTAssertEqual(notes, "Less salt")
    }

    /// Version 3 gives every recipe and list a stable uid (#26): existing rows get distinct
    /// UUID-shaped ones and keep everything else; a re-share keeps its uid; new rows get their own.
    func testAVersion2DatabaseMigratesToVersion3GivingEveryRowAUid() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try old.execute("PRAGMA foreign_keys = ON")
            try AppDatabase.migrate(old, upTo: 2)
            for id in 1...3 {
                try old.run(
                    """
                    INSERT INTO recipes (id, sourceUrl, title, imageUrl, ingredients, instructions,
                        prepTime, cookTime, totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes)
                    VALUES (?, ?, 'R', NULL, '[]', '[]', NULL, NULL, NULL, NULL, 'BLOG', ?, '[]', 'n')
                    """,
                    Int64(id), "https://example.com/\(id)", Int64(id * 10)
                )
            }
            try old.run("INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (1, 1, 5)")
        }

        let migrated = try AppDatabase(path: path)

        let snapshot = try await migrated.read { try BackupDao(db: $0).snapshot() }
        let uids = snapshot.recipes.map(\.uid) + snapshot.lists.map(\.uid)
        XCTAssertEqual(Set(uids).count, 9)
        let uuid = try NSRegularExpression(pattern: "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        for uid in uids {
            XCTAssertNotNil(uuid.firstMatch(in: uid, range: NSRange(uid.startIndex..., in: uid)), uid)
        }
        let one = try await migrated.get(1)
        XCTAssertEqual(one?.notes, "n")
        let refs = try await migrated.crossRefs(1)
        XCTAssertEqual(refs.map(\.listId), [1])

        let before = try await migrated.get(2)!
        var fresh = dataRecipeRecord("https://example.com/2", viewedAt: 99, title: "New title")
        fresh.uid = "should-not-win"
        try await migrated.upsert(fresh)
        let after = try await migrated.get(2)
        XCTAssertEqual(after?.uid, before.uid)
        try await migrated.write { _ = try ListDao(db: $0).create(name: "Mine", recipeId: ListDao.noRecipe, now: 0) }
        let lists = try await migrated.read { try BackupDao(db: $0).snapshot().lists }
        XCTAssertEqual(Set(lists.map(\.uid)).count, 7)
    }

    /// Version 4 adds the recipe's language (#14): a version-3 file keeps its data, note and
    /// uid, with no language, and a re-share fills it in (Android's MigrationTest 4→5).
    func testAVersion3DatabaseMigratesToVersion4WithNoLanguage() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try old.execute("PRAGMA foreign_keys = ON")
            try AppDatabase.migrate(old, upTo: 3)
            try old.run(
                """
                INSERT INTO recipes (id, sourceUrl, title, imageUrl, ingredients, instructions, prepTime,
                    cookTime, totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes, uid)
                VALUES (7, 'https://example.com/a', 'Adobo', NULL, '["1 cup soy sauce"]', '["Simmer."]',
                    NULL, NULL, NULL, '4', 'BLOG', 123, '[0]', 'Less salt', 'uid-7')
                """
            )
            XCTAssertEqual(try old.queryOne("PRAGMA user_version") { $0.int(0) }, 3)
        }

        let migrated = try AppDatabase(path: path)
        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        let row = try await migrated.get(7)
        XCTAssertEqual(row?.title, "Adobo")
        XCTAssertEqual(row?.checkedIngredients, [0])
        XCTAssertEqual(row?.notes, "Less salt")
        XCTAssertEqual(row?.uid, "uid-7")
        XCTAssertNil(row?.language)

        var reshared = dataRecipeRecord("https://example.com/a", viewedAt: 900)
        reshared.language = "en-us"
        try await migrated.upsert(reshared)
        let after = try await migrated.get(7)
        XCTAssertEqual(after?.language, "en-us")
        XCTAssertEqual(after?.notes, "Less salt")
        XCTAssertEqual(after?.uid, "uid-7")
    }

    func testAnUndoneDeleteKeepsItsUid() async throws {
        let repo = DefaultRecipeRepository(db: db, source: DataStubSource(), clock: DataTestClock())
        let id = try await db.upsert(dataRecipeRecord("https://example.com/u", viewedAt: 1))
        let uid = try await db.get(id)?.uid
        let deleted = await repo.delete(id: id)
        await repo.restore(try XCTUnwrap(deleted))
        let restored = try await db.get(id)?.uid
        XCTAssertEqual(restored, uid)
    }

    /// Version 5 adds saved cook progress and the chosen servings (Android's MIGRATION_5_6). A
    /// version-4 file opens with its recipe, uid, language, note, ticks and membership intact and neither.
    func testAVersion4DatabaseMigratesToVersion5KeepingItsData() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try old.execute("PRAGMA foreign_keys = ON")
            try AppDatabase.migrate(old, upTo: 4)
            try old.run(
                """
                INSERT INTO recipes (id, sourceUrl, title, imageUrl, ingredients, instructions,
                    prepTime, cookTime, totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes, uid, language)
                VALUES (7, 'https://example.com/a', 'Adobo', NULL, '["1 cup soy sauce"]', '["Simmer."]',
                    NULL, NULL, NULL, '4', 'BLOG', 123, '[0]', 'Less salt', 'recipe-uid', 'en')
                """
            )
            try old.run("INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (7, 1, 5)")
        }

        let migrated = try AppDatabase(path: path)

        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        let row = try await migrated.get(7)
        XCTAssertEqual(row?.title, "Adobo")
        XCTAssertEqual(row?.checkedIngredients, [0])
        XCTAssertEqual(row?.notes, "Less salt")
        XCTAssertEqual(row?.uid, "recipe-uid")
        XCTAssertEqual(row?.language, "en")
        XCTAssertNil(row?.cookState)
        XCTAssertNil(row?.servingsTarget)
        let refs = try await migrated.crossRefs(7)
        XCTAssertEqual(refs.map(\.listId), [1])

        try await migrated.write { try RecipeDao(db: $0).setServingsTarget(7, target: 6) }
        let target = try await migrated.get(7)?.servingsTarget
        XCTAssertEqual(target, 6)
    }

    /// Version 6 (#29; Android's Room 7): whose words a recipe is. Everything stored before was
    /// parsed from its link and never edited: PARSED, no editedAt, nothing else changes.
    func testAVersion5DatabaseMigratesToVersion6AsParsedAndUnedited() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try old.execute("PRAGMA foreign_keys = ON")
            try AppDatabase.migrate(old, upTo: 5)
            try old.run(
                """
                INSERT INTO recipes (id, sourceUrl, title, imageUrl, ingredients, instructions,
                    prepTime, cookTime, totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes, uid,
                    language, cookState, servingsTarget)
                VALUES (7, 'https://example.com/a', 'Adobo', NULL, '["1 cup soy sauce"]', '["Simmer."]',
                    NULL, NULL, NULL, '4', 'BLOG', 123, '[0]', 'Less salt', 'recipe-uid', 'en', NULL, 6)
                """
            )
        }

        let migrated = try AppDatabase(path: path)

        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        // Opened by the current build, so it goes all the way up.
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        let row = try await migrated.get(7)
        XCTAssertEqual(row?.title, "Adobo")
        XCTAssertEqual(row?.notes, "Less salt")
        XCTAssertEqual(row?.servingsTarget, 6)
        XCTAssertEqual(row?.contentOrigin, "PARSED")
        XCTAssertNil(row?.editedAt)
    }

    // MARK: - The user's version (#29)

    func testAReShareOfAnEditedRecipeKeepsItsContentAndOnlyCountsAsAView() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 1))
        _ = try await db.write {
            try RecipeDao(db: $0).saveEdit(id, edited: dataRecipeRecord("https://a.com/1", viewedAt: 1, title: "Mine"),
                                           origin: "EDITED", editedAt: 2)
        }

        try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 3, title: "Site's"))

        let row = try await db.get(id)
        XCTAssertEqual(row?.title, "Mine")
        XCTAssertEqual(row?.contentOrigin, "EDITED")
        XCTAssertEqual(row?.editedAt, 2)
        XCTAssertEqual(row?.lastViewedAt, 3)
    }

    func testUpdateFromSourceReplacesTheUsersVersionAndMakesItParsedAgain() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 1))
        try await db.write { try RecipeDao(db: $0).setNotes(id, notes: "Less salt") }
        try await db.putInList(id, 1)
        _ = try await db.write {
            try RecipeDao(db: $0).saveEdit(id, edited: dataRecipeRecord("https://a.com/1", viewedAt: 1, title: "Mine"),
                                           origin: "EDITED", editedAt: 2)
        }

        _ = try await db.write {
            try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/1", viewedAt: 3, title: "Site's"),
                                         historyLimit: historyLimit, replaceUsersVersion: true)
        }

        let row = try await db.get(id)
        XCTAssertEqual(row?.title, "Site's")
        XCTAssertEqual(row?.contentOrigin, "PARSED")
        XCTAssertNil(row?.editedAt)
        XCTAssertEqual(row?.notes, "Less salt")
        let refs = try await db.crossRefs(id)
        XCTAssertEqual(refs.map(\.listId), [1])
    }

    func testSaveEditKeepsTheLinkUidNoteAndTicksWhenIngredientsAreUnchanged() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 1, checked: [1]))
        try await db.write { try RecipeDao(db: $0).setNotes(id, notes: "Less salt") }
        let before = try await db.get(id)

        let saved = try await db.write {
            try RecipeDao(db: $0).saveEdit(id, edited: dataRecipeRecord("manual:ignored", viewedAt: 99, title: "Mine"),
                                           origin: "EDITED", editedAt: 5)
        }

        let row = try await db.get(id)
        XCTAssertTrue(saved)
        XCTAssertEqual(row?.title, "Mine")
        XCTAssertEqual(row?.sourceUrl, "https://a.com/1")
        XCTAssertEqual(row?.uid, before?.uid)
        XCTAssertEqual(row?.lastViewedAt, 1)
        XCTAssertEqual(row?.notes, "Less salt")
        XCTAssertEqual(row?.checkedIngredients, [1])
        let missing = try await db.write {
            try RecipeDao(db: $0).saveEdit(12345, edited: row!, origin: "EDITED", editedAt: 6)
        }
        XCTAssertFalse(missing)
    }

    private let cookJSON = #"{"active":true,"currentStep":1,"doneSteps":[0],"timers":[]}"#

    func testCookStateAndServingsSurviveAReShareWithTheSameSteps() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        try await db.write { conn in
            try RecipeDao(db: conn).setCookState(id, cookState: self.cookJSON)
            try RecipeDao(db: conn).setServingsTarget(id, target: 8)
        }

        try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 900, title: "New title"))

        let row = try await db.get(id)
        XCTAssertEqual(row?.cookState, cookJSON)
        XCTAssertEqual(row?.servingsTarget, 8)
    }

    func testCookStateIsDroppedWhenTheStepsChangeButServingsAreKept() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        try await db.write { conn in
            try RecipeDao(db: conn).setCookState(id, cookState: self.cookJSON)
            try RecipeDao(db: conn).setServingsTarget(id, target: 8)
        }

        var changed = dataRecipeRecord("https://a.com/1", viewedAt: 900)
        changed.instructions = ["Stir.", "Chill."]
        try await db.upsert(changed)

        let row = try await db.get(id)
        XCTAssertNil(row?.cookState) // step indexes would point at different steps
        XCTAssertEqual(row?.servingsTarget, 8)
    }

    func testCookStatesListsOnlyRecipesWithCookProgress() async throws {
        let cooking = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        try await db.upsert(dataRecipeRecord("https://a.com/2", viewedAt: 200))
        try await db.write { try RecipeDao(db: $0).setCookState(cooking, cookState: self.cookJSON) }

        let rows = try await db.read { try RecipeDao(db: $0).cookStates() }
        XCTAssertEqual(rows, [CookStateRecord(id: cooking, title: "Recipe https://a.com/1", cookState: cookJSON)])
    }

    func testReopeningAFileDatabaseKeepsItsDataAndDoesNotReseed() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        var first: AppDatabase? = try AppDatabase(path: path)
        try await first!.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        first = nil

        let reopened = try AppDatabase(path: path)
        let count = try await reopened.recipeCount()
        let lists = try await reopened.allLists()
        XCTAssertEqual(count, 1)
        XCTAssertEqual(lists.count, 6)
    }

    // MARK: - Upsert

    func testANewLinkIsInserted() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        XCTAssertGreaterThan(id, 0)
        let title = try await db.get(id)?.title
        let count = try await db.recipeCount()
        XCTAssertEqual(title, "Recipe https://a.com/1")
        XCTAssertEqual(count, 1)
    }

    func testAReSharedLinkKeepsItsIdAndDoesNotDuplicate() async throws {
        let first = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        let second = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 900, title: "Renamed"))

        XCTAssertEqual(first, second)
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 1)
        let saved = try await db.get(first)
        XCTAssertEqual(saved?.lastViewedAt, 900)
        XCTAssertEqual(saved?.title, "Renamed")
    }

    func testAReSharedLinkKeepsItsListMembership() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        try await db.putInList(id, 1)

        try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 900))

        let refs = try await db.crossRefs(id)
        XCTAssertEqual(refs.map(\.listId), [1])
    }

    func testTickedIngredientsSurviveAReShareWhenTheIngredientsAreUnchanged() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        try await db.write { try RecipeDao(db: $0).setChecked(id, checked: [0, 1]) }

        try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 900))

        let checked = try await db.get(id)?.checkedIngredients
        XCTAssertEqual(checked, [0, 1])
    }

    func testANoteSurvivesAReShareEvenWhenTheContentChanges() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        try await db.write { try RecipeDao(db: $0).setNotes(id, notes: "Used half the sugar") }

        try await db.upsert(
            dataRecipeRecord("https://a.com/1", viewedAt: 900, title: "New title", ingredients: ["3 apples"])
        )

        let row = try await db.get(id)
        XCTAssertEqual(row?.title, "New title")
        XCTAssertEqual(row?.notes, "Used half the sugar")
    }

    func testSetNotesWritesAndClearsTheNote() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        let initial = try await db.get(id)?.notes
        XCTAssertNil(initial)

        try await db.write { try RecipeDao(db: $0).setNotes(id, notes: "Needs 10 more minutes") }
        let written = try await db.get(id)?.notes
        XCTAssertEqual(written, "Needs 10 more minutes")

        try await db.write { try RecipeDao(db: $0).setNotes(id, notes: nil) }
        let cleared = try await db.get(id)?.notes
        XCTAssertNil(cleared)
    }

    func testTickedIngredientsResetWhenTheIngredientsChange() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        try await db.write { try RecipeDao(db: $0).setChecked(id, checked: [0, 1]) }

        try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 900, ingredients: ["3 apples"]))

        let checked = try await db.get(id)?.checkedIngredients
        XCTAssertEqual(checked, [])
    }

    // MARK: - History cap

    func testHistoryIsCappedAtTheLimitKeepingTheMostRecent() async throws {
        var ids: [Int64] = []
        for n in 1...(historyLimit + 5) {
            ids.append(try await db.upsert(dataRecipeRecord("https://a.com/\(n)", viewedAt: Int64(n) * 1000)))
        }

        let count = try await db.recipeCount()
        XCTAssertEqual(count, historyLimit)
        for id in ids.prefix(5) {
            let row = try await db.get(id)
            XCTAssertNil(row, "the five oldest are gone")
        }
        for id in ids.dropFirst(5) {
            let row = try await db.get(id)
            XCTAssertNotNil(row)
        }
    }

    func testFiftyOneUnlistedMeansTheOldestIsGone() async throws {
        let oldest = try await db.upsert(dataRecipeRecord("https://a.com/oldest", viewedAt: 1))
        for n in 0..<historyLimit {
            try await db.upsert(dataRecipeRecord("https://a.com/\(n)", viewedAt: 1000 + Int64(n)))
        }
        let gone = try await db.get(oldest)
        let count = try await db.recipeCount()
        XCTAssertNil(gone)
        XCTAssertEqual(count, historyLimit)
    }

    func testRecipesInAListAreNeverCulled() async throws {
        let oldest = try await db.upsert(dataRecipeRecord("https://a.com/old", viewedAt: 1))
        try await db.putInList(oldest, 1)

        for n in 0..<(historyLimit + 5) {
            try await db.upsert(dataRecipeRecord("https://a.com/\(n)", viewedAt: 1000 + Int64(n)))
        }

        let kept = try await db.get(oldest)
        XCTAssertNotNil(kept, "a saved recipe must survive the cap")
        // The cap applies to the unsaved ones: 50 of them, plus the one saved recipe.
        let count = try await db.recipeCount()
        XCTAssertEqual(count, historyLimit + 1)
    }

    func testOpeningAnOldRecipeMovesItToTheTopSoItIsNotCulled() async throws {
        let old = try await db.upsert(dataRecipeRecord("https://a.com/old", viewedAt: 1))
        for n in 0..<(historyLimit - 1) {
            try await db.upsert(dataRecipeRecord("https://a.com/\(n)", viewedAt: 1000 + Int64(n)))
        }
        try await db.write { try RecipeDao(db: $0).touch(old, now: 999_999) }

        try await db.upsert(dataRecipeRecord("https://a.com/new", viewedAt: 2_000_000))

        let row = try await db.get(old)
        let count = try await db.recipeCount()
        XCTAssertNotNil(row)
        XCTAssertEqual(count, historyLimit)
    }

    // MARK: - Saved is derived

    func testSavedIsDerivedFromListMembership() async throws {
        let kept = try await db.upsert(dataRecipeRecord("https://a.com/kept", viewedAt: 200))
        let plain = try await db.upsert(dataRecipeRecord("https://a.com/plain", viewedAt: 100))
        try await db.putInList(kept, 2)

        let history = Dictionary(uniqueKeysWithValues: try await db.history().map { ($0.id, $0) })
        XCTAssertEqual(history[kept]?.isSaved, true)
        XCTAssertEqual(history[plain]?.isSaved, false)
    }

    // MARK: - Ordering, cascades, storage

    func testHistoryIsNewestFirst() async throws {
        let a = try await db.upsert(dataRecipeRecord("https://a.com/a", viewedAt: 100))
        let b = try await db.upsert(dataRecipeRecord("https://a.com/b", viewedAt: 300))
        let c = try await db.upsert(dataRecipeRecord("https://a.com/c", viewedAt: 200))

        let history = try await db.history().map(\.id)
        let recent = try await db.read { try RecipeDao(db: $0).recent(limit: 2) }.map(\.id)
        XCTAssertEqual(history, [b, c, a])
        XCTAssertEqual(recent, [b, c])
    }

    func testDeletingARecipeRemovesItsListMembership() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        try await db.putInList(id, 1)

        try await db.write { try $0.run("DELETE FROM recipes WHERE id = ?", id) }

        let refs = try await db.read { try $0.queryOne("SELECT COUNT(*) FROM recipe_list_cross_ref") { $0.int(0) } }
        XCTAssertEqual(refs, 0)
    }

    func testARecipeCannotBeAddedToAListThatDoesNotExist() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        do {
            try await db.putInList(id, 9999)
            XCTFail("foreign keys must be enforced")
        } catch let error as SQLiteError {
            XCTAssertTrue(error.isConstraintViolation, "\(error)")
        }
    }

    func testListsAndTickedIngredientsRoundTripThroughTheDatabase() async throws {
        let ingredients = ["1 cup flour, sifted", "\"good\" oil", "½ tsp salt", "日本語", "1/2 cup \\ milk"]
        let id = try await db.upsert(
            dataRecipeRecord("https://a.com/1", viewedAt: 100, ingredients: ingredients, checked: [3, 1])
        )

        let saved = try await db.get(id)
        XCTAssertEqual(saved?.ingredients, ingredients)
        XCTAssertEqual(saved?.instructions, ["Mix.", "Bake 20 minutes."])
        XCTAssertEqual(saved?.checkedIngredients, [1, 3])
        let stored = try await db.read {
            try $0.queryOne("SELECT checkedIngredients FROM recipes WHERE id = ?", id) { $0.string(0) }
        }
        XCTAssertEqual(stored, "[1,3]", "ticks are stored as sorted JSON ints")
    }

    func testFindByUrlReturnsNilForAnUnseenLink() async throws {
        let row = try await db.read { try RecipeDao(db: $0).findByUrl("https://never-seen.com") }
        XCTAssertNil(row)
    }

    // MARK: - Search

    func testSearchMatchesTitle() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100, title: "Chicken Adobo"))
        try await db.upsert(dataRecipeRecord("https://a.com/2", viewedAt: 200, title: "Beef Stew"))

        let found = try await db.history("adobo").map(\.id)
        XCTAssertEqual(found, [id])
    }

    func testSearchMatchesIngredients() async throws {
        let id = try await db.upsert(
            dataRecipeRecord("https://a.com/1", viewedAt: 100, ingredients: ["2 anchovy fillets", "1 lemon"])
        )
        try await db.upsert(dataRecipeRecord("https://a.com/2", viewedAt: 200, ingredients: ["2 cups flour"]))

        let found = try await db.history("anchovy").map(\.id)
        XCTAssertEqual(found, [id])
    }

    func testSearchFindsAFractionInIngredients() async throws {
        // Slashes are stored unescaped, so "1/2" can be found in the JSON column.
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100, ingredients: ["1/2 cup milk"]))
        let found = try await db.history("1/2 cup").map(\.id)
        XCTAssertEqual(found, [id])
    }

    func testAnEmptyQueryReturnsEverything() async throws {
        let a = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        let b = try await db.upsert(dataRecipeRecord("https://a.com/2", viewedAt: 200))

        let found = Set(try await db.history("").map(\.id))
        XCTAssertEqual(found, [a, b])
    }

    func testSearchIsCaseInsensitive() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100, title: "Chicken ADOBO"))

        let lower = try await db.history("adobo").map(\.id)
        let upper = try await db.history("ADOBO").map(\.id)
        XCTAssertEqual(lower, [id])
        XCTAssertEqual(upper, [id])
    }

    func testAQueryContainingAPercentSignMatchesLiterally() async throws {
        // Regression guard: LIKE treats % as a wildcard; instr() matches only the literal text.
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100, title: "100% Whole Wheat Bread"))
        try await db.upsert(dataRecipeRecord("https://a.com/2", viewedAt: 200, title: "Sourdough Bread"))

        let found = try await db.history("100%").map(\.id)
        XCTAssertEqual(found, [id])
        let underscore = try await db.history("_").map(\.id)
        XCTAssertEqual(underscore, [], "_ is literal too")
    }

    // MARK: - Delete and restore

    func testDeletingARecipeByIdRemovesTheRow() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))

        try await db.write { try RecipeDao(db: $0).delete(id) }

        let row = try await db.get(id)
        let count = try await db.recipeCount()
        XCTAssertNil(row)
        XCTAssertEqual(count, 0)
    }

    func testDeletingARecipeRemovesItsCrossRefs() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        try await db.putInList(id, 1)

        try await db.write { try RecipeDao(db: $0).delete(id) }

        let refs = try await db.crossRefs(id)
        XCTAssertTrue(refs.isEmpty)
    }

    func testCrossRefsForReturnsEmptyForARecipeInNoList() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        let refs = try await db.crossRefs(id)
        XCTAssertTrue(refs.isEmpty)
    }

    func testRestoreBringsBackTheRowAndItsListMembershipWithTheSameId() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100, title: "Chicken Adobo"))
        try await db.putInList(id, 1, at: 500)
        try await db.putInList(id, 2, at: 600)

        let row = try await db.get(id)!
        let refs = try await db.crossRefs(id)
        try await db.write { try RecipeDao(db: $0).delete(id) }
        let gone = try await db.get(id)
        XCTAssertNil(gone)

        try await db.write { try RecipeDao(db: $0).restore(row, crossRefs: refs) }

        let restored = try await db.get(id)
        XCTAssertEqual(restored?.id, id)
        XCTAssertEqual(restored?.title, "Chicken Adobo")
        let restoredRefs = try await db.crossRefs(id)
        XCTAssertEqual(Set(restoredRefs.map(\.listId)), [1, 2])
        XCTAssertEqual(Set(restoredRefs.map(\.addedAt)), [500, 600])
    }

    func testRestoreSurvivesAListDeletedWhileTheRecipeWasGone() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100, title: "Chicken Adobo"))
        let favorites = try await db.favoritesId()
        let lunch = try await db.listId(named: "Lunch")
        try await db.putInList(id, favorites, at: 500)
        try await db.putInList(id, lunch, at: 600)
        let row = try await db.get(id)!
        let refs = try await db.crossRefs(id)
        try await db.write { try RecipeDao(db: $0).delete(id) }

        // Lunch goes away before Undo is pressed.
        try await db.write { try ListDao(db: $0).delete(id: lunch) }
        try await db.write { try RecipeDao(db: $0).restore(row, crossRefs: refs) }

        let restored = try await db.get(id)
        XCTAssertEqual(restored?.title, "Chicken Adobo")
        let restoredRefs = try await db.crossRefs(id)
        XCTAssertEqual(restoredRefs.map(\.listId), [favorites])
        XCTAssertEqual(restoredRefs.map(\.addedAt), [500])
    }

    func testRestoringARecipeWithNoListMembershipRestoresJustTheRow() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        let row = try await db.get(id)!
        let refs = try await db.crossRefs(id)
        try await db.write { try RecipeDao(db: $0).delete(id) }

        try await db.write { try RecipeDao(db: $0).restore(row, crossRefs: refs) }

        let restored = try await db.get(id)
        let restoredRefs = try await db.crossRefs(id)
        XCTAssertNotNil(restored)
        XCTAssertTrue(restoredRefs.isEmpty)
    }

    func testAFailedWriteRollsBackTheWholeTransaction() async throws {
        let id = try await db.upsert(dataRecipeRecord("https://a.com/1", viewedAt: 100))
        do {
            try await db.write { conn in
                try RecipeDao(db: conn).touch(id, now: 555)
                try conn.run("INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (?, 9999, 1)", id)
            }
            XCTFail("expected a foreign-key failure")
        } catch {}
        let row = try await db.get(id)
        XCTAssertEqual(row?.lastViewedAt, 100, "the touch in the failed transaction was rolled back")
    }
}
