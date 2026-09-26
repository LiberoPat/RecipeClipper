import XCTest
@testable import RecipeClipper

/// The model names runs of lines; the lines come from the window as written (#128). Android's
/// `PageLinesTest`, case for case; the corpus's `Lines` rows pin the two together.
final class PageLinesTests: XCTestCase {

    private let window = [
        "Grandma’s Banana Bread",
        "Serves 8",
        "Ingredients",
        "▢ 3 very ripe bananas, mashed",
        "• ⅓ cup melted butter",
        "For the topping:",
        "- 1 tbsp sugar",
        "Instructions",
        "Mash the bananas.",
        "Bake for 1 hour.",
    ].joined(separator: "\n")

    private func run(_ first: Int, _ last: Int) -> LineRun { LineRun(first: first, last: last) }

    func testEachLineGoesToTheModelAfterItsNumber() {
        XCTAssertEqual(PageLines.numbered("Soup\nIngredients\n1 cup water"), "[1] Soup\n[2] Ingredients\n[3] 1 cup water")
    }

    func testTheNamedLinesAsWrittenWithoutBulletsOrBareHeadings() {
        let picked = PageLines.selection(
            window, PagePick(name: "Grandma’s Banana Bread", ingredients: [run(3, 7)], steps: [run(8, 10)], yield: "Serves 8")
        )
        XCTAssertEqual(picked, PageSelection(
            name: "Grandma’s Banana Bread",
            ingredients: ["3 very ripe bananas, mashed", "⅓ cup melted butter", "For the topping:", "1 tbsp sugar"],
            steps: ["Mash the bananas.", "Bake for 1 hour."],
            yield: "Serves 8"
        ))
    }

    func testRunsInAnyOrderAndOverlappingGiveEachLineOnceInPageOrder() {
        let picked = PageLines.selection(window, PagePick(name: "x", ingredients: [run(7, 7), run(4, 5), run(5, 5)], steps: []))
        XCTAssertEqual(picked.ingredients, ["3 very ripe bananas, mashed", "⅓ cup melted butter", "1 tbsp sugar"])
    }

    func testARunOutsideTheWindowOrBackwardsIsNoAnswerNeverAGuess() {
        let picked = PageLines.selection(
            window, PagePick(name: "x", ingredients: [run(0, 4), run(5, 4), run(9, 11)], steps: [run(-2, -1), run(10, 10)])
        )
        XCTAssertEqual(picked.ingredients, [])
        XCTAssertEqual(picked.steps, ["Bake for 1 hour."])
    }

    func testALineNamedAsBothAnIngredientAndAStepIsNeither() {
        let picked = PageLines.selection(window, PagePick(name: "x", ingredients: [run(4, 9)], steps: [run(9, 10)]))
        XCTAssertEqual(picked.ingredients, ["3 very ripe bananas, mashed", "⅓ cup melted butter", "For the topping:", "1 tbsp sugar"])
        XCTAssertEqual(picked.steps, ["Bake for 1 hour."])
    }

    func testOnlyAHeadingOnItsOwnIsDroppedInEveryShippedLanguage() {
        for heading in ["Ingredients", "INGREDIENTS:", "Method", "Zutaten", "作り方"] {
            XCTAssertTrue(RecipeTextWindow.isBareHeading(heading), heading)
        }
        for line in ["Ingredients for the glaze", "材料（2人分）", "Method: stir well"] {
            XCTAssertFalse(RecipeTextWindow.isBareHeading(line), line)
        }
    }
}
