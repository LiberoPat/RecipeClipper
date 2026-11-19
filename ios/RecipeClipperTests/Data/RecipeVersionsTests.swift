import Combine
import XCTest
@testable import RecipeClipper

/// Recipe versions (#354) through DefaultRecipeRepository over a real in-memory database
/// (Android's RecipeVersionsRepositoryTest and RecipeDaoTest's versions): JSON from a chat whose
/// `isBasedOn` names a recipe here is saved linked to it by uid; anything else is a plain chat
/// recipe; deleting the original clears the link and keeps the version, and an undo links it again.
final class RecipeVersionsTests: XCTestCase {
    private let soupUrl = "https://example.com/soup"
    private var db: AppDatabase!
    private var source: DataStubSource!
    private var versionsOn = true
    private var library = FixedLibraryLimit(limit: .unlimited)
    private var recipes: DefaultRecipeRepository!

    private let spicier = RecipeDraft(
        name: "Spicier soup", ingredientsText: "1 onion\n1 chilli", instructionsText: "Cook.\nAdd the chilli."
    )

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        source = DataStubSource()
        source.results[soupUrl] = .success(dataRecipe(soupUrl))
        makeRepository()
    }

    private func makeRepository() {
        recipes = DefaultRecipeRepository(
            db: db, source: source, clock: DataTestClock(1_000), sleep: { _ in }, library: library,
            versionsOn: { [unowned self] in self.versionsOn }
        )
    }

    override func tearDown() {
        recipes = nil
        db = nil
    }

    private func original() async -> Recipe {
        guard case .success(let recipe) = await recipes.importFromUrl(soupUrl) else {
            XCTFail("import failed")
            return dataRecipe(soupUrl)
        }
        return recipe
    }

    private func row(_ id: Int64) async throws -> RecipeRecord? { try await db.read { try RecipeDao(db: $0).get(id) } }

    private func uid(_ id: Int64) async throws -> String? { try await row(id)?.uid }

    private func first<T>(_ publisher: AnyPublisher<T, Never>) async -> T {
        await publisher.values.first { _ in true }!
    }

    func testAChatRecipeWhoseIsBasedOnIsASavedLinkIsAVersionOfIt() async throws {
        let soup = await original()
        let version = await recipes.addManual(
            draft: spicier, language: nil, notes: nil, fromChat: true, basedOn: soupUrl
        )!

        XCTAssertEqual(version.origin, .chat)
        let soupUid = try await uid(soup.id)
        let basedOnUid = try await row(version.id)?.basedOnUid
        XCTAssertEqual(basedOnUid, soupUid)
        let original = await first(recipes.observeBasedOn(id: version.id))
        XCTAssertEqual(original, RecipeLink(id: soup.id, title: "Soup"))
        let versions = await first(recipes.observeVersions(id: soup.id))
        XCTAssertEqual(versions, [RecipeLink(id: version.id, title: "Spicier soup")])
        let none = await first(recipes.observeBasedOn(id: soup.id))
        XCTAssertNil(none, "the original is not a version of anything")
    }

    func testATypedRecipeIsFoundByItsManualLink() async throws {
        let typed = await recipes.addManual(draft: RecipeDraft(name: "Toast", ingredientsText: "Bread"))!
        let version = await recipes.addManual(
            draft: spicier, language: nil, notes: nil, fromChat: true, basedOn: typed.sourceUrl
        )!

        let basedOnUid = try await row(version.id)?.basedOnUid
        let typedUid = try await uid(typed.id)
        XCTAssertEqual(basedOnUid, typedUid)
    }

    func testALinkTheAICleanedOrDirtiedDifferentlyStillFindsTheRecipe() async throws {
        let soup = await original()
        let version = await recipes.addManual(
            draft: spicier, language: nil, notes: nil, fromChat: true,
            basedOn: "  http://EXAMPLE.com/soup?utm_source=chat#top "
        )!

        let basedOnUid = try await row(version.id)?.basedOnUid
        let soupUid = try await uid(soup.id)
        XCTAssertEqual(basedOnUid, soupUid)
    }

    func testAnIsBasedOnThatMatchesNothingIsIgnored() async throws {
        _ = await original()
        let version = await recipes.addManual(
            draft: spicier, language: nil, notes: nil, fromChat: true, basedOn: "https://example.com/stew"
        )!

        let basedOnUid = try await row(version.id)?.basedOnUid
        XCTAssertNil(basedOnUid)
        XCTAssertEqual(version.origin, .chat)
    }

    func testNoClaimABlankClaimOrTextThatDidNotComeFromAChatLinksNothing() async throws {
        _ = await original()
        let none = await recipes.addManual(draft: spicier, language: nil, notes: nil, fromChat: true, basedOn: nil)!
        let blank = await recipes.addManual(
            draft: RecipeDraft(name: "B", ingredientsText: "x"), language: nil, notes: nil, fromChat: true, basedOn: "   "
        )!
        let plain = await recipes.addManual(
            draft: RecipeDraft(name: "C", ingredientsText: "x"), language: nil, notes: nil, fromChat: false, basedOn: soupUrl
        )!

        for recipe in [none, blank, plain] {
            let basedOnUid = try await row(recipe.id)?.basedOnUid
            XCTAssertNil(basedOnUid, recipe.name)
        }
    }

    func testWithTheFlagOffTheLinkIsNotSaved() async throws {
        _ = await original()
        versionsOn = false
        let version = await recipes.addManual(
            draft: spicier, language: nil, notes: nil, fromChat: true, basedOn: soupUrl
        )!

        let basedOnUid = try await row(version.id)?.basedOnUid
        XCTAssertNil(basedOnUid)
    }

    func testAVersionCountsForTheFreeLimitLikeAnyRecipe() async {
        library = FixedLibraryLimit(limit: .capped(max: 2))
        makeRepository()
        _ = await original()
        let first = await recipes.addManual(draft: spicier, language: nil, notes: nil, fromChat: true, basedOn: soupUrl)!
        XCTAssertGreaterThan(first.id, 0)

        let third = await recipes.addManual(
            draft: RecipeDraft(name: "Third", ingredientsText: "x"), language: nil, notes: nil, fromChat: true, basedOn: soupUrl
        )!
        XCTAssertEqual(third.id, 0, "not kept at the limit")
        let count = try? await db.read { try RecipeDao(db: $0).count() }
        XCTAssertEqual(count, 2)
    }

    func testDeletingTheOriginalKeepsTheVersionAndClearsItsLink() async throws {
        let soup = await original()
        let version = await recipes.addManual(draft: spicier, language: nil, notes: nil, fromChat: true, basedOn: soupUrl)!

        _ = await recipes.delete(id: soup.id)

        let kept = try await row(version.id)
        XCTAssertEqual(kept?.title, "Spicier soup")
        XCTAssertNil(kept?.basedOnUid)
        let original = await first(recipes.observeBasedOn(id: version.id))
        XCTAssertNil(original)
    }

    func testUndoingTheDeleteOfTheOriginalLinksItsVersionsAgain() async throws {
        let soup = await original()
        let version = await recipes.addManual(draft: spicier, language: nil, notes: nil, fromChat: true, basedOn: soupUrl)!
        let deleted = await recipes.delete(id: soup.id)!
        let unlinked = try await row(version.id)?.basedOnUid
        XCTAssertNil(unlinked)

        let outcome = await recipes.restore(deleted)

        XCTAssertEqual(outcome, .restored)
        let basedOnUid = try await row(version.id)?.basedOnUid
        XCTAssertEqual(basedOnUid, deleted.uid)
        let versions = await first(recipes.observeVersions(id: soup.id))
        XCTAssertEqual(versions, [RecipeLink(id: version.id, title: "Spicier soup")])
    }

    func testDeletingAVersionLeavesItsOriginal() async throws {
        let soup = await original()
        let version = await recipes.addManual(draft: spicier, language: nil, notes: nil, fromChat: true, basedOn: soupUrl)!

        _ = await recipes.delete(id: version.id)

        let remaining = try await row(soup.id)
        XCTAssertNotNil(remaining)
        let versions = await first(recipes.observeVersions(id: soup.id))
        XCTAssertEqual(versions, [])
    }

    func testEditingAVersionOrItsOriginalKeepsTheLink() async throws {
        let soup = await original()
        let version = await recipes.addManual(draft: spicier, language: nil, notes: nil, fromChat: true, basedOn: soupUrl)!

        _ = await recipes.saveEdit(id: version.id, draft: RecipeDraft(name: "Spicy soup", ingredientsText: "1 onion\n1 chilli"))
        _ = await recipes.saveEdit(id: soup.id, draft: RecipeDraft(name: "Plain soup", ingredientsText: "1 onion"))

        let basedOnUid = try await row(version.id)?.basedOnUid
        let soupUid = try await uid(soup.id)
        XCTAssertEqual(basedOnUid, soupUid)
        let original = await first(recipes.observeBasedOn(id: version.id))
        XCTAssertEqual(original, RecipeLink(id: soup.id, title: "Plain soup"))
        let versions = await first(recipes.observeVersions(id: soup.id))
        XCTAssertEqual(versions.map(\.title), ["Spicy soup"])
    }

    func testAChangeOfAVersionIsAVersionOfThatVersion() async throws {
        let soup = await original()
        let first = await recipes.addManual(draft: spicier, language: nil, notes: nil, fromChat: true, basedOn: soupUrl)!
        let second = await recipes.addManual(
            draft: RecipeDraft(name: "Even spicier", ingredientsText: "3 chillies"), language: nil, notes: nil,
            fromChat: true, basedOn: first.sourceUrl
        )!

        let basedOnUid = try await row(second.id)?.basedOnUid
        let firstUid = try await uid(first.id)
        XCTAssertEqual(basedOnUid, firstUid)
        let ofSoup = await self.first(recipes.observeVersions(id: soup.id))
        XCTAssertEqual(ofSoup.map(\.id), [first.id])
        let ofFirst = await self.first(recipes.observeVersions(id: first.id))
        XCTAssertEqual(ofFirst.map(\.id), [second.id])
    }

    // MARK: The SQL

    func testTheOldCleanUpRemovingAnOriginalClearsItsVersionsLinks() async throws {
        let soupId = try await db.write { try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/soup", viewedAt: 1), historyLimit: 1) }
        let soupUid = try await uid(soupId)
        var version = dataRecipeRecord("manual:1", viewedAt: 5)
        version.contentOrigin = "CHAT"
        version.basedOnUid = soupUid
        let versionId = try await db.write { try RecipeDao(db: $0).upsert(version, historyLimit: 1) }

        // A newer unprotected recipe pushes the older soup out of a history of one.
        _ = try await db.write { try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/other", viewedAt: 20), historyLimit: 1) }

        let gone = try await row(soupId)
        XCTAssertNil(gone)
        let kept = try await row(versionId)
        XCTAssertNotNil(kept)
        XCTAssertNil(kept?.basedOnUid)
    }

    func testAReShareAnEditAndARefreshKeepAVersionsLink() async throws {
        let soupId = try await db.write { try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/soup", viewedAt: 1), historyLimit: 50) }
        let soupUid = try await uid(soupId)
        var version = dataRecipeRecord("manual:1", viewedAt: 5)
        version.contentOrigin = "CHAT"
        version.basedOnUid = soupUid
        let versionId = try await db.write { try RecipeDao(db: $0).upsert(version, historyLimit: 50) }

        // The version re-shared (only a view), edited, and the original refreshed from its source.
        var again = dataRecipeRecord("manual:1", viewedAt: 9)
        again.contentOrigin = "CHAT"
        _ = try await db.write { try RecipeDao(db: $0).upsert(again, historyLimit: 50) }
        _ = try await db.write {
            try RecipeDao(db: $0).saveEdit(versionId, edited: dataRecipeRecord("manual:1", viewedAt: 9, title: "Hotter"), origin: "CHAT", editedAt: 7)
        }
        _ = try await db.write { try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/soup", viewedAt: 12, title: "Soup, updated"), historyLimit: 50) }

        let kept = try await row(versionId)
        XCTAssertEqual(kept?.basedOnUid, soupUid)
        XCTAssertEqual(kept?.title, "Hotter")
        let versions = try await db.read { try RecipeDao(db: $0).versions(of: soupId) }
        XCTAssertEqual(versions.map(\.id), [versionId])
    }

    func testAVersion15DatabaseMigratesToVersion16KeepingEveryRecipeWithNoLink() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try old.execute("PRAGMA foreign_keys = ON")
            try AppDatabase.migrate(old, upTo: 15)
            try old.execute("""
                INSERT INTO recipes (id, sourceUrl, title, imageUrl, ingredients, instructions, prepTime, cookTime,
                    totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes, uid, language, cookState,
                    servingsTarget, contentOrigin, editedAt) VALUES
                    (7, 'https://example.com/a', 'Adobo', NULL, '["1 cup soy sauce"]', '["Simmer."]', NULL, NULL, NULL,
                     '4', 'BLOG', 123, '[]', NULL, 'recipe-uid', 'en', NULL, NULL, 'PARSED', NULL)
                """)
            XCTAssertEqual(try old.queryOne("PRAGMA user_version") { $0.int(0) }, 15)
        }

        let migrated = try AppDatabase(path: path)
        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        XCTAssertEqual(AppDatabase.schemaVersion, 16)
        let adobo = try await migrated.read { try RecipeDao(db: $0).get(7) }
        XCTAssertEqual(adobo?.title, "Adobo")
        XCTAssertEqual(adobo?.uid, "recipe-uid")
        XCTAssertNil(adobo?.basedOnUid)

        var spicy = dataRecipeRecord("manual:v1", viewedAt: 5, title: "Spicier adobo")
        spicy.contentOrigin = "CHAT"
        spicy.basedOnUid = "recipe-uid"
        let spicyId = try await migrated.write { try RecipeDao(db: $0).upsert(spicy, limit: .unlimited) }
        let links = try await migrated.read { try RecipeDao(db: $0).versions(of: 7) }
        XCTAssertEqual(links, [RecipeLink(id: spicyId, title: "Spicier adobo")])
        let original = try await migrated.read { try RecipeDao(db: $0).original(of: spicyId) }
        XCTAssertEqual(original, RecipeLink(id: 7, title: "Adobo"))
        try await migrated.write { try RecipeDao(db: $0).deleteWithLinks(7) }
        let cleared = try await migrated.read { try RecipeDao(db: $0).get(spicyId) }
        XCTAssertNil(cleared?.basedOnUid)
    }
}
