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
        guard case .error(.inArticle(let recipes)) = result, let recipe = recipes.first, recipes.count == 1 else {
            return XCTFail("expected one inArticle, got \(result)")
        }
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
        guard case .error(.inArticle(let recipes)) = result else { return XCTFail("expected inArticle, got \(result)") }
        XCTAssertEqual(recipes.map(\.steps.count), [6])
    }

    func testSeveralAreEachOfferedUnderTheirOwnLinkTheFirstUnderThePages() async throws {
        source.next = nil
        let page = try pageFixture("article-two-recipes")
        let several = DefaultRecipeRepository(
            db: db, source: PageSource(html: page), clock: DataTestClock(1_000), sleep: { _ in }, articleOn: { true }
        )
        guard case .error(.inArticle(let recipes)) = await several.importFromUrl(url, renderedPage: nil) else { return XCTFail("expected inArticle") }
        XCTAssertEqual(recipes.map(\.sourceUrl), [url, url + "?pith_recipe=2"])

        // Saved, the second is found by its own link, for the chooser.
        var second = Recipe(
            name: recipes[1].name, image: nil, ingredients: recipes[1].ingredients, instructions: recipes[1].steps,
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: recipes[1].sourceUrl
        )
        second.id = 0
        guard case .success(let saved) = await several.saveClip(second) else { return XCTFail("not saved") }
        let ids = await several.savedIds(links: recipes.map(\.sourceUrl))
        XCTAssertEqual(ids, [recipes[1].sourceUrl: saved.id])
    }

    // Past Cloudflare's check in the visible view (#220): the page it already has, read the same way.

    func testPastTheCheckOneRecipeInTheArticleIsOffered() async throws {
        source.next = .error(.blocked(httpStatus: 403))
        let result = await recipes.importPage(url, html: try pageFixture("article-elperiodico-mejillones"))
        guard case .error(.inArticle(let found)) = result else { return XCTFail("expected inArticle, got \(result)") }
        XCTAssertEqual(found.count, 1)
        let saved = try await rows()
        XCTAssertEqual(saved, 0)
    }

    func testPastTheCheckSeveralAreOffered() async throws {
        let result = await recipes.importPage(url, html: try pageFixture("article-two-recipes"))
        guard case .error(.inArticle(let found)) = result else { return XCTFail("expected inArticle, got \(result)") }
        XCTAssertEqual(found.map(\.name), ["Smoky hummus", "Herb yogurt dip"])
    }

    func testPastTheCheckNoneIsNoRecipeFoundAsBefore() async throws {
        let result = await recipes.importPage(url, html: try pageFixture("article-prose"))
        XCTAssertEqual(result, .error(.noRecipeFound))
    }

    func testTheFlagOffIsNoRecipeFoundAsBefore() async {
        flagOn = false
        let result = await recipes.importFromUrl(url, renderedPage: nil)
        XCTAssertEqual(result, .error(.noRecipeFound))
    }
}
