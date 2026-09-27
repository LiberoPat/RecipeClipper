import XCTest
@testable import RecipeClipper

/// Editing and typing in recipes (#29) through DefaultRecipeRepository over a real in-memory
/// database (Android's DefaultRecipeRepositoryEditTest): the user's version is never refreshed
/// by a re-share, "Update from source" replaces it explicitly, and a manual recipe gets a
/// `manual:` link.
final class DataEditTests: XCTestCase {
    private let url = "https://example.com/soup"
    private var db: AppDatabase!
    private var clock: DataTestClock!
    private var source: DataStubSource!
    private var recipes: DefaultRecipeRepository!

    private let edit = RecipeDraft(
        name: "Grandma's soup",
        ingredientsText: "1 onion\n\n  2 carrots  \n",
        instructionsText: "Chop.\nSimmer."
    )

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        clock = DataTestClock(1_000)
        source = DataStubSource()
        source.results[url] = .success(dataRecipe(url))
        recipes = DefaultRecipeRepository(db: db, source: source, clock: clock, sleep: { _ in })
    }

    override func tearDown() {
        recipes = nil
        db = nil
    }

    private func imported() async -> Recipe {
        guard case .success(let recipe) = await recipes.importFromUrl(url) else {
            XCTFail("import failed")
            return dataRecipe(url)
        }
        return recipe
    }

    func testSavingAnEditMakesAParsedRecipeEditedStampedWithTheNewContent() async {
        let id = await imported().id
        clock.time = 2_000

        let saved = await recipes.saveEdit(id: id, draft: edit)

        XCTAssertEqual(saved?.origin, .edited)
        XCTAssertEqual(saved?.editedAt, 2_000)
        XCTAssertEqual(saved?.name, "Grandma's soup")
        XCTAssertEqual(saved?.ingredients, ["1 onion", "2 carrots"])
        XCTAssertEqual(saved?.instructions, ["Chop.", "Simmer."])
        XCTAssertEqual(saved?.sourceUrl, url)
        XCTAssertNil(saved?.yield)
    }

    func testAnEditThatIsNotARecipeIsRefused() async throws {
        let id = await imported().id

        let noSteps = await recipes.saveEdit(id: id, draft: RecipeDraft(name: "Soup"))
        let noName = await recipes.saveEdit(id: id, draft: RecipeDraft(ingredientsText: "1 onion"))

        XCTAssertNil(noSteps)
        XCTAssertNil(noName)
        let row = try await db.get(id)
        XCTAssertEqual(row?.contentOrigin, "PARSED")
    }

    func testReSharingAnEditedRecipeOpensItWithoutFetchingAndKeepsTheEdit() async throws {
        let id = await imported().id
        _ = await recipes.saveEdit(id: id, draft: edit)
        source.results[url] = .success(dataRecipe(url, title: "Soup, updated by the site"))
        clock.time = 5_000

        let result = await recipes.importFromUrl(url + "?utm_source=x")

        XCTAssertEqual(source.fetched.count, 1)
        guard case .success(let recipe) = result else { return XCTFail("expected the saved edit") }
        XCTAssertEqual(recipe.name, "Grandma's soup")
        XCTAssertEqual(recipe.lastViewedAt, 5_000)
        let row = try await db.get(id)
        XCTAssertEqual(row?.lastViewedAt, 5_000)
    }

    func testReSharingAnUneditedRecipeStillRefreshesIt() async throws {
        let id = await imported().id
        source.results[url] = .success(dataRecipe(url, title: "Soup, updated by the site"))

        _ = await recipes.importFromUrl(url)

        let row = try await db.get(id)
        XCTAssertEqual(row?.title, "Soup, updated by the site")
    }

    func testUpdateFromSourceReplacesTheEditKeepsIdAndNoteAndIsParsedAgain() async {
        let id = await imported().id
        await recipes.setNotes(id: id, notes: "Less salt")
        _ = await recipes.saveEdit(id: id, draft: edit)
        source.results[url] = .success(dataRecipe(url, title: "Soup, updated by the site"))

        let result = await recipes.updateFromSource(id: id)

        guard case .success(let recipe) = result else { return XCTFail("expected success") }
        XCTAssertEqual(recipe.id, id)
        XCTAssertEqual(recipe.name, "Soup, updated by the site")
        XCTAssertEqual(recipe.origin, .parsed)
        XCTAssertNil(recipe.editedAt)
        XCTAssertEqual(recipe.notes, "Less salt")
    }

    func testAFailedUpdateFromSourceKeepsTheEdit() async throws {
        let id = await imported().id
        _ = await recipes.saveEdit(id: id, draft: edit)
        source.results[url] = .error(.offline)

        let result = await recipes.updateFromSource(id: id)

        XCTAssertEqual(result, .error(.offline))
        let row = try await db.get(id)
        XCTAssertEqual(row?.title, "Grandma's soup")
        XCTAssertEqual(row?.contentOrigin, "EDITED")
    }

    func testAManualRecipeGetsAManualLinkIsManualAndIsNeverFetched() async {
        let recipe = await recipes.addManual(draft: edit)

        XCTAssertNotNil(recipe)
        XCTAssertTrue(ManualRecipe.isManual(recipe?.sourceUrl ?? ""))
        XCTAssertEqual(recipe?.origin, .manual)
        XCTAssertEqual(recipe?.editedAt, 1_000)
        XCTAssertEqual(recipe?.canUpdateFromSource, false)
        let update = await recipes.updateFromSource(id: recipe!.id)
        XCTAssertEqual(update, .error(.nothingToShow))
        XCTAssertTrue(source.fetched.isEmpty)
    }

    func testTwoManualRecipesAreTwoRows() async {
        let a = await recipes.addManual(draft: edit)
        let b = await recipes.addManual(draft: edit)

        XCTAssertNotEqual(a?.id, b?.id)
        XCTAssertNotEqual(a?.sourceUrl, b?.sourceUrl)
    }

    func testEditingAManualRecipeKeepsItManual() async {
        let id = await recipes.addManual(draft: edit)!.id
        clock.time = 9_000

        var changed = edit
        changed.name = "Grandma's soup, v2"
        let saved = await recipes.saveEdit(id: id, draft: changed)

        XCTAssertEqual(saved?.origin, .manual)
        XCTAssertEqual(saved?.editedAt, 9_000)
    }
}
