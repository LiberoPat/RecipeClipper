import XCTest
@testable import RecipeClipper

/// Sending a list (#149; Android's SendListTextTest): "Send list" writes every unticked item as
/// shown, naming the recipes it's for, and a list shared back in is read line by line, as written.
final class SendListTextTests: XCTestCase {

    private var nextId: Int64 = 1
    private func item(_ text: String, recipeId: Int64? = nil, checked: Bool = false) -> GroceryItem {
        defer { nextId += 1 }
        return GroceryItem(
            id: nextId, text: text, language: "en", aisle: Aisles.of(text, words: LanguageWords.english),
            checked: checked, sortOrder: Int(nextId), recipeId: recipeId
        )
    }

    private let titles: [Int64: String] = [1: "Sheet-pan chicken", 2: "Bread", 3: "Cake"]

    private func send(_ items: GroceryItem...) -> String {
        GroceryShareText.format(GroceryCombiner.sections(items), title: "Groceries", recipeTitles: titles, aisleName: \.key)
    }

    func testEachItemNamesTheRecipeItIsFor() {
        XCTAssertEqual(
            send(item("2 lb chicken thighs", recipeId: 1), item("2 onions")),
            "Groceries\n\nproduce\n- 2 onions\n\nmeat\n- 2 lb chicken thighs (Sheet-pan chicken)"
        )
    }

    func testARowFromSeveralRecipesNamesThemAll() {
        XCTAssertEqual(
            send(item("200 g flour", recipeId: 2), item("100 g flour", recipeId: 3)),
            "Groceries\n\nbaking\n- 300 g flour (Bread, Cake)"
        )
    }

    func testARepeatedLineIsShownOnceWithEachRecipeNamedOnce() {
        XCTAssertEqual(
            send(item("2 onions", recipeId: 2), item("2 onions", recipeId: 3), item("2 onions", recipeId: 3)),
            "Groceries\n\nproduce\n- 6 onions (Bread, Cake)"
        )
    }

    func testLinesKeptTogetherEachNameTheirOwnRecipes() {
        XCTAssertEqual(
            send(
                item("1 cup sugar", recipeId: 2), item("1 cup sugar", recipeId: 3), item("100 g sugar"),
                item("1 cup milk", recipeId: 3), item("2 eggs", recipeId: 3, checked: true)
            ),
            "Groceries\n\ndairy\n- 1 cup milk (Cake)\n\nbaking\n- 1 cup sugar × 2 (Bread, Cake)\n- 100 g sugar"
        )
    }

    func testARecipeWithNoTitleNamesNothing() {
        XCTAssertEqual(send(item("2 onions", recipeId: 9)), "Groceries\n\nproduce\n- 2 onions")
    }

    // MARK: - The Pantry's "Send list": what's in stock

    private func stock(
        _ name: String, quantity: String? = nil, inStock: Bool = true, aisle: Aisle? = nil, expires: Int64? = nil
    ) -> PantryItem {
        defer { nextId += 1 }
        return PantryItem(
            id: nextId, name: name, quantity: quantity, language: "en",
            aisle: aisle ?? Aisles.of(name, words: LanguageWords.english), inStock: inStock, alwaysHave: false,
            purchasedDay: nil, expiresDay: expires
        )
    }

    private func sendPantry(_ sort: PantrySort, _ items: PantryItem...) -> String {
        PantryShareText.format(PantryList.arrange(items, query: "", sort: sort), title: "Pantry", aisleName: \.key)
    }

    func testThePantrySendsWhatIsInStockByAisleWithQuantitiesAsWritten() {
        XCTAssertEqual(
            sendPantry(
                .aisle,
                stock("basmati rice", quantity: " half a bag "), stock("onions"), stock("oats", quantity: " ", aisle: .grains),
                stock("milk", inStock: false)
            ),
            "Pantry\n\nproduce\n- onions\n\ngrains\n- basmati rice (half a bag)\n- oats"
        )
    }

    func testAnAisleWithNothingInStockIsLeftOut() {
        XCTAssertEqual(sendPantry(.aisle, stock("onions"), stock("milk", inStock: false)), "Pantry\n\nproduce\n- onions")
    }

    func testSortedByExpiryThePantrySendsOneListWithNoHeading() {
        XCTAssertEqual(
            sendPantry(.expiry, stock("rice"), stock("onions", expires: 20_730), stock("milk", quantity: "1 l", expires: 20_725)),
            "Pantry\n\n- milk (1 l)\n- onions\n- rice"
        )
    }

    func testAPantryWithNothingInStockSendsOnlyItsTitle() {
        XCTAssertEqual(sendPantry(.aisle, stock("milk", inStock: false)), "Pantry")
    }

    /// What the Pantry sends reads back as its items, and adds to another pantry as their names.
    func testThePantrysListReadsBackAsItsItems() {
        let sent = sendPantry(.aisle, stock("basmati rice", quantity: "half a bag"), stock("onions"), stock("2 lemons"))
        let lines = ReceivedList.lines(sent)
        XCTAssertEqual(lines, ["2 lemons", "onions", "basmati rice (half a bag)"])
        XCTAssertEqual(lines.map { IngredientName.of($0, words: .english) }, ["lemons", "onions", "basmati rice"])
    }

    // MARK: - Receiving

    func testASentListIsReadByItsBulletsLeavingTheTitleAndAislesOut() {
        let sent = "Groceries\n\nProduce\n- 2 onions\n\nMeat\n- 2 lb chicken thighs (Sheet-pan chicken)\n- 2 corn × 3"
        XCTAssertEqual(
            ReceivedList.lines(sent), ["2 onions", "2 lb chicken thighs (Sheet-pan chicken)", "2 corn × 3"]
        )
    }

    func testAListWithNoBulletsOffersEveryLine() {
        XCTAssertEqual(ReceivedList.lines("milk\r\n  2 eggs \n\nbread\n"), ["milk", "2 eggs", "bread"])
    }

    func testOtherBulletsCountAndHeadingsAndEmptyBulletsAreLeftOut() {
        XCTAssertEqual(
            ReceivedList.lines("Shopping:\n• milk\n* eggs\n– 1 lb butter\n- \n-\n- For the sauce:\n- …"),
            ["milk", "eggs", "1 lb butter"]
        )
    }

    func testANegativeLookingLineIsNotABullet() {
        XCTAssertEqual(ReceivedList.lines("-5 bags ice\nmilk"), ["-5 bags ice", "milk"])
    }

    func testNothingToAddIsEmpty() {
        XCTAssertEqual(ReceivedList.lines(""), [])
        XCTAssertEqual(ReceivedList.lines("\n  \n---\n"), [])
    }
}
