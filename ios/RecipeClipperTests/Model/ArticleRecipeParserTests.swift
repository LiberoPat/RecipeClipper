import XCTest
@testable import RecipeClipper

/// A recipe written inside an article (#435): a list of amounts, then the method. The pages are in
/// `shared/fixtures/pages/article-*.html`; Android's `ArticleRecipeParserTest`, case for case.
final class ArticleRecipeParserTests: XCTestCase {

    private let elPeriodico = "https://recetas.elperiodico.com/articulo-los-cocineros-coinciden-no-comas-los-mejillones-" +
        "de-lata-con-patatas-tienen-mas-sabor-y-son-mas-nutritivos-si-los-combinas-con-queso-parmesano-y-tomate-seco-79292.html"

    private func all(_ html: String, url: String = "https://example.com/a") -> [ArticleRecipe] {
        ArticleRecipeParser.parse(html: html, url: url)
    }

    /// The page's one recipe; fails when there are several.
    private func parse(_ html: String, url: String = "https://example.com/a") -> ArticleRecipe? {
        let found = all(html, url: url)
        XCTAssertLessThanOrEqual(found.count, 1)
        return found.first
    }

    func testTheElPeriodicoArticlesListAndMethodParagraphsAsWritten() throws {
        let recipe = try XCTUnwrap(parse(try pageFixture("article-elperiodico-mejillones"), url: elPeriodico))
        // Named by the heading above its list; the page's headline is kept for the chooser.
        XCTAssertEqual(recipe.name, "Cómo convertir una lata de mejillones en escabeche en un aperitivo crujiente y lleno de sabor")
        XCTAssertEqual(
            recipe.headline,
            "Los cocineros coinciden: “No comas los mejillones de lata con patatas, tienen más sabor y son más " +
                "nutritivos si los combinas con queso parmesano y tomate seco”"
        )
        XCTAssertEqual(recipe.ingredients, [
            "1 lata de mejillones en escabeche, preferiblemente grandes.",
            "100-120 gramos de queso parmesano rallado.",
            "1 huevo.",
            "5 o 6 tomates secos conservados en aceite.",
            "Un poco de agua.",
            "Aceite para freír.",
        ])
        XCTAssertEqual(recipe.steps.count, 6)
        XCTAssertEqual(
            recipe.steps.first,
            "Para la preparación, primero rallamos finamente el parmesano y lo mezclamos con el huevo batido hasta " +
                "obtener una masa espesa y manejable. Si queda demasiado líquida, incorporamos un poco más de queso."
        )
        XCTAssertEqual(
            recipe.steps.last,
            "Conviene montar el aperitivo justo antes de servirlo, porque la humedad del tomate y del escabeche puede " +
                "ablandar la superficie crujiente del queso."
        )
        XCTAssertEqual(recipe.steps.filter { $0.hasPrefix("Imagen:") || $0.hasPrefix("Más allá") }.count, 0)
        XCTAssertEqual(recipe.language, "es")
        XCTAssertEqual(
            recipe.image,
            "https://cdn0.recetasgratis.net/es/posts/2/9/2/los_cocineros_coinciden_no_comas_los_mejillones_de_lata_con_patatas_" +
                "tienen_mas_sabor_y_son_mas_nutritivos_si_los_combinas_con_queso_parmesano_y_tomate_seco_79292_1200.jpg"
        )
        XCTAssertEqual(recipe.sourceUrl, elPeriodico)
    }

    func testThePageParserHandsItOverWithNoRecipeFound() throws {
        let fetched = BlogRecipeSource.parsePage(html: try pageFixture("article-elperiodico-mejillones"), url: elPeriodico)
        guard case .error(.noRecipeFound) = fetched.result else { return XCTFail("expected noRecipeFound, got \(fetched.result)") }
        XCTAssertEqual(fetched.articles.map(\.ingredients.count), [6])
    }

    func testAnEnglishArticlesOrderedMethodAStepsHeaderSkippedAndANotesHeaderEndingIt() throws {
        let recipe = try XCTUnwrap(parse(try pageFixture("article-english-ol")))
        XCTAssertEqual(recipe.name, "The lemon bars my neighbour taught me")
        XCTAssertEqual(recipe.ingredients, ["1 cup (2 sticks) butter, softened", "2 cups flour", "½ cup powdered sugar", "4 large eggs", "Zest of 2 lemons"])
        XCTAssertEqual(recipe.steps, [
            "Heat the oven to 350°F.",
            "Press the butter, flour and sugar into a 9x13 pan and bake for 20 minutes.",
            "Whisk the eggs and zest, pour over the crust and bake 25 minutes more.",
        ])
        XCTAssertEqual(recipe.image, "https://cdn.example.com/lemon-bars.jpg")
        XCTAssertEqual(recipe.language, "en")
    }

    func testTheSitesMenuAndFooterListsAreNeverIngredients() throws {
        XCTAssertNil(parse(try pageFixture("article-nav-list")))
    }

    func testAListOfLinksIsNeverIngredients() throws {
        XCTAssertNil(parse(try pageFixture("article-link-list")))
    }

    func testAShortBulletedListOfTipsIsNeverIngredients() throws {
        XCTAssertNil(parse(try pageFixture("article-tips-list")))
    }

    func testProseAloneIsNeverARecipe() throws {
        XCTAssertNil(parse(try pageFixture("article-prose")))
    }

