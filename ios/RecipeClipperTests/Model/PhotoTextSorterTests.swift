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
        texts.map { PhotoLine(text: $0, confidence: unsure.contains($0) ? Float(0.3) : Float(1)) }
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

    func testAMixedNumberThatLostItsSpaceIsCheckedHoweverSureTheRecogniserWas() {
        // Read from a real card by Vision, at full confidence: "1 1/2 cups flour".
        let misread = card.map { $0 == "1 1/2 cups flour" ? "11/2 cups flour" : $0 }
        let reading = PhotoTextSorter.sort(lines(misread))

        XCTAssertEqual(reading.ingredients[1], "11/2 cups flour")
        XCTAssertEqual(reading.uncertain, ["11/2 cups flour"])
    }

    func testCheckedLinesKeepTheOrderTheyAreShownIn() {
        let misread = card.map { line -> String in
            switch line {
            case "1 cup butter": return "1 cupbutter"
            case "3. Bake at 350°F for 12 minutes.": return "3. Bake at 35O°F for 12 minutes."
            default: return line
            }
        }
        let reading = PhotoTextSorter.sort(lines(misread, unsure: ["2 cups rolled oats"]))

        XCTAssertEqual(reading.uncertain, ["1 cupbutter", "2 cups rolled oats", "Bake at 35O°F for 12 minutes."])
    }

    func testAmountsShapedLikeAMisreadingAreSuspect() {
        for line in [
            "11/2 cups flour", "13/4 cups sugar", "21/2 tsp baking soda", "31/3 cups milk",
            "Bake for 11/2 hours.", "3/2 cup water",
            "l/2 cup sugar", "I/4 tsp salt", "O.5 kg potatoes", "1/Z cup milk", "1/S tsp salt",
            "1O eggs", "Bake at 35o°F.", "l2 eggs", "|/2 cup oil",
            "1 cupraisitos", "1 cupraisins", "2 tbspsugar", "1 teaspoonvanilla",
        ] {
            XCTAssertTrue(PhotoTextSorter.suspect(line), line)
        }
    }

    func testOrdinaryAmountsAreNotSuspect() {
        for line in [
            "1 1/2 cups flour", "1/2 cup sugar", "3/4 tsp salt", "2/2 cups", "1-1/2 cups oats",
            "1/2-3/4 cup milk", "11/16 inch", "1 cup raisins", "1cup flour", "2 green onions",
            "3 carrots", "12 cupcake liners", "1oz chocolate", "1l milk", "10 oz spinach",
            "2 lb chicken", "350°F", "Bake at 350°F for 12 minutes.", "Cool for 10 min.Serve.",
            "Aunt June's Oatmeal Cookies", "Stir in 2 eggs", "0.5 kg potatoes", "1,5 kg flour",
        ] {
            XCTAssertFalse(PhotoTextSorter.suspect(line), line)
        }
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
