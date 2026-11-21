import XCTest
@testable import RecipeClipper

/// A recipe's tips (#432) through DefaultRecipeRepository over a real in-memory database (Android's
/// DefaultRecipeRepositoryTipsTest and MigrationTest.migration19To20): with `recipeTips` on they are
/// stored apart from the steps; off, a fresh parse's tips are its last steps, as before. A recipe saved
/// with its tips as steps simply refreshes on a re-share.
final class RecipeTipsDataTests: XCTestCase {
    private let url = "https://www.kingarthurbaking.com/recipes/mini-apple-pies-recipe"
    private var db: AppDatabase!
    private var source: DataStubSource!
    private var tipsOn = true
    private var recipes: DefaultRecipeRepository!

    private var pies: Recipe {
        var recipe = Recipe(
            name: "Mini Apple Pies", image: nil, ingredients: ["1 apple"], instructions: ["Bake.", "Cool."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: "24", sourceUrl: url
        )
        recipe.tips = ["Use a baking stone."]
        return recipe
    }

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        source = DataStubSource()
        source.results[url] = .success(pies)
        recipes = DefaultRecipeRepository(
            db: db, source: source, clock: DataTestClock(1_000), sleep: { _ in },
            library: FixedLibraryLimit(limit: .unlimited), tipsOn: { [unowned self] in self.tipsOn }
        )
    }

    override func tearDown() {
        recipes = nil
        db = nil
    }

    private func imported() async throws -> Recipe {
        guard case .success(let recipe) = await recipes.importFromUrl(url, renderedPage: nil) else {
            XCTFail("import failed")
            throw CancellationError()
        }
        return recipe
    }

    func testWithRecipeTipsOnTheTipsAreStoredApartFromTheSteps() async throws {
        let saved = try await imported()
        XCTAssertEqual(saved.instructions, ["Bake.", "Cool."])
        XCTAssertEqual(saved.tips, ["Use a baking stone."])
        let row = try await db.read { try RecipeDao(db: $0).get(saved.id) }
        XCTAssertEqual(row?.tips, ["Use a baking stone."])
    }

    func testWithRecipeTipsOffAFreshParsesTipsAreItsLastStepsAsBefore() async throws {
        tipsOn = false
        let saved = try await imported()
        XCTAssertEqual(saved.instructions, ["Bake.", "Cool.", "Use a baking stone."])
        XCTAssertEqual(saved.tips, [])
    }

    func testARecipeSavedWithItsTipsAsStepsRefreshesOnAReShareItsProgressGoingWithTheOldSteps() async throws {
        tipsOn = false
        let old = try await imported()
        let progress = CookStateJSON.encode(CookProgress(active: true, currentStep: 2))
        try await db.write { try RecipeDao(db: $0).setCookState(old.id, cookState: progress) }

        tipsOn = true
        let refreshed = try await imported()

        XCTAssertEqual(refreshed.id, old.id)
        XCTAssertEqual(refreshed.instructions, ["Bake.", "Cool."])
        XCTAssertEqual(refreshed.tips, ["Use a baking stone."])
        let row = try await db.read { try RecipeDao(db: $0).get(old.id) }
        XCTAssertNil(row?.cookState, "the steps changed, so the step indexes no longer mean the same steps")
    }

    func testCookProgressStaysWhenOnlyTheTipsChange() async throws {
        let first = try await imported()
        let progress = CookStateJSON.encode(CookProgress(active: true, currentStep: 1))
        try await db.write { try RecipeDao(db: $0).setCookState(first.id, cookState: progress) }
        var more = pies
        more.tips = ["Use a baking stone.", "Or a steel."]
        source.results[url] = .success(more)

        let again = try await imported()

        XCTAssertEqual(again.tips, ["Use a baking stone.", "Or a steel."])
        let row = try await db.read { try RecipeDao(db: $0).get(first.id) }
        XCTAssertNotNil(row?.cookState)
    }

    func testAnEditKeepsTheTipsAsTheTipsBoxHoldsThem() async throws {
        let id = try await imported().id
        let opened = await recipes.open(id: id)
        let draft = RecipeDraft.of(try XCTUnwrap(opened))
        XCTAssertEqual(draft.tipsText, "Use a baking stone.")

        var renamed = draft
        renamed.name = "My pies"
        let kept = await recipes.saveEdit(id: id, draft: renamed)
        XCTAssertEqual(kept?.tips, ["Use a baking stone."])

        var cleared = draft
        cleared.tipsText = "  \n"
        let none = await recipes.saveEdit(id: id, draft: cleared)
        XCTAssertEqual(none?.tips, [])
    }

    /// A user_version 18 file (Android's Room 19) gains `tips`, `[]` for every recipe. A recipe saved
    /// with its tips as steps is not rewritten: it keeps all its steps and its cook progress.
    func testAVersion18DatabaseMigratesToVersion19KeepingEveryRecipeAsItWasWithNoTips() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try AppDatabase.migrate(old, upTo: 18)
            try old.execute("""
                INSERT INTO recipes (id, sourceUrl, title, imageUrl, ingredients, instructions, prepTime, cookTime,
                    totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes, uid, language, cookState,
                    servingsTarget, contentOrigin, editedAt, basedOnUid, photoFile) VALUES
                    (7, 'https://www.kingarthurbaking.com/recipes/mini-apple-pies-recipe', 'Mini Apple Pies', NULL,
                     '["1 apple"]', '["Bake.","Cool.","A tip."]', NULL, NULL, NULL, '24', 'BLOG', 123, '[0]', 'Mine',
                     'recipe-uid', 'en', '{"active":true,"currentStep":1}', 12, 'PARSED', NULL, NULL, 'stored.jpg')
                """)
            XCTAssertEqual(try old.queryOne("PRAGMA user_version") { $0.int(0) }, 18)
        }

        let migrated = try AppDatabase(path: path)
        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        XCTAssertEqual(AppDatabase.schemaVersion, 19)
        let kept = try await migrated.read { try XCTUnwrap(RecipeDao(db: $0).get(7)) }
        XCTAssertEqual(kept.instructions, ["Bake.", "Cool.", "A tip."])
        XCTAssertEqual(kept.tips, [])
        XCTAssertEqual(kept.checkedIngredients, [0])
        XCTAssertEqual(kept.notes, "Mine")
        XCTAssertEqual(kept.servingsTarget, 12)
        XCTAssertEqual(kept.photoFile, "stored.jpg")
        XCTAssertEqual(kept.uid, "recipe-uid")
        XCTAssertNotNil(kept.cookState)

        var fresh = kept
        fresh.instructions = ["Bake.", "Cool."]
        fresh.tips = ["A tip."]
        _ = try await migrated.write { try RecipeDao(db: $0).upsert(fresh, limit: .unlimited) }
        let refreshed = try await migrated.read { try XCTUnwrap(RecipeDao(db: $0).get(7)) }
        XCTAssertEqual(refreshed.instructions, ["Bake.", "Cool."])
        XCTAssertEqual(refreshed.tips, ["A tip."])
        XCTAssertNil(refreshed.cookState)
        XCTAssertEqual(refreshed.notes, "Mine")
        XCTAssertEqual(refreshed.photoFile, "stored.jpg")
    }
}
