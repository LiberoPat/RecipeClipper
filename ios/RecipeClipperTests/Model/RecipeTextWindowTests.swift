import XCTest
@testable import RecipeClipper

/// A page's HTML as lines, and which part of it goes to the model (#103). Android's
/// `PageTextReaderTest` and `RecipeTextWindowTest`, expectation for expectation.
final class RecipeTextWindowTests: XCTestCase {

    private let story = (0..<30).map { "A long paragraph of the story, number \($0), about why this cake matters to me." }

    func testEachBlockIsALineInlineMarkupJoinsScriptsAndNavigationAreLeftOut() {
        let page = PageTextReader.read(html: """
            <html lang="de"><head><title>T</title><script>var x = 1;</script></head><body>
            <nav><a href="/">Home</a></nav><h1>Apfel&shy;kuchen</h1>
            <p>Ein <b>schneller</b> Kuchen.<br>Mit&nbsp;Zimt.</p>
            <ul><li><span>200</span> <span>g</span> Mehl</li><li>2 Eier</li></ul>
            <div>lose <div>innen</div> danach</div>
            <footer>© 2024</footer><button>Drucken</button></body></html>
            """, url: "https://example.com/k")
        XCTAssertEqual(page.lines, ["Apfelkuchen", "Ein schneller Kuchen.", "Mit Zimt.", "200 g Mehl", "2 Eier", "lose", "innen", "danach"])
        XCTAssertEqual(page.title, "Apfelkuchen")
        XCTAssertEqual(page.language, "de")
    }

    func testTheTitleFallsBackToOgTitleThenTheTitleElementAndOgImageIsAbsolute() {
        let og = PageTextReader.read(html: """
            <head><title>Site | Soup</title><meta property="og:title" content="Soup">
            <meta property="og:image" content="/img/soup.jpg"></head><body><p>x</p></body>
            """, url: "https://example.com/soup")
        XCTAssertEqual(og.title, "Soup")
        XCTAssertEqual(og.image, "https://example.com/img/soup.jpg")
        XCTAssertEqual(PageTextReader.read(html: "<title>Site | Soup</title><p>x</p>", url: "https://e.com/").title, "Site | Soup")
    }

    func testARealBlogPageWithNoRecipeData() throws {
        let page = PageTextReader.read(html: try pageFixture(), url: "https://blog.example/banana-bread")
        XCTAssertEqual(page.title, "Grandma’s Banana Bread")
        XCTAssertEqual(page.image, "https://blog.example/wp-content/uploads/banana-bread.jpg")
        XCTAssertEqual(page.language, "en-US")
        XCTAssertTrue(page.lines.contains("⅓ cup melted butter"))
        XCTAssertTrue(page.lines.contains("1 ½ cups all-purpose flour"))
        XCTAssertTrue(page.lines.contains("Prep Time: 15 minutes Cook Time: 1 hour"))
        XCTAssertFalse(page.lines.contains { $0.contains("dataLayer") || $0.contains("console") || $0 == "Recipes" || $0.contains("rights reserved") })
    }

    func testTheIngredientsHeadingWithIngredientLinesAfterItWinsOverOneInTheStory() {
        let lines = ["Ingredients you'll need", "Ripe bananas are the secret."] + story +
            ["Banana Bread", "Ingredients", "3 bananas", "1/3 cup butter", "1 egg", "Instructions", "Mash the bananas."]
        XCTAssertEqual(RecipeTextWindow.anchor(lines), lines.firstIndex(of: "Ingredients"))
    }

    func testWithNoHeadingTheDensestRunOfIngredientLines() {
        XCTAssertEqual(RecipeTextWindow.anchor(story + ["200 g Mehl", "2 Eier", "½ TL Salz", "Alles verrühren."]), 30)
    }

    func testThePageTitleLeadsAWindowThatDoesntHoldItAndTheLeadStaysWithinAFifth() throws {
        let lines = story + ["200 g Mehl", "2 Eier", "½ TL Salz", "Alles verrühren."]
        let window = try XCTUnwrap(RecipeTextWindow.window(PageText(title: "Apfelkuchen", lines: lines), maxChars: 500))
        XCTAssertEqual(window.components(separatedBy: "\n"), ["Apfelkuchen", story[29], "200 g Mehl", "2 Eier", "½ TL Salz", "Alles verrühren."])
    }

    func testAPageWithNothingLikeARecipeSendsNothing() {
        XCTAssertNil(RecipeTextWindow.window(PageText(title: "About us", lines: story), maxChars: 5_000))
        XCTAssertNil(RecipeTextWindow.window(PageText(title: "Login", lines: ["Sign in", "Ingredients", "Password"]), maxChars: 5_000))
    }

    func testHeadingsInOtherLanguagesAndShapes() {
        XCTAssertTrue(RecipeTextWindow.isHeading("INGREDIENTS:"))
        XCTAssertTrue(RecipeTextWindow.isHeading("Zutaten für 4 Personen"))
        XCTAssertTrue(RecipeTextWindow.isHeading("材料（2人分）"))
        XCTAssertTrue(RecipeTextWindow.isHeading("Modo de preparo"))
        XCTAssertFalse(RecipeTextWindow.isHeading("Ingredientsnotaword"))
        XCTAssertFalse(RecipeTextWindow.isHeading("The ingredients " + String(repeating: "x", count: 60)))
    }

    func testTheWindowKeepsTheCardsLeadLinesAndFitsTheBudget() throws {
        let page = PageTextReader.read(html: try pageFixture(), url: "https://blog.example/b")
        let window = try XCTUnwrap(RecipeTextWindow.window(page, maxChars: 1_000))
        let lines = window.components(separatedBy: "\n")
        XCTAssertTrue(lines.contains("Grandma’s Banana Bread"))
        XCTAssertLessThanOrEqual(window.utf16.count, 1_000)
        XCTAssertTrue(lines.contains("Servings: 10 slices"))
        XCTAssertTrue(lines.contains("Ingredients") && lines.contains("1 ½ cups all-purpose flour"))
        XCTAssertFalse(lines.contains { $0.hasPrefix("There is something") })
        XCTAssertTrue(try XCTUnwrap(RecipeTextWindow.window(page, maxChars: 4_000)).components(separatedBy: "\n").contains("Mix in the flour."))
    }
}

/// The shared page fixture (shared/fixtures/pages), as the Android tests read it.
func pageFixture(_ name: String = "blog-no-recipe-data") throws -> String {
    let bundle = Bundle(for: RecipeTextWindowTests.self)
    let url = try XCTUnwrap(bundle.url(forResource: name, withExtension: "html", subdirectory: "fixtures/pages"))
    return try String(contentsOf: url, encoding: .utf8)
}
