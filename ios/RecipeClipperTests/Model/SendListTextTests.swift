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
