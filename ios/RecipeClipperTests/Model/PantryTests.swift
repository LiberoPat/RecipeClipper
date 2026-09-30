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

    // #194: what has run out is one last section, in either sort; running low stays in its aisle.
    func testRunOutItemsAreOneLastSectionAndRunningLowStaysInItsAisle() {
        var apples = item("apples", aisle: .produce)
        apples.runningLow = true
        let items = [
            item("rice", aisle: .grains), item("milk", inStock: false, aisle: .dairy, expires: 20_730),
            apples, item("oats", inStock: false, aisle: .grains),
        ]
        let byAisle = PantryList.arrange(items, query: "", sort: .aisle)
        XCTAssertEqual(byAisle.map(\.aisle), [.produce, .grains, nil])
        XCTAssertEqual(byAisle.map(\.runOut), [false, false, true])
        XCTAssertEqual(byAisle.last?.items.map(\.name), ["milk", "oats"])
        XCTAssertEqual(byAisle[0].items.first?.stock, .runningLow)

        let byExpiry = PantryList.arrange(items, query: "", sort: .expiry)
        XCTAssertEqual(byExpiry.map { $0.items.map(\.name) }, [["apples", "rice"], ["milk", "oats"]])
        XCTAssertEqual(byExpiry.map(\.runOut), [false, true])

        XCTAssertEqual(PantryList.arrange(items, query: "oats", sort: .expiry).map(\.runOut), [true])
    }

    func testTheStockIsRunOutWheneverTheItemIsOutRunningLowOnlyInStock() {
        XCTAssertEqual(item("a").stock, .inStock)
        var b = item("b"); b.runningLow = true
        XCTAssertEqual(b.stock, .runningLow)
        var c = item("c", inStock: false); c.runningLow = true
        XCTAssertEqual(c.stock, .runOut)
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
        // A listed pair's number aside (#191).
        let onions = item("Onions")
        XCTAssertEqual(PantryList.sameName([onions], name: "onion", language: "en"), onions)
        XCTAssertNil(PantryList.sameName([onions], name: "red onion", language: "en"))
    }

    // MARK: Matching: presence, by the end-of-name rule

    func testALineIsCoveredWhenItsIngredientIsInStock() {
        let pantry = [item("Butter"), item("flour")]
        XCTAssertTrue(PantryMatch.covered("2 tbsp unsalted butter, softened", language: "en", pantry: pantry))
        XCTAssertTrue(PantryMatch.covered("2 cups all-purpose flour", language: "en", pantry: pantry))
        XCTAssertFalse(PantryMatch.covered("1 can butter beans", language: "en", pantry: pantry))
        XCTAssertFalse(PantryMatch.covered("2 eggs", language: "en", pantry: pantry))
    }

    func testRunningLowIsStillCovered() {
        var flour = item("flour"); flour.runningLow = true
        XCTAssertTrue(PantryMatch.covered("2 cups flour", language: "en", pantry: [flour]))
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

    // MARK: Listed singular/plural pairs (#191)

    func testAListedPairIsHaveInEitherNumberButADifferentOnionIsBuy() {
        // Walkthrough 03: "onions" in the pantry, "1 onion, sliced" in the recipe.
        XCTAssertTrue(PantryMatch.covered("1 onion, sliced", language: "en", pantry: [item("onions")]))
        XCTAssertTrue(PantryMatch.covered("2 large eggs", language: "en", pantry: [item("egg")]))
        XCTAssertTrue(PantryMatch.covered("1 red onion", language: "en", pantry: [item("Red Onions")]))
        // The owner: red onions are different, as yellow onions are from white.
        XCTAssertFalse(PantryMatch.covered("1 red onion", language: "en", pantry: [item("yellow onions")]))
        XCTAssertFalse(PantryMatch.covered("1 red onion", language: "en", pantry: [item("onions")]))
        XCTAssertFalse(PantryMatch.covered("2 onions", language: "en", pantry: [item("red onion")]))
        XCTAssertFalse(PantryMatch.covered("1 tsp onion powder", language: "en", pantry: [item("onions")]))
        XCTAssertFalse(PantryMatch.covered("1 cup pea shoots", language: "en", pantry: [item("peas")]))
    }

    func testAListedPairsLinesAreOneRowOfWhatINeedNamedByTheFirst() {
        let needs = PantryMatch.weekNeeds(
            [source("Soup", "1 onion", "1 red onion"), source("Stew", "2 onions, sliced", recipeId: 2)],
            pantry: [item("Onions")]
        )
        XCTAssertEqual(needs.have.count, 1)
        XCTAssertEqual(needs.have.first?.name, "onion")
        XCTAssertEqual(needs.have.first?.pantryName, "Onions")
        XCTAssertEqual(needs.have.first?.lines.map(\.text), ["1 onion", "2 onions, sliced"])
        XCTAssertEqual(needs.buy.map(\.name), ["red onion"])
    }

    func testOnListIsTheItemsOwnNameAListedPairsNumberAside() {
        func grocery(_ id: Int64, _ text: String, checked: Bool = false) -> GroceryItem {
            GroceryItem(id: id, text: text, language: "en", aisle: .produce, checked: checked, sortOrder: Int(id))
        }
        let list = [grocery(1, "onion"), grocery(2, "2 onions"), grocery(3, "red onions"), grocery(4, "Onions", checked: true)]
        XCTAssertEqual(PantryList.ownLines(item("Onions"), list).map(\.id), [1])
        XCTAssertEqual(PantryList.ownLines(item("red onion"), list).map(\.id), [3])
    }

    /// Owner, 2026-09-29: the basket tag is any unticked line naming the item, as What I need
    /// matches: a typed "2 onions" and a recipe's "1 onion, sliced" are "onions"; "red onion" isn't.
    func testOnListIsAnyUntickedLineWhoseIngredientMatchesTheItemRecipesIncluded() {
        func grocery(_ id: Int64, _ text: String, checked: Bool = false, recipeId: Int64? = nil, language: String = "en") -> GroceryItem {
            GroceryItem(id: id, text: text, language: language, aisle: .produce, checked: checked, sortOrder: Int(id), recipeId: recipeId)
        }
        let list = [
            grocery(1, "onions"), grocery(2, "2 onions"), grocery(3, "1 onion, sliced", recipeId: 7),
            grocery(4, "1 red onion"), grocery(5, "3 onions", checked: true), grocery(6, "2 cebollas", language: "es"),
            grocery(7, "2 tbsp onion powder"),
        ]
        XCTAssertEqual(PantryList.onListLines(item("Onions"), list).map(\.id), [1, 2, 3])
        XCTAssertEqual(PantryList.onListLines(item("red onion"), list).map(\.id), [4])
        XCTAssertEqual(PantryList.onListLines(item("flour"), [grocery(1, "1 cup rice flour")]).map(\.id), [])
        XCTAssertEqual(PantryList.onListLines(item("butter"), [grocery(1, "2 tbsp unsalted butter")]).map(\.id), [1])
    }

    func testNothingPlannedIsEmpty() {
        XCTAssertTrue(PantryMatch.weekNeeds([], pantry: [item("flour")]).isEmpty)
    }
}
