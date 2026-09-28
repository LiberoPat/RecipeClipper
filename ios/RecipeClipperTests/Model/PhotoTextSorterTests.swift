import XCTest
@testable import RecipeClipper

/// Android's `PhotoTextSorterTest` (#198): the same cases and expectations.
final class PhotoTextSorterTests: XCTestCase {
    /// A recipe card as the recogniser reads it: a title, headers, a numbered method.
    private let card = [
        "Aunt June's Oatmeal Cookies",
        "Ingredients",
        "1 cup butter",
        "1 1/2 cups flour",
        "2 cups rolled oats",
        "Directions",
        "1. Cream the butter.",
        "2. Stir in the flour and oats.",
        "3. Bake at 350°F for 12 minutes.",
    ]

    private func lines(_ texts: [String], unsure: Set<String> = []) -> [PhotoLine] {
        texts.map { PhotoLine(text: $0, confidence: unsure.contains($0) ? 0.3 : 1) }
    }

    func testACardsLinesSortIntoIngredientsAndStepsLikeATypedPost() {
        let reading = PhotoTextSorter.sort(lines(card))

        XCTAssertTrue(reading.sorted)
        XCTAssertEqual(reading.ingredients, ["1 cup butter", "1 1/2 cups flour", "2 cups rolled oats"])
        XCTAssertEqual(reading.instructions, ["Cream the butter.", "Stir in the flour and oats.", "Bake at 350°F for 12 minutes."])
        XCTAssertEqual(reading.uncertain, [])
    }

    func testLinesTheRecogniserWasUnsureOfAreMarkedAsTheyAreShown() {
        let reading = PhotoTextSorter.sort(lines(card, unsure: ["1 1/2 cups flour", "3. Bake at 350°F for 12 minutes."]))

        // The step lost its "3." to the splitter and is still marked.
        XCTAssertEqual(reading.uncertain, ["1 1/2 cups flour", "Bake at 350°F for 12 minutes."])
    }

    func testAMisreadAmountStaysAsReadNeverCorrected() {
        let misread = card.map { $0 == "1 cup butter" ? "l cup butter" : $0 }
        let reading = PhotoTextSorter.sort(lines(misread, unsure: ["l cup butter"]))

        XCTAssertEqual(reading.ingredients.first, "l cup butter")
        XCTAssertEqual(reading.uncertain, ["l cup butter"])
    }

    func testNoConfidenceIsNotLowConfidence() {
        let reading = PhotoTextSorter.sort(card.map { PhotoLine(text: $0, confidence: nil) })

        XCTAssertEqual(reading.uncertain, [])
    }

    func testAShortUnsurePieceMarksOnlyALineThatIsExactlyIt() {
        let reading = PhotoTextSorter.sort(lines(card + ["1"], unsure: ["1"]))

        XCTAssertFalse(reading.uncertain.contains("1 cup butter"))
    }

    func testLinesThatDontSortComeBackUnsortedEveryOneAsRead() {
        let prose = ["Mix the butter and sugar,", "then the flour. Bake till golden."]
        let reading = PhotoTextSorter.sort(lines(prose, unsure: ["then the flour. Bake till golden."]))

        XCTAssertFalse(reading.sorted)
        XCTAssertEqual(reading.ingredients, prose)
        XCTAssertEqual(reading.instructions, [])
        XCTAssertEqual(reading.uncertain, ["then the flour. Bake till golden."])
    }

    func testAPhotoWithNoTextReadsAsEmpty() {
        let reading = PhotoTextSorter.sort([PhotoLine(text: "  "), PhotoLine(text: "")])

        XCTAssertFalse(reading.sorted)
        XCTAssertTrue(reading.isEmpty)
    }

    func testAGallerysPicturesReadAsOneTextInOrder() {
        // The front has the ingredients, the back the method.
        let reading = PhotoTextSorter.sort(lines(Array(card.prefix(5))) + lines(Array(card.dropFirst(5))))

        XCTAssertTrue(reading.sorted)
        XCTAssertEqual(reading.ingredients.count, 3)
        XCTAssertEqual(reading.instructions.count, 3)
    }
}
