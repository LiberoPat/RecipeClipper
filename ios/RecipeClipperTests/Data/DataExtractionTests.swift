import XCTest
@testable import RecipeClipper

/// A recipe picked from the page's text when the page has no recipe data (#103), over the
/// shared page fixture through the real parsers, a fake model and an in-memory database.
/// Android's `DefaultRecipeRepositoryExtractionTest`, case for case.
final class DataExtractionTests: XCTestCase {
    private let url = "https://blog.example/banana-bread"
    private var db: AppDatabase!
    private var source: PageSource!
    private var model: FakePageRecipeExtractor!
    private var flagOn = true
    private var recipes: DefaultRecipeRepository!

    /// The fixture page as fetched: no recipe data, so noRecipeFound with its text; `next` once set.
    private final class PageSource: RecipeSource {
        let html: String
        var next: ParseResult?
        init(html: String) { self.html = html }
        func fetch(url: String) async -> ParseResult { await fetchPage(url: url).result }
        func fetchPage(url: String) async -> FetchedPage {
            if let next { return FetchedPage(result: next) }
            return BlogRecipeSource.parsePage(html: html, url: url)
        }
    }

    private let picks = PageSelection(
        name: "Grandma's Banana Bread",
        ingredients: ["3 very ripe bananas, mashed", "1/3 cup melted butter", "1 1/2 cups all-purpose flour"],
        steps: ["Mix in the flour.", "Bake for 55 to 65 minutes, until a tester comes out clean."],
        yield: "10 slices", prepTime: "15 minutes", cookTime: "1 hour"
    )
    private let kept = ["3 very ripe bananas, mashed", "⅓ cup melted butter", "1 ½ cups all-purpose flour"]

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        source = PageSource(html: try pageFixture())
        model = FakePageRecipeExtractor(picks: picks)
        recipes = DefaultRecipeRepository(
            db: db, source: source, clock: DataTestClock(1_000), sleep: { _ in },
            extractor: model, extractionOn: { [unowned self] in self.flagOn }
        )
    }

    private func importPage() async -> ParseResult { await recipes.importFromUrl(url, renderedPage: nil) }

    private func recipe(_ result: ParseResult) throws -> Recipe {
        guard case .success(let recipe) = result else {
            XCTFail("expected a recipe, got \(result)")
            throw CancellationError()
        }
        return recipe
    }

    private let noRecipe = ParseResult.error(.noRecipeFound)

    func testWhatTheModelPicksFromThePageIsSavedInThePagesOwnWords() async throws {
        let recipe = try recipe(await importPage())
        XCTAssertEqual(recipe.name, "Grandma’s Banana Bread")
        XCTAssertEqual(recipe.ingredients, kept)
        XCTAssertEqual(recipe.instructions, picks.steps)
        XCTAssertEqual(recipe.yield, "10 slices")
        XCTAssertEqual(recipe.prepTime, "15m")
        XCTAssertEqual(recipe.cookTime, "1h")
        XCTAssertEqual(recipe.image, "https://blog.example/wp-content/uploads/banana-bread.jpg")
        XCTAssertEqual(recipe.language, "en-us")
        XCTAssertEqual(recipe.origin, .extracted)
        let row = try await db.read { try RecipeDao(db: $0).findByUrl(self.url) }
        XCTAssertEqual(row?.contentOrigin, "EXTRACTED")
        XCTAssertTrue(model.asked.first?.contains("Ingredients\n3 very ripe bananas, mashed") ?? false)
    }

    func testLinesTheModelMadeUpAreDroppedTheRestKept() async throws {
        model.picks?.ingredients = picks.ingredients + ["2 cups chocolate chips", "4 very ripe bananas, mashed"]
        model.picks?.steps = ["Fold in the chocolate chips.", "Mix in the flour."]
        let recipe = try recipe(await importPage())
        XCTAssertEqual(recipe.ingredients, kept)
        XCTAssertEqual(recipe.instructions, ["Mix in the flour."])
    }

    func testNothingVerifiableIsNoRecipeFoundAsBefore() async throws {
        model.picks?.name = "Easy Banana Loaf"
        let renamed = await importPage()
        XCTAssertEqual(renamed, noRecipe)
        model.picks = PageSelection(name: picks.name, ingredients: ["2 cups chocolate chips"], steps: ["Stir well."])
        let invented = await importPage()
        XCTAssertEqual(invented, noRecipe)
        model.picks = nil
        let none = await importPage()
        XCTAssertEqual(none, noRecipe)
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 0)
    }

    func testAnUnsupportedLanguageOrPhoneOrTheFlagOffNeverAsksTheModel() async {
        model.languages = ["de"]
        let unsupported = await importPage()
        XCTAssertEqual(unsupported, noRecipe)
        model.languages = ["en"]
        flagOn = false
        let off = await importPage()
        XCTAssertEqual(off, noRecipe)
        XCTAssertTrue(model.asked.isEmpty)
    }

    func testOnlyAPageThatLoadedWithNoRecipeDataIsRead() async {
        source.next = .error(.blocked(403))
        let blocked = await importPage()
        XCTAssertEqual(blocked, .error(.blocked(403)))
        XCTAssertTrue(model.asked.isEmpty)
    }

    func testAnExtractedRecipeIsTheSourcesSoAReShareRefreshesIt() async throws {
        _ = await importPage()
        source.next = .success(dataRecipe(url, title: "Banana Bread", ingredients: ["3 bananas"]))
        let recipe = try recipe(await importPage())
        XCTAssertEqual(recipe.name, "Banana Bread")
        XCTAssertEqual(recipe.origin, .parsed)
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 1)
    }
}
