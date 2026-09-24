import XCTest
@testable import RecipeClipper

/// Saving a hand-clipped recipe (#37) through DefaultRecipeRepository over a real in-memory
/// database (Android's DefaultRecipeRepositoryClipTest): it is CLIPPED, keyed on the cleaned
/// link, a re-share opens it without a fetch, History marks it, and "Update from source"
/// replaces it only when the page now has recipe data.
final class DataClipTests: XCTestCase {
    private let url = "https://example.com/cookies"
    private var db: AppDatabase!
    private var clock: DataTestClock!
    private var source: DataStubSource!
    private var recipes: DefaultRecipeRepository!

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        clock = DataTestClock(1_000)
        source = DataStubSource()
        source.results[url] = .error(.noRecipeFound)
        recipes = DefaultRecipeRepository(db: db, source: source, clock: clock, sleep: { _ in })
    }

    override func tearDown() {
        recipes = nil
        db = nil
    }

    private func clip(_ name: String = "Oat Cookies", link: String? = nil) -> Recipe {
        Recipe(
            name: name, image: "https://img.example/c.jpg", ingredients: ["1 cup oats"],
            instructions: ["Bake."], prepTime: nil, cookTime: nil, totalTime: nil, yield: nil,
            sourceUrl: link ?? "\(url)?utm_source=x#jump"
        )
    }

    private func saved(_ recipe: Recipe? = nil) async throws -> Recipe {
        guard case .success(let saved) = await recipes.saveClip(recipe ?? clip()) else {
            XCTFail("clip save failed")
            throw CancellationError()
        }
        return saved
    }

    func testAClipIsSavedClippedUnderTheCleanedLinkAsAView() async throws {
        let recipe = try await saved()
        XCTAssertEqual(recipe.origin, .clipped)
        XCTAssertNil(recipe.editedAt)
        XCTAssertEqual(recipe.sourceUrl, url)
        XCTAssertEqual(recipe.lastViewedAt, 1_000)
    }

    func testReSharingAClippedLinkOpensTheClipWithoutAFetch() async throws {
        let id = try await saved().id
        clock.time = 5_000

        guard case .success(let recipe) = await recipes.importFromUrl(url) else {
            return XCTFail("re-share failed")
        }
        XCTAssertEqual(source.fetched, [])
        XCTAssertEqual(recipe.id, id)
        XCTAssertEqual(recipe.name, "Oat Cookies")
    }

    func testClippingTheSameLinkAgainReplacesTheClipInPlaceNoteKept() async throws {
        let id = try await saved().id
        await recipes.setNotes(id: id, notes: "Less sugar")

        let again = try await saved(clip("Brown Butter Oat Cookies"))
        XCTAssertEqual(again.id, id)
        XCTAssertEqual(again.name, "Brown Butter Oat Cookies")
        XCTAssertEqual(again.notes, "Less sugar")
    }

    func testHistorySaysARowIsClipped() async throws {
        _ = try await saved()
        let history = await firstValue(recipes.observeHistory(query: ""))
        XCTAssertEqual(history?.map(\.isClipped), [true])
    }

    func testAClipCanBeUpdatedFromSourceAndAFailureLeavesItAsItIs() async throws {
        let recipe = try await saved()
        XCTAssertTrue(recipe.canUpdateFromSource)

        let failed = await recipes.updateFromSource(id: recipe.id)
        XCTAssertEqual(failed, .error(.noRecipeFound))
        let kept = await recipes.open(id: recipe.id)
        XCTAssertEqual(kept?.origin, .clipped)
        XCTAssertEqual(kept?.name, "Oat Cookies")

        source.results[url] = .success(clip("Cookies, from the site", link: url))
        guard case .success(let updated) = await recipes.updateFromSource(id: recipe.id) else {
            return XCTFail("update failed")
        }
        XCTAssertEqual(updated.origin, .parsed)
        XCTAssertEqual(updated.name, "Cookies, from the site")
    }
}