    func testAListOfAmountsWithNoMethodAfterItIsNoRecipe() throws {
        XCTAssertNil(parse(try pageFixture("article-list-only")))
    }

    private func article(_ items: [String], lang: String = "en") -> String {
        "<html lang='\(lang)'><body><article><h1>Soup</h1><ul>" + items.map { "<li>\($0)</li>" }.joined() +
            "</ul><p>Simmer it all for 20 minutes.</p></article></body></html>"
    }

    func testAtLeastThreeItemsMustBeAmounts() {
        XCTAssertNil(parse(article(["2 onions", "1 l stock"])))
        XCTAssertEqual(parse(article(["2 onions", "1 l stock", "3 carrots"]))?.ingredients.count, 3)
    }

    func testMoreThanHalfTheItemsMustBeAmounts() {
        XCTAssertNil(parse(article(["2 onions", "1 l stock", "3 carrots", "Salt", "Pepper", "Parsley"])))
        XCTAssertEqual(parse(article(["2 onions", "1 l stock", "3 carrots", "Salt", "Pepper"]))?.ingredients.count, 5)
    }

    func testAnItemPast200CharactersIsNotAnAmount() {
        let long = "2 " + String(repeating: "very ", count: 45) + "long onions"
        XCTAssertNil(parse(article([long, "1 l stock", "3 carrots"])))
    }

    func testALanguageWithNoTablesFindsNothing() {
        XCTAssertNil(parse(article(["2 uien", "1 l bouillon", "3 wortels"], lang: "nl")))
    }

    func testANestedListIsNotTheList() {
        let html = "<html lang='en'><body><article><h1>Soup</h1><ul><li>Tips<ul><li>2 onions</li><li>1 l stock</li>" +
            "<li>3 carrots</li></ul></li></ul><p>Simmer.</p></article></body></html>"
        XCTAssertNil(parse(html))
    }

    func testTheMethodStopsAtTheNextListAndTheNextHeading() {
        let html = "<html lang='en'><body><article><h1>Soup</h1><ul><li>2 onions</li><li>1 l stock</li><li>3 carrots</li></ul>" +
            "<div><p>Chop everything.</p><p>Simmer for 20 minutes.</p></div><ul><li>1 cup rice</li></ul><p>Not a step.</p>" +
            "</article></body></html>"
        XCTAssertEqual(parse(html)?.steps, ["Chop everything.", "Simmer for 20 minutes."])
        let headed = html.replacingOccurrences(of: "<ul><li>1 cup rice</li></ul>", with: "<h3>Next</h3>")
        XCTAssertEqual(parse(headed)?.steps, ["Chop everything.", "Simmer for 20 minutes."])
    }

    func testAnArticleWithTwoRecipesGivesBothEachNamedByItsHeadingWithItsOwnSteps() throws {
        let recipes = all(try pageFixture("article-two-recipes"))
        XCTAssertEqual(recipes.map(\.name), ["Smoky hummus", "Herb yogurt dip"])
        XCTAssertEqual(recipes.map(\.headline), ["Two dips for a party", "Two dips for a party"])
        XCTAssertEqual(recipes.first?.ingredients.count, 4)
        XCTAssertEqual(recipes.first?.steps, ["Blend everything with a splash of water until smooth.", "Taste, add salt, and finish with more paprika."])
        XCTAssertEqual(recipes.last?.ingredients, ["250 g Greek yogurt", "2 tbsp chopped dill", "1 small garlic clove, grated"])
        XCTAssertEqual(recipes.last?.steps, ["Stir the dill and garlic into the yogurt.", "Chill for 30 minutes."])
        XCTAssertEqual(recipes.last?.image, "https://cdn.example.com/dips.jpg")
    }

    func testAtMostSixRecipesAreRead() {
        let one = "<h2>Soup</h2><ul><li>2 onions</li><li>1 l stock</li><li>3 carrots</li></ul><p>Simmer.</p>"
        XCTAssertEqual(all("<html lang='en'><body><article><h1>Soups</h1>\(String(repeating: one, count: 8))</article></body></html>").count, 6)
    }

    func testAnOrderedListReadAsStepsIsNeverARecipeOfItsOwn() {
        let html = "<html lang='en'><body><article><h1>Soup</h1><ul><li>2 onions</li><li>1 l stock</li><li>3 carrots</li></ul>" +
            "<ol><li>2 minutes to chop.</li><li>20 minutes to simmer.</li><li>5 minutes to rest.</li></ol><p>Serve hot.</p></article></body></html>"
        XCTAssertEqual(all(html).map(\.steps.count), [4])
    }

    func testAStepsHeadingAfterTheListEndsItSoAPlainBlogPageIsLeftToTheModel() throws {
        XCTAssertTrue(all("<html lang='en'><body><article><h1>Soup</h1><ul><li>2 onions</li><li>1 l stock</li><li>3 carrots</li></ul>" +
            "<h2>Method</h2><p>Simmer.</p></article></body></html>").isEmpty)
        XCTAssertTrue(all(try pageFixture("blog-no-recipe-data")).isEmpty)
    }

    func testNoHeadingNoNameNoRecipe() {
        XCTAssertNil(parse("<html lang='en'><body><ul><li>2 onions</li><li>1 l stock</li><li>3 carrots</li></ul><p>Simmer.</p></body></html>"))
    }
}
