import XCTest
@testable import RecipeClipper

/// A recipe written inside an article (#435), over the shared El Periódico page through the real
/// parsers and an in-memory database: offered for the cook to check, never saved by the import.
/// Android's `DefaultRecipeRepositoryArticleTest`.
final class DataArticleTests: XCTestCase {
    private let url = "https://recetas.elperiodico.com/articulo-mejillones-79292.html"
    private var db: AppDatabase!
    private var source: PageSource!
    private var flagOn = true
    private var recipes: DefaultRecipeRepository!

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

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        source = PageSource(html: try pageFixture("article-elperiodico-mejillones"))
        recipes = DefaultRecipeRepository(
            db: db, source: source, clock: DataTestClock(1_000), sleep: { _ in },
            articleOn: { [unowned self] in self.flagOn }
        )
    }

    private func rows() async throws -> Int {
        try await db.read { try RecipeDao(db: $0).findByUrl(self.url) } == nil ? 0 : 1
    }

    func testThePagesArticleRecipeIsOfferedAndNothingIsSaved() async throws {
        let result = await recipes.importFromUrl(url + "?utm_source=x", renderedPage: nil)
        guard case .error(.inArticle(let recipe)) = result else { return XCTFail("expected inArticle, got \(result)") }
        XCTAssertEqual(recipe.ingredients.count, 6)
        XCTAssertEqual(recipe.steps.count, 6)
        XCTAssertEqual(recipe.sourceUrl, url)
        XCTAssertEqual(recipe.language, "es")
        let saved = try await rows()
        XCTAssertEqual(saved, 0)
    }

    func testSafarisPageIsReadWhenTheFetchHasNoArticle() async throws {
        source.next = .error(.noRecipeFound)
        let result = await recipes.importFromUrl(url, renderedPage: try pageFixture("article-elperiodico-mejillones"))
        guard case .error(.inArticle(let recipe)) = result else { return XCTFail("expected inArticle, got \(result)") }
        XCTAssertEqual(recipe.steps.count, 6)
    }

    func testTheFlagOffIsNoRecipeFoundAsBefore() async {
        flagOn = false
        let result = await recipes.importFromUrl(url, renderedPage: nil)
        XCTAssertEqual(result, .error(.noRecipeFound))
    }
}
