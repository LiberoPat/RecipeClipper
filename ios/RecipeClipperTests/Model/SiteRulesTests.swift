import XCTest
@testable import RecipeClipper

/// Site rules as shared data (#120), on the trimmed real pages Android's `SiteRulesTest` reads.
/// Edge cases are pinned to the Kotlin by the differential corpus's `Site` rows.
final class SiteRulesTests: XCTestCase {

    private struct NoRecipe: Error { let page: String }

    private func recipe(_ page: String, _ url: String) throws -> Recipe {
        guard case .success(let recipe) = BlogRecipeSource.parse(html: try pageFixture(page), url: url) else {
            throw NoRecipe(page: page)
        }
        return recipe
    }

    private let nyt = "https://cooking.nytimes.com/recipes/1026066-lemon-layer-cake-with-cream-cheese-frosting"
    private let bbc = "https://www.bbcgoodfood.com/recipes/classic-victoria-sandwich-recipe"
    private let ba = "https://www.bonappetit.com/recipe/bas-best-carrot-cake"
    private let epicurious = "https://www.epicurious.com/recipes/food/views/ba-syn-7up-cake"
    private let delish = "https://www.delish.com/cooking/recipe-ideas/a21968799/cannoli-pie-recipe/"
    private let g750 = "https://www.750g.com/brookie-r98937.htm"
    private let recetas = "https://recetas.elperiodico.com/receta-de-galletas-de-mantequilla-caseras-31553.html"
    private let habas = "https://recetas.elperiodico.com/receta-de-pure-de-habas-56441.html"

    func testNytCookingGroupNamesBecomeHeadings() throws {
        let lines = try recipe("site-nytimes-lemon-layer-cake", nyt).ingredients
        XCTAssertEqual(lines[0], "FOR THE CAKE AND LEMON SYRUP:")
        XCTAssertEqual(Array(lines[11..<13]), ["1 tablespoon lemon extract (optional)", "FOR THE FROSTING:"])
        XCTAssertEqual(lines.count, 18)
    }

    func testBbcGoodFoodHeadingComesBackAndItsPromoAfterTheListIsNoHeading() throws {
        let lines = try recipe("site-bbcgoodfood-victoria-sandwich", bbc).ingredients
        XCTAssertEqual(lines[0], "200g caster sugar")
        XCTAssertEqual(Array(lines[5..<8]), ["2 tbsp milk", "For the filling:", "100g butter softened"])
        XCTAssertEqual(lines.last, "icing sugar to decorate")
        XCTAssertEqual(lines.count, 12)
    }

    func testBonAppetitGetsItsHeadingsAndTheEditorsNoteLeavesTheLastStep() throws {
        let recipe = try recipe("site-bonappetit-carrot-cake", ba)
        XCTAssertEqual(Array(recipe.ingredients.prefix(2)), ["Cake:", "Nonstick vegetable oil spray"])
        XCTAssertEqual(recipe.ingredients[19], "Cream cheese frosting and assembly:")
        XCTAssertEqual(recipe.ingredients.count, 26)
        let last = try XCTUnwrap(recipe.instructions.last)
        XCTAssertTrue(last.hasSuffix("Garnish top of the cake with Candied Carrot Coins, if desired."), last)
        XCTAssertFalse(recipe.instructions.contains { $0.contains("Editor’s note") || $0.contains("Head this way") })
    }

    func testEpicuriousSharesBonAppetitsLayoutDownToItsSpecialEquipment() throws {
        let lines = try recipe("site-epicurious-7up-cake", epicurious).ingredients
        XCTAssertEqual(lines.filter { $0.hasSuffix(":") }, ["Cake:", "Glaze and assembly:", "Special Equipment:"])
        XCTAssertEqual(Array(lines.suffix(2)), ["Special Equipment:", "A 12-cup Bundt pan"])
    }

    func testDelishCardWritesUnitsItsOwnWaySoItsAmountsAreLeftOutOfTheMatch() throws {
        let lines = try recipe("site-delish-cannoli-pie", delish).ingredients
        // The card shows "6 Tbsp."; JSON-LD's line stays.
        XCTAssertEqual(Array(lines.prefix(4)),
                       ["For the crust:", "Cooking spray, for pie dish", "10 graham crackers, crushed", "6 tbsp. butter, melted"])
        XCTAssertEqual(lines[6], "For the filling:")
        XCTAssertEqual(lines.count, 15)
    }

    func test750gBrownieAndCookieDoughsGetTheirHeadings() throws {
        let lines = try recipe("site-750g-brookie", g750).ingredients
        XCTAssertEqual(Array(lines.prefix(2)), ["Brownie:", "2 oeufs"])
        XCTAssertEqual(Array(lines[6..<9]), ["120 g de chocolat", "Cookie:", "½ paquet de levure chimique"])
        XCTAssertEqual(lines.filter { $0.hasSuffix(":") }, ["Brownie:", "Cookie:"])
        XCTAssertEqual(lines.count, 13)
    }

