import XCTest
@testable import RecipeClipper

/// Port of Android's RecipePrintTest, case for case.
final class RecipePrintTests: XCTestCase {

    private let recipe = Recipe(
        name: "Chicken Adobo",
        image: "https://example.com/adobo.jpg",
        ingredients: ["2 lb chicken thighs", "For the sauce:", "1/2 cup soy sauce"],
        instructions: ["Marinate the chicken.", "Simmer 30 minutes."],
        prepTime: "10m",
        cookTime: "30m",
        totalTime: nil,
        yield: "6 servings",
        sourceUrl: "https://example.com/adobo"
    )

    private func blocks(
        ingredients: [String] = ["1 kg chicken thighs", "For the sauce:", "60 ml soy sauce"],
        note: String? = nil,
        photo: Bool = true,
        recipe: Recipe? = nil
    ) -> [RecipePrint.Block] {
        let r = recipe ?? self.recipe
        return RecipePrint.blocks(
            recipe: r, servings: ServingsScale(base: 6, target: 3), ingredients: ingredients,
            instructions: r.instructions, sourceDomain: "example.com", note: note, photo: photo
        )
    }

    func testTheCardIsTheLinesAsShownInTheReadingViewsOrder() {
        XCTAssertEqual(blocks(), [
            .photo("https://example.com/adobo.jpg"),
            .title("Chicken Adobo"),
            .credit("example.com"),
            .meta("Serves 3 (originally 6)"),
            .meta("Prep 10m · Cook 30m"),
            .section("Ingredients"),
            .ingredient("1 kg chicken thighs"),
            .groupHeading("For the sauce:"),
            .ingredient("60 ml soy sauce"),
            .section("Instructions"),
            .step(1, "Marinate the chicken."),
            .step(2, "Simmer 30 minutes."),
        ])
    }

    func testANotePrintsLastUnderItsHeadingABlankOneNotAtAll() {
        XCTAssertEqual(Array(blocks(note: "  Less salt\n").suffix(2)), [.section("Notes"), .note("Less salt")])
        XCTAssertFalse(blocks(note: "  ").contains { if case .note = $0 { return true } else { return false } })
    }

    func testNoPhotoWhenAskedForNoneOrTheRecipeHasNone() {
        var noImage = recipe
        noImage.image = nil
        for shown in [blocks(photo: false), blocks(recipe: noImage)] {
            XCTAssertFalse(shown.contains { if case .photo = $0 { return true } else { return false } })
        }
    }

    func testAnEmptySectionHasNoHeading() {
        XCTAssertFalse(blocks(ingredients: []).contains(.section("Ingredients")))
    }

    func testHeadingsAndTheTitleKeepWithTheNextBlockLinesDont() {
        XCTAssertTrue(RecipePrint.keepsWithNext(.section("Ingredients")))
        XCTAssertTrue(RecipePrint.keepsWithNext(.title("A")))
        XCTAssertFalse(RecipePrint.keepsWithNext(.ingredient("1 egg")))
        XCTAssertFalse(RecipePrint.keepsWithNext(.step(1, "Stir")))
    }

    func testBlocksFillAPageThenTheNext() {
        XCTAssertEqual(RecipePrint.paginate([40, 50, 60, 30, 20], keep: Array(repeating: false, count: 5), pageHeight: 100), [0, 0, 1, 1, 2])
    }

    func testAHeadingNeverEndsAPage() {
        XCTAssertEqual(RecipePrint.paginate([70, 20, 20], keep: [false, true, false], pageHeight: 100), [0, 1, 1])
    }

    func testAKeptRunTallerThanAPageBreaksWhereItMust() {
        XCTAssertEqual(RecipePrint.paginate([60, 30, 90], keep: [true, true, false], pageHeight: 100), [0, 0, 1])
    }

    func testABlockTallerThanAPageGetsItsOwn() {
        XCTAssertEqual(RecipePrint.paginate([10, 150, 10], keep: [false, false, false], pageHeight: 100), [0, 1, 2])
    }

    func testACookbookNumbersFromItsTitleAndContentsPages() {
        XCTAssertEqual(RecipePrint.startPages([2, 1, 3], contentsPages: 1), [3, 5, 6])
        XCTAssertEqual(RecipePrint.startPages([0, 1], contentsPages: 2), [4, 5])
    }
}
