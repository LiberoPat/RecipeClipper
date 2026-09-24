import XCTest
@testable import RecipeClipper

/// Port of Android's MicrodataRecipeParserTest: the same pages, the same expected results.
/// `jetpack` has the structure of a Smitten Kitchen page (WordPress's Jetpack recipe block, as
/// served in September 2026) with made-up text: the markup is what matters, and the repo is
/// public.
enum MicrodataFixtures {
    static let jetpackUrl = "https://blog.example/2026/09/tomato-soup/"

    static let jetpack = """
        <!doctype html><html><head>
        <meta property="og:image" content="https://img.example/soup.jpg?fit=1200%2C800&#038;ssl=1">
        </head><body>
        <header><a href="https://blog.example/" itemprop="url">Blog</a></header>
        <article><p>A long story about soup.</p>
        <div class="hrecipe h-recipe jetpack-recipe" itemscope itemtype="https://schema.org/Recipe">
        <h3 class="p-name jetpack-recipe-title" itemprop="name">Tomato Soup with Crispy Onions</h3>
        <ul class="jetpack-recipe-meta">
        <li class="jetpack-recipe-servings" itemprop="recipeYield"><strong>Servings: </strong>4, more as a side</li>
        <li class="jetpack-recipe-time"> <time itemprop="totalTime" datetime="P0DT0H50M0S"><strong>Time:</strong> <span class="time">50 minutes</span></time> </li>
        <li class="jetpack-recipe-print"><a href="#">Print</a></li>
        </ul>
        <div class="jetpack-recipe-content">
        <div class="jetpack-recipe-notes">Make it a day ahead if you like.</div>
        <div class="jetpack-recipe-ingredients"><ul>
        <li class="jetpack-recipe-ingredient" itemprop="recipeIngredient">2 pounds ripe tomatoes, halved</li>
        <li class="jetpack-recipe-ingredient" itemprop="recipeIngredient">1 red onion, thinly sliced</li>
        <li class="jetpack-recipe-ingredient" itemprop="recipeIngredient">Salt &amp; pepper</li>
        </ul></div>
        <div class="jetpack-recipe-directions e-instructions"><strong>Heat oven:</strong> To 350°F.</p>
        <p><strong>Make the soup:</strong> Grate the tomatoes [I use <a href="https://shop.example/grater">this</a>] and simmer for 20 minutes.</p>
        <p>Fry the onion until crisp, about 8 to 10 minutes.</p>
        </div></div></div>
        <p>Comments</p></article></body></html>
        """

    static let genericUrl = "https://bars.example/lemon-bars"

    static let generic = """
        <html><body>
        <div itemscope itemtype="http://schema.org/Recipe">
        <div itemprop="author" itemscope itemtype="http://schema.org/Person"><span itemprop="name">Jane Cook</span></div>
        <h1 itemprop="name">Lemon Bars</h1>
        <img itemprop="image" src="/img/lemon-bars.jpg">
        <meta itemprop="prepTime" content="PT15M">
        <span itemprop="recipeYield">16 bars</span>
        <ul><li itemprop="recipeIngredient">1 cup flour</li><li itemprop="recipeIngredient">2 lemons</li></ul>
        <ol>
        <li itemprop="recipeInstructions" itemscope itemtype="http://schema.org/HowToStep"><span itemprop="text">Heat the oven to 350°F.</span></li>
        <li itemprop="recipeInstructions" itemscope itemtype="http://schema.org/HowToStep"><span itemprop="text">Bake 25 minutes.</span></li>
        </ol>
        </div></body></html>
        """

    static let list = #"<div itemscope itemtype="https://schema.org/Thing https://schema.org/Recipe"><span itemprop="name">Toast</span><div itemprop="recipeInstructions"><ol><li>Slice the bread.</li><li>Toast it.</li></ol></div></div>"#
    static let unclosed = #"<div itemscope itemtype="https://schema.org/Recipe"><b itemprop="name">Pancakes</b><div itemprop="recipeInstructions"><p>Mix.<p>Rest 10 minutes.<p>Fry.</div></div>"#
    static let noName = #"<div itemscope itemtype="https://schema.org/Recipe"><span itemprop="recipeIngredient">1 egg</span></div>"#
    static let nameOnly = #"<div itemscope itemtype="https://schema.org/Recipe"><span itemprop="name">Nothing</span></div>"#
    static let notARecipe = #"<div itemscope itemtype="https://schema.org/Article"><span itemprop="name">News</span><span itemprop="recipeIngredient">1 egg</span></div>"#
    static let deep = #"<div itemscope itemtype="https://schema.org/Recipe"><span itemprop="name">Deep</span><div itemprop="recipeInstructions">"#
        + String(repeating: "<div>", count: 5_000) + "Stir." + String(repeating: "</div>", count: 5_000) + "</div></div>"
}

