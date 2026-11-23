import XCTest
@testable import RecipeClipper

/// The filter sheet with the views folded in (#454): each control asks for the view screens' own filter or
/// #378's, nothing new; the pills and the badge follow the sheet's order; a pill's × undoes only its control.
/// Android's `RecipeFilterChoicesTest`.
final class RecipeFilterChoicesTests: XCTestCase {

    func testNothingChosenAsksForNothingAndShowsNoPill() {
        let none = RecipeFilterChoices.none
        XCTAssertTrue(none.isEmpty)
        XCTAssertEqual(none.views, [])
        XCTAssertEqual(none.quick, [])
        XCTAssertEqual(none.pills, [])
    }

    func testAnyAndAnywhereAreNoFilter() {
        let choices = RecipeFilterChoices(cooked: .any, origin: .anywhere)
        XCTAssertTrue(choices.isEmpty)
        XCTAssertEqual(choices.pills, [])
    }

    func testEachControlAsksForExactlyTheViewOrTheQuickFilterItStandsFor() {
        func check(
            _ choices: RecipeFilterChoices, views: Set<RecipeFilter>, quick: Set<RecipeQuickFilter>, pill: RecipeFilterPill,
            line: UInt = #line
        ) {
            XCTAssertEqual(choices.views, views, line: line)
            XCTAssertEqual(choices.quick, quick, line: line)
            XCTAssertEqual(choices.pills, [pill], line: line)
            XCTAssertFalse(choices.isEmpty, line: line)
        }
        check(RecipeFilterChoices(thirtyOrLess: true), views: [], quick: [.thirtyOrLess], pill: .thirtyOrLess)
        // Cooked is the Cooked view (its EXISTS); Not yet is #378's Not cooked yet, that view turned round.
        check(RecipeFilterChoices(cooked: .cooked), views: [.cooked], quick: [], pill: .cooked)
        check(RecipeFilterChoices(cooked: .notYet), views: [], quick: [.notCooked], pill: .notCooked)
        check(RecipeFilterChoices(usesPantry: true), views: [], quick: [.usesPantry], pill: .usesPantry)
        check(RecipeFilterChoices(notInAList: true), views: [.notInAList], quick: [], pill: .notInAList)
        check(RecipeFilterChoices(origin: .typed), views: [.typed], quick: [], pill: .typed)
        check(RecipeFilterChoices(origin: .chat), views: [.chat], quick: [], pill: .chat)
    }

    func testEveryFilterOnCombinesAndThePillsKeepTheSheetsOrder() {
        let all = RecipeFilterChoices(thirtyOrLess: true, cooked: .cooked, usesPantry: true, notInAList: true, origin: .chat)
        XCTAssertEqual(all.views, [.cooked, .notInAList, .chat])
        XCTAssertEqual(all.quick, [.thirtyOrLess, .usesPantry])
        XCTAssertEqual(all.pills, [.thirtyOrLess, .cooked, .usesPantry, .notInAList, .chat])
    }

    func testAPillsXUndoesItsOwnControlOnly() {
        let all = RecipeFilterChoices(thirtyOrLess: true, cooked: .notYet, usesPantry: true, notInAList: true, origin: .typed)
        var expected = all
        expected.thirtyOrLess = false
        XCTAssertEqual(all.without(.thirtyOrLess), expected)
        expected = all
        expected.cooked = .any
        XCTAssertEqual(all.without(.notCooked), expected)
        expected = all
        expected.usesPantry = false
        XCTAssertEqual(all.without(.usesPantry), expected)
        expected = all
        expected.notInAList = false
        XCTAssertEqual(all.without(.notInAList), expected)
        expected = all
        expected.origin = .anywhere
        XCTAssertEqual(all.without(.typed), expected)
        XCTAssertEqual(RecipeFilterChoices(cooked: .cooked).without(.cooked), RecipeFilterChoices())
        XCTAssertTrue(all.pills.reduce(all) { $0.without($1) }.isEmpty)
    }

    func testNotYetNarrowsTheDatabasesAnswerAsNotCookedYetDoes() {
        let cooked = RecipeSummary(id: 1, title: "Cooked", imageUrl: nil, totalTime: nil, lastViewedAt: 0, isSaved: false, lastCookedDay: 20_000)
        let fresh = RecipeSummary(id: 2, title: "Fresh", imageUrl: nil, totalTime: nil, lastViewedAt: 0, isSaved: false)
        let notYet = RecipeFilterChoices(cooked: .notYet)
        XCTAssertEqual(RecipeQuickFilters.apply([cooked, fresh], filters: notYet.quick), [fresh])
    }
}
