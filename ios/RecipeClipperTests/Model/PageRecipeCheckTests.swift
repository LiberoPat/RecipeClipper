import XCTest
@testable import RecipeClipper

/// The model may only pick text on the page (#103). Android's `PageRecipeCheckTest`, case for case;
/// the corpus's `Pick` rows pin the two together.
final class PageRecipeCheckTests: XCTestCase {

    private let page = """
        Grandma’s Banana Bread
        Servings: 10 slices
        Ingredients
        ▢ 3 very ripe bananas, mashed
        ⅓ cup melted butter
        1 ½ cups all-purpose flour
        12 cups popcorn
        Instructions
        1. Preheat the oven to 350°F (175°C). Bake for 55 to 65 minutes, until a tester comes out clean.
        Bake for 20-25 minutes.
        """

    private func find(_ picked: String, _ kind: PageRecipeCheck.Kind) -> String? {
        PageRecipeCheck.find(page, picked, kind: kind)
    }

    func testWhatShowsIsThePagesOwnTextFoundAfterFolding() {
        XCTAssertEqual(find("1/3 cup melted butter", .ingredient), "⅓ cup melted butter")
        XCTAssertEqual(find("grandma's banana bread", .name), "Grandma’s Banana Bread")
        XCTAssertEqual(find("1 1/2 cups all-purpose flour", .ingredient), "1 ½ cups all-purpose flour")
        XCTAssertEqual(find("3 very ripe bananas, mashed", .ingredient), "3 very ripe bananas, mashed")
    }

    func testTextThatIsNotOnThePageIsDropped() {
        XCTAssertNil(find("2 cups melted butter", .ingredient))
        XCTAssertNil(find("1/2 cup melted butter", .ingredient))
        XCTAssertNil(find("Mash the bananas.", .step))
    }

    func testASpanNeverCutsIntoANumberOrAWord() {
        XCTAssertNil(find("2 cups popcorn", .ingredient))
        XCTAssertNil(find("½ cups all-purpose flour", .ingredient))
        XCTAssertNil(find("25 minutes.", .step))
        XCTAssertNil(find("Bake for 20", .step))
        XCTAssertNil(find("ake for 55 to 65 minutes", .step))
        XCTAssertEqual(find("Bake for 55 to 65 minutes, until a tester comes out clean.", .step),
                       "Bake for 55 to 65 minutes, until a tester comes out clean.")
    }

    func testAnIngredientStartsItsLineAfterABulletOnly() {
        XCTAssertNil(find("10 slices", .ingredient))
        XCTAssertEqual(find("10 slices", .other), "10 slices")
    }

    func testDashesFoldAndANameOrStepNeedsALetter() {
        XCTAssertEqual(find("Bake for 20–25 minutes.", .step), "Bake for 20-25 minutes.")
        XCTAssertNil(find("350", .step))
        XCTAssertNil(find("", .name))
    }

    func testARecipeNeedsANamePlusIngredientsOrSteps() throws {
        let picked = PageSelection(
            name: "Grandma's Banana Bread", ingredients: ["⅓ cup melted butter", "2 eggs"],
            steps: ["Mash everything."], yield: "10 slices", prepTime: "15 minutes"
        )
        let kept = try XCTUnwrap(PageRecipeCheck.verify(page, picked))
        XCTAssertEqual(kept.name, "Grandma’s Banana Bread")
        XCTAssertEqual(kept.ingredients, ["⅓ cup melted butter"])
        XCTAssertEqual(kept.steps, [])
        XCTAssertEqual(kept.yield, "10 slices")
        XCTAssertNil(kept.prepTime)
        var renamed = picked; renamed.name = "Banana Loaf"
        XCTAssertNil(PageRecipeCheck.verify(page, renamed))
        var invented = picked; invented.ingredients = ["2 eggs"]
        XCTAssertNil(PageRecipeCheck.verify(page, invented))
    }
}
