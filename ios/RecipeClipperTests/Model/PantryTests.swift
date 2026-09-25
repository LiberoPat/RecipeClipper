import XCTest
@testable import RecipeClipper

/// Android's PantryTest (#51): sorting, search, the expiry badge, and Have/Buy.
final class PantryTests: XCTestCase {
    private var nextId: Int64 = 1

    private func item(
        _ name: String, inStock: Bool = true, alwaysHave: Bool = false, aisle: Aisle = .other,
        expires: Int64? = nil, language: String? = "en"
    ) -> PantryItem {
        defer { nextId += 1 }
        return PantryItem(
            id: nextId, name: name, quantity: nil, language: language, aisle: aisle, inStock: inStock,
            alwaysHave: alwaysHave, purchasedDay: nil, expiresDay: expires
        )
    }

    private func source(_ title: String, _ lines: String..., day: Int64? = 20_720, language: String? = "en", recipeId: Int64 = 1) -> GrocerySource {
        GrocerySource(key: "s-\(title)", recipeId: recipeId, title: title, day: day, language: language, lines: lines)
    }

    // MARK: Sorting, search and the badge

    func testByAisleAislesInTheirOrderAndNamesAToZWithin() {
        let items = [item("rice", aisle: .grains), item("Butter", aisle: .dairy), item("apples", aisle: .produce), item("eggs", aisle: .dairy)]
        let sections = PantryList.arrange(items, query: "", sort: .aisle)
        XCTAssertEqual(sections.map(\.aisle), [.produce, .dairy, .grains])
        XCTAssertEqual(sections[1].items.map(\.name), ["Butter", "eggs"])
    }

    func testByExpirySoonestFirstUndatedLastInOneSection() {
        let items = [item("zucchini"), item("milk", expires: 20_725), item("yogurt", expires: 20_721), item("bread")]
        let sections = PantryList.arrange(items, query: "", sort: .expiry)
        XCTAssertEqual(sections.count, 1)
        XCTAssertNil(sections[0].aisle)
        XCTAssertEqual(sections[0].items.map(\.name), ["yogurt", "milk", "bread", "zucchini"])
    }

    func testSearchIsACaseInsensitivePartOfTheName() {
        let items = [item("Plain flour"), item("rice flour"), item("butter")]
        XCTAssertEqual(PantryList.arrange(items, query: " FLOUR ", sort: .aisle).flatMap { $0.items.map(\.name) }, ["Plain flour", "rice flour"])
        XCTAssertTrue(PantryList.arrange(items, query: "cumin", sort: .aisle).isEmpty)
    }

    func testTheBadge() {
        let today: Int64 = 20_720
        XCTAssertEqual(PantryList.badge(today - 1, today: today), .expired)
        XCTAssertEqual(PantryList.badge(today, today: today), .soon)
        XCTAssertEqual(PantryList.badge(today + 3, today: today), .soon)
        XCTAssertNil(PantryList.badge(today + 4, today: today))
        XCTAssertNil(PantryList.badge(nil, today: today))
    }

    func testTheSameNameIsTrimmedAndCaseInsensitiveInTheSameLanguage() {
        let flour = item("Flour")
        XCTAssertEqual(PantryList.sameName([flour], name: " flour ", language: "en"), flour)
        XCTAssertNil(PantryList.sameName([flour], name: "flour", language: "de"))
        XCTAssertNil(PantryList.sameName([flour], name: "rice flour", language: "en"))
    }

    // MARK: Matching: presence, by the end-of-name rule

    func testALineIsCoveredWhenItsIngredientIsInStock() {
        let pantry = [item("Butter"), item("flour")]
        XCTAssertTrue(PantryMatch.covered("2 tbsp unsalted butter, softened", language: "en", pantry: pantry))
        XCTAssertTrue(PantryMatch.covered("2 cups all-purpose flour", language: "en", pantry: pantry))
        XCTAssertFalse(PantryMatch.covered("1 can butter beans", language: "en", pantry: pantry))
        XCTAssertFalse(PantryMatch.covered("2 eggs", language: "en", pantry: pantry))
    }

    func testOutOfStockIsNotCoveredAStapleAlwaysIs() {
        XCTAssertFalse(PantryMatch.covered("1 cup milk", language: "en", pantry: [item("milk", inStock: false)]))
        XCTAssertTrue(PantryMatch.covered("1 tsp salt", language: "en", pantry: [item("salt", inStock: false, alwaysHave: true)]))
    }

    func testNeverAcrossLanguagesAndNeverForALineWithNoName() {
        XCTAssertFalse(PantryMatch.covered("200 g Butter", language: "de", pantry: [item("butter")]))
        XCTAssertFalse(PantryMatch.covered("salt and pepper", language: "en", pantry: [item("salt"), item("pepper")]))
        XCTAssertFalse(PantryMatch.covered("2 eggs", language: nil, pantry: [item("eggs", language: nil)]))
    }

    func testAStapleWinsOverAnOutOfStockItem() {
        let out = item("oil", inStock: false)
        let staple = item("olive oil", alwaysHave: true)
        XCTAssertEqual(PantryMatch.find("olive oil", language: "en", pantry: [out, staple]), staple)
        XCTAssertEqual(PantryMatch.status(staple), .staple)
        XCTAssertEqual(PantryMatch.status(out), .buy)
        XCTAssertEqual(PantryMatch.status(nil), .buy)
    }

    // MARK: The week's What I need

    func testLinesNamingTheSameIngredientAreOneRowNeverSummed() {
        let needs = PantryMatch.weekNeeds(
            [source("Pancakes", "2 cups flour", "2 eggs", recipeId: 1), source("Bread", "500 g flour", day: 20_722, recipeId: 2)],
            pantry: [item("Flour")]
        )
        XCTAssertEqual(needs.have.count, 1)
        let flour = needs.have[0]
        XCTAssertEqual(flour.name, "flour")
        XCTAssertEqual(flour.status, .have)
        XCTAssertEqual(flour.pantryName, "Flour")
        XCTAssertEqual(flour.lines.map(\.text), ["2 cups flour", "500 g flour"])
        XCTAssertEqual(flour.lines.map(\.title), ["Pancakes", "Bread"])
        XCTAssertEqual(needs.buy.map(\.name), ["eggs"])
    }

    func testStaplesAreNeverOnBuyOutOfStockItemsAre() {
        let needs = PantryMatch.weekNeeds(
            [source("Soup", "1 tsp salt", "1 cup milk")],
            pantry: [item("salt", inStock: false, alwaysHave: true), item("milk", inStock: false)]
        )
        XCTAssertEqual(needs.buy.map(\.name), ["milk"])
        XCTAssertEqual(needs.have.first?.status, .staple)
    }

    func testALineWithNoNameStandsAloneOnBuy() {
        let needs = PantryMatch.weekNeeds([source("Salad", "salt and pepper", "salt and pepper")], pantry: [item("salt"), item("pepper")])
        XCTAssertEqual(needs.buy.count, 2)
        XCTAssertTrue(needs.buy.allSatisfy { $0.name == nil && $0.lines.count == 1 })
        XCTAssertTrue(needs.have.isEmpty)
    }

    func testTheSameNameInTwoLanguagesIsTwoRows() {
        let needs = PantryMatch.weekNeeds([source("A", "2 eggs", language: "en"), source("B", "2 eggs", language: "de")], pantry: [])
        XCTAssertEqual(needs.buy.count, 2)
    }

    func testNothingPlannedIsEmpty() {
        XCTAssertTrue(PantryMatch.weekNeeds([], pantry: [item("flour")]).isEmpty)
    }
}