    func testRecetasGratisVideoPointerAndCallForCommentsLeaveTheLastStepOnItsNewHostAndItsOldOne() throws {
        for url in [recetas, recetas.replacingOccurrences(of: "recetas.elperiodico.com", with: "www.recetasgratis.net")] {
            let steps = try recipe("site-recetasgratis-galletas-mantequilla", url).instructions
            XCTAssertEqual(steps.count, 8, url)
            XCTAssertTrue(steps.last?.hasSuffix("cuéntanos cómo te han quedado.") == true, url)
            XCTAssertFalse(steps.contains { $0.contains("vídeo") }, url)
        }
        let last = try recipe("site-recetasgratis-pure-de-habas", habas).instructions.last
        XCTAssertEqual(last, "Servir el puré de hablas y espolvorear cebollín finamente picado.")
    }

    func testACardWithAnIngredientJsonLdLacksLeavesJsonLdsLinesAlone() throws {
        let caesar = "https://www.delish.com/cooking/recipe-ideas/a19695267/caesar-salad-recipe/"
        let lines = try recipe("site-delish-caesar-salad", caesar).ingredients
        XCTAssertEqual(lines.count, 15)
        XCTAssertFalse(lines.contains { $0.hasSuffix(":") })
    }

    func testARuleAppliesOnlyOnItsOwnSite() throws {
        let lines = try recipe("site-bbcgoodfood-victoria-sandwich", "https://example.com/victoria-sandwich").ingredients
        XCTAssertEqual(lines.count, 11)
        let steps = try recipe("site-bonappetit-carrot-cake", "https://example.com/carrot-cake").instructions
        XCTAssertTrue(try XCTUnwrap(steps.last).contains("Editor’s note"))
    }

    func testNoiseIsCutOnlyWhereItsPhraseStartsAndNeverTakesTheOnlyStep() {
        let phrases = ["Editor’s note:"]
        XCTAssertEqual(SiteRules.dropNoise(["Mix.", "Bake. Editor’s note: First printed in 2019."], phrases: phrases), ["Mix.", "Bake."])
        XCTAssertEqual(SiteRules.dropNoise(["Mix.", "Editor’s note: First printed in 2019."], phrases: phrases), ["Mix."])
        XCTAssertEqual(SiteRules.dropNoise(["Editor’s note: x"], phrases: phrases), ["Editor’s note: x"])
        XCTAssertEqual(SiteRules.dropNoise(["Bake.Editor’s note: x"], phrases: phrases), ["Bake.Editor’s note: x"])
        XCTAssertEqual(SiteRules.dropNoise(["Editor’s note: x", "Bake."], phrases: phrases), ["Editor’s note: x", "Bake."])
    }

    func testSelectorsMatchTheDocumentedSubsetAndNothingElse() throws {
        let tree = HtmlTree("<h3 id='a' class='x  SubHed-icPl y' data-testid=\"List\">t</h3>")
        let h3 = try XCTUnwrap(tree.elements.first { $0.name == "h3" })
        for text in ["h3", ".x", ".SubHed-icPl", "#a", "[data-testid]", "[data-testid=List]", "[data-testid='List']",
                     "[class^=x]", "[class*=SubHed-]", "h3.x.y[class*=Hed]", "p, h3"] {
            XCTAssertTrue(try XCTUnwrap(CardSelector(text), text).matches(h3), text)
        }
        for text in ["p", ".X", ".SubHed", "#b", "[data-x]", "[data-testid=list]", "[class^=y]", "h4.x"] {
            XCTAssertFalse(try XCTUnwrap(CardSelector(text), text).matches(h3), text)
        }
        for text in ["div > p", "div p", "a:hover", "li,", "*", "[class|=x]", ""] {
            XCTAssertNil(CardSelector(text), text)
        }
    }

    func testTheTableIsVersionedKeyedByBareHostAndEveryRuleReads() throws {
        let table = SharedTables.read("site-rules")
        XCTAssertEqual(table["schemaVersion"] as? Int, 1)
        XCTAssertGreaterThanOrEqual(SiteRules.version, 1)
        let sites = try XCTUnwrap(table["sites"] as? [String: Any])
        for host in sites.keys {
            XCTAssertTrue(host == host.lowercased() && !host.hasPrefix("www.") && !host.contains("/"), host)
            XCTAssertNotNil(SiteRules.site(url: "https://www.\(host)/r"), host)
        }
        XCTAssertEqual(SiteRules.cards.count, 2)
    }
}
