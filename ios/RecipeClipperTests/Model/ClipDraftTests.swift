import XCTest
@testable import RecipeClipper

/// Mirrors Android's ClipDraftTest.kt (ClipSelectionTest and ClipDraftTest), case for case.
final class ClipSelectionTests: XCTestCase {

    func testEachLineBecomesOneItem() {
        XCTAssertEqual(ClipSelection.lines("1 cup flour\n2 eggs\n½ tsp salt"), ["1 cup flour", "2 eggs", "½ tsp salt"])
    }

    func testEveryKindOfLineBreakSplits() {
        XCTAssertEqual(ClipSelection.lines("a\r\nb\rc\u{2028}d\u{2029}e"), ["a", "b", "c", "d", "e"])
    }

    func testBlankLinesAreDroppedAndSpacesCollapsed() {
        XCTAssertEqual(
            ClipSelection.lines("\n\n  1 cup \u{A0}(226\tg)   butter  \n \u{A0} \n2 eggs\n\n"),
            ["1 cup (226 g) butter", "2 eggs"]
        )
    }

    func testNothingIsGuessedInsideALine() {
        XCTAssertEqual(
            ClipSelection.lines("1. Brown the butter. 2. Whisk in the sugar.\n• 3 cups oats"),
            ["1. Brown the butter. 2. Whisk in the sugar.", "• 3 cups oats"]
        )
    }

    func testAnEmptyOrBlankSelectionHasNoLines() {
        XCTAssertEqual(ClipSelection.lines(""), [])
        XCTAssertEqual(ClipSelection.lines(" \n\u{A0}\n"), [])
    }

    func testANameJoinsItsLinesWithOneSpace() {
        XCTAssertEqual(ClipSelection.name("Brown Butter\n  Oat Cookies \n"), "Brown Butter Oat Cookies")
        XCTAssertEqual(ClipSelection.name("\n "), "")
    }
}

final class ClipDraftTests: XCTestCase {

    private let url = "https://hearthandcrumb.example/cookies"
    private var empty: ClipDraft { ClipDraft(sourceUrl: url) }

    func testAssigningSplitsLinesAndRecordsAMark() {
        let draft = empty.assign(.ingredients, "1 cup flour\n\n2 eggs")
        XCTAssertEqual(draft.ingredients, ["1 cup flour", "2 eggs"])
        XCTAssertEqual(draft.marks, [.ingredients: "m1"])
        XCTAssertEqual(draft.pendingMarkId, "m2")
        XCTAssertEqual(draft.count(.ingredients), 2)
    }

    func testAssigningReplacesNeverAppends() {
        let draft = empty.assign(.ingredients, "a\nb\nc\nd\ne\nf\ng\nh").assign(.ingredients, "1\n2\n3\n4")
        XCTAssertEqual(draft.ingredients, ["1", "2", "3", "4"])
        XCTAssertEqual(draft.count(.ingredients), 4)
        XCTAssertEqual(draft.marks, [.ingredients: "m2"])
    }

    func testANameIsOneLineAndReplacesToo() {
        let draft = empty.assign(.name, "Brown Butter\nOat Cookies").assign(.name, "Cookies")
        XCTAssertEqual(draft.name, "Cookies")
        XCTAssertEqual(draft.count(.name), 1)
    }

    func testABlankSelectionChangesNothing() {
        let draft = empty.assign(.steps, "Mix.")
        XCTAssertEqual(draft.assign(.steps, " \n "), draft)
        XCTAssertEqual(draft.assign(.name, ""), draft)
        XCTAssertEqual(draft.assign(.photo, " "), draft)
    }

    func testThePhotoIsTheImageAddressAsGiven() {
        let draft = empty.assign(.photo, " https://img.example/c.jpg ")
        XCTAssertEqual(draft.photo, "https://img.example/c.jpg")
        XCTAssertEqual(draft.count(.photo), 1)
        XCTAssertEqual(draft.marks[.photo], "m1")
    }

