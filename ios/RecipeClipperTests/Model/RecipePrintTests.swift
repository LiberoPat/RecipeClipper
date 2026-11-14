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
        recipe: Recipe? = nil
    ) -> [RecipePrint.Block] {
        let r = recipe ?? self.recipe
        return RecipePrint.blocks(
            recipe: r, servings: ServingsScale(base: 6, target: 3), ingredients: ingredients,
            instructions: r.instructions, sourceDomain: "example.com", note: note
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

    func testNoPhotoWhenTheRecipeHasNone() {
        var noImage = recipe
        noImage.image = nil
        XCTAssertFalse(blocks(recipe: noImage).contains { if case .photo = $0 { return true } else { return false } })
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

    /// Whole blocks (one line each), as the page each lands on.
    private func pages(_ heights: [Double], keep: [Bool], pageHeight: Double) -> [Int] {
        RecipePrint.paginate(heights.indices.map { .init(space: 0, lines: [heights[$0]], keep: keep[$0]) }, pageHeight: pageHeight).map(\.page)
    }

    private func measured(_ space: Double, _ lines: [Double], _ keep: Bool = false) -> RecipePrint.Measured {
        RecipePrint.Measured(space: space, lines: lines, keep: keep)
    }

    private func piece(_ block: Int, _ page: Int, _ first: Int, _ end: Int) -> RecipePrint.Piece {
        RecipePrint.Piece(block: block, page: page, first: first, end: end)
    }

    func testBlocksFillAPageThenTheNext() {
        XCTAssertEqual(pages([40, 50, 60, 30, 20], keep: Array(repeating: false, count: 5), pageHeight: 100), [0, 0, 1, 1, 2])
    }

    func testAHeadingNeverEndsAPage() {
        XCTAssertEqual(pages([70, 20, 20], keep: [false, true, false], pageHeight: 100), [0, 1, 1])
    }

    func testAKeptRunTallerThanAPageBreaksWhereItMust() {
        XCTAssertEqual(pages([60, 30, 90], keep: [true, true, false], pageHeight: 100), [0, 0, 1])
    }

    func testASingleLineTallerThanAPageGetsAPageOfItsOwn() {
        XCTAssertEqual(pages([10, 150, 10], keep: [false, false, false], pageHeight: 100), [0, 1, 2])
    }

    func testABlockTallerThanAPageCarriesOnOverTheNextPagesSplitBetweenLines() {
        // 15 lines of 10 after a block of 10: nine fill the first page, six go on with the next block.
        let pieces = RecipePrint.paginate(
            [measured(0, [10]), measured(0, Array(repeating: 10, count: 15)), measured(0, [10])], pageHeight: 100
        )
        XCTAssertEqual(pieces, [piece(0, 0, 0, 1), piece(1, 0, 0, 9), piece(1, 1, 9, 15), piece(2, 1, 0, 1)])
    }

    func testAHeadingStaysWithTheFirstLineOfABlockTallerThanAPage() {
        // The heading (20) and the note's first line (4 + 10) move on together; the note's space counts once.
        let pieces = RecipePrint.paginate(
            [measured(0, [75]), measured(0, [20], true), measured(4, Array(repeating: 10, count: 12))], pageHeight: 100
        )
        XCTAssertEqual(pieces, [piece(0, 0, 0, 1), piece(1, 1, 0, 1), piece(2, 1, 0, 7), piece(2, 2, 7, 12)])
    }

    func testABlockWithNoLinesTakesItsSpace() {
        XCTAssertEqual(RecipePrint.paginate([measured(5, [])], pageHeight: 100), [piece(0, 0, 0, 0)])
        XCTAssertEqual(measured(5, [4, 6]).height, 15)
    }

    func testACookbookNumbersFromItsTitleAndContentsPages() {
        XCTAssertEqual(RecipePrint.startPages([2, 1, 3], contentsPages: 1), [3, 5, 6])
        XCTAssertEqual(RecipePrint.startPages([0, 1], contentsPages: 2), [4, 5])
    }
}