final class MicrodataRecipeParserTests: XCTestCase {

    private func parse(_ html: String, _ url: String = "https://x.example/r") -> Recipe? {
        MicrodataRecipeParser.parse(html: html, sourceUrl: url)
    }

    func testAJetpackRecipeBlockIsReadStepsFromTheDirectionsDiv() throws {
        let recipe = try XCTUnwrap(parse(MicrodataFixtures.jetpack, MicrodataFixtures.jetpackUrl))

        XCTAssertEqual(recipe.name, "Tomato Soup with Crispy Onions")
        XCTAssertEqual(recipe.ingredients, ["2 pounds ripe tomatoes, halved", "1 red onion, thinly sliced", "Salt & pepper"])
        XCTAssertEqual(recipe.instructions, [
            "Heat oven: To 350°F.", // bare text before the first <p>: the step a per-<p> reading loses
            "Make the soup: Grate the tomatoes [I use this] and simmer for 20 minutes.",
            "Fry the onion until crisp, about 8 to 10 minutes.",
        ])
        XCTAssertEqual(recipe.yield, "Servings: 4, more as a side")
        XCTAssertEqual(recipe.totalTime, "50m")
        XCTAssertNil(recipe.prepTime)
        XCTAssertNil(recipe.cookTime)
        XCTAssertEqual(recipe.image, "https://img.example/soup.jpg?fit=1200%2C800&ssl=1") // og:image, entity decoded
        XCTAssertEqual(recipe.sourceUrl, MicrodataFixtures.jetpackUrl)
    }

    func testTheNotesAndThePageAroundTheBlockAreLeftOut() throws {
        let recipe = try XCTUnwrap(parse(MicrodataFixtures.jetpack, MicrodataFixtures.jetpackUrl))
        let everything = recipe.instructions + recipe.ingredients
        XCTAssertEqual(everything.filter { $0.contains("day ahead") || $0.contains("story") || $0.contains("Comments") }, [])
    }

    func testStandardMicrodataIsReadAndANestedAuthorsNameIsNotTheRecipes() throws {
        let recipe = try XCTUnwrap(parse(MicrodataFixtures.generic, MicrodataFixtures.genericUrl))

        XCTAssertEqual(recipe.name, "Lemon Bars")
        XCTAssertEqual(recipe.image, "https://bars.example/img/lemon-bars.jpg") // relative src made absolute
        XCTAssertEqual(recipe.prepTime, "15m")
        XCTAssertEqual(recipe.yield, "16 bars")
        XCTAssertEqual(recipe.ingredients, ["1 cup flour", "2 lemons"])
        XCTAssertEqual(recipe.instructions, ["Heat the oven to 350°F.", "Bake 25 minutes."])
    }

    func testPlainListStepsSplitOnePerItemAndASecondItemtypeIsFine() throws {
        let recipe = try XCTUnwrap(parse(MicrodataFixtures.list))
        XCTAssertEqual(recipe.name, "Toast")
        XCTAssertEqual(recipe.ingredients, [])
        XCTAssertEqual(recipe.instructions, ["Slice the bread.", "Toast it."])
    }

    func testUnclosedParagraphsAreStillSeparateSteps() throws {
        XCTAssertEqual(try XCTUnwrap(parse(MicrodataFixtures.unclosed)).instructions, ["Mix.", "Rest 10 minutes.", "Fry."])
    }

    func testNoNameNothingToCookOrNoRecipeItemGivesNothing() {
        XCTAssertNil(parse(MicrodataFixtures.noName))
        XCTAssertNil(parse(MicrodataFixtures.nameOnly))
        XCTAssertNil(parse(MicrodataFixtures.notARecipe))
        XCTAssertNil(parse("<html><body><p>Just a story.</p></body></html>"))
    }

    func testDeeplyNestedStepsDontOverflowTheStack() throws {
        XCTAssertEqual(try XCTUnwrap(parse(MicrodataFixtures.deep)).instructions, ["Stir."])
    }

    // MARK: - Through BlogRecipeSource

    func testAPageWithOnlyMicrodataIsReadThroughTheFallback() {
        let result = BlogRecipeSource.parse(html: MicrodataFixtures.jetpack, url: MicrodataFixtures.jetpackUrl)
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.name, "Tomato Soup with Crispy Onions")
    }

    func testJsonLdWinsOverMicrodataOnAPageWithBoth() {
        let html = MicrodataFixtures.jetpack.replacingOccurrences(
            of: "</head>",
            with: #"<script type="application/ld+json">{"@type":"Recipe","name":"From JSON-LD","recipeIngredient":["1 egg"]}</script></head>"#
        )
        guard case .success(let recipe) = BlogRecipeSource.parse(html: html, url: MicrodataFixtures.jetpackUrl) else {
            return XCTFail("no recipe")
        }
        XCTAssertEqual(recipe.name, "From JSON-LD")
    }
}