    func testClearingEmptiesOneFieldAndDropsOnlyItsMark() {
        let draft = empty.assign(.name, "Cookies").assign(.steps, "Mix.\nBake.").clear(.steps)
        XCTAssertEqual(draft.steps, [])
        XCTAssertEqual(draft.name, "Cookies")
        XCTAssertEqual(draft.marks, [.name: "m1"])
        XCTAssertNil(empty.assign(.photo, "x").clear(.photo).photo)
    }

    func testMarkIdsNeverRepeatEvenAfterAClear() {
        XCTAssertEqual(empty.assign(.name, "A").clear(.name).assign(.name, "B").marks[.name], "m2")
    }

    func testFinishingNeedsANamePlusIngredientsOrSteps() {
        XCTAssertFalse(empty.canFinish)
        XCTAssertFalse(empty.assign(.name, "Cookies").canFinish)
        XCTAssertFalse(empty.assign(.ingredients, "flour").assign(.steps, "Mix.").canFinish)
        XCTAssertTrue(empty.assign(.name, "Cookies").assign(.ingredients, "flour").canFinish)
        XCTAssertTrue(empty.assign(.name, "Cookies").assign(.steps, "Mix.").canFinish)
    }

    func testBlankLinesLeftInReviewDoNotCount() {
        let draft = empty.assign(.name, "Cookies").addLine(.ingredients)
        XCTAssertFalse(draft.canFinish)
        XCTAssertEqual(draft.count(.ingredients), 0)
    }

    func testEmptyMeansNothingAssignedOrTyped() {
        XCTAssertTrue(empty.isEmpty)
        XCTAssertTrue(empty.addLine(.steps).isEmpty)
        var typed = empty
        typed.serves = "24"
        XCTAssertFalse(typed.isEmpty)
        XCTAssertFalse(empty.assign(.photo, "x").isEmpty)
        XCTAssertTrue(empty.assign(.name, "A").clear(.name).isEmpty)
    }

    func testReviewEditsRemovesAndAddsLines() {
        let draft = empty.assign(.steps, "Mix.\nBake.\nCool.")
        XCTAssertEqual(draft.editLine(.steps, 0, "Mix well.").steps, ["Mix well.", "Bake.", "Cool."])
        XCTAssertEqual(draft.removeLine(.steps, 1).steps, ["Mix.", "Cool."])
        XCTAssertEqual(draft.addLine(.steps).steps, ["Mix.", "Bake.", "Cool.", ""])
        XCTAssertEqual(draft.editLine(.steps, 3, "x"), draft)
        XCTAssertEqual(draft.removeLine(.steps, -1), draft)
    }

    func testNoRecipeUntilItCanFinish() {
        XCTAssertNil(empty.assign(.name, "Cookies").toRecipe())
    }

    func testTheRecipeIsTrimmedDropsBlankLinesAndTakesServesAndTimeOnlyIfTyped() throws {
        let recipe = try XCTUnwrap(
            empty.assign(.name, "Cookies")
                .assign(.ingredients, "flour\nsugar")
                .addLine(.ingredients)
                .editLine(.ingredients, 1, "  sugar  ")
                .assign(.photo, "https://img.example/c.jpg")
                .toRecipe()
        )
        XCTAssertEqual(recipe.name, "Cookies")
        XCTAssertEqual(recipe.ingredients, ["flour", "sugar"])
        XCTAssertEqual(recipe.instructions, [])
        XCTAssertEqual(recipe.image, "https://img.example/c.jpg")
        XCTAssertEqual(recipe.sourceUrl, url)
        XCTAssertNil(recipe.yield)
        XCTAssertNil(recipe.totalTime)
        XCTAssertNil(recipe.prepTime)
        XCTAssertNil(recipe.cookTime)

        var typed = empty.assign(.name, "Cookies").assign(.steps, "Bake.")
        typed.serves = " 24 cookies "
        typed.totalTime = "1h 30m"
        let typedRecipe = try XCTUnwrap(typed.toRecipe())
        XCTAssertEqual(typedRecipe.yield, "24 cookies")
        XCTAssertEqual(typedRecipe.totalTime, "1h 30m")
    }
}
