import XCTest
@testable import RecipeClipper

/// Android's PlanShiftTest (#398): what skipping a planned meal does to the plan.
final class PlanShiftTests: XCTestCase {
    private let lunch: Int64 = 2
    private let dinner: Int64 = 3
    private let today: Int64 = 100
    private let far: Int64 = 1_000

    private func meal(_ id: Int64, _ day: Int64, _ type: Int64 = 3, note: String? = nil) -> PlannedMeal {
        PlannedMeal(
            id: id, day: day, mealTypeId: type,
            recipeId: note == nil ? id : nil,
            title: note == nil ? "Recipe \(id)" : nil,
            imageUrl: nil, servings: nil, note: note
        )
    }

    private func plan(_ meals: [PlannedMeal], _ id: Int64, _ choice: PlanShift.Choice, lastDay: Int64 = 1_000) -> PlanShift.Result {
        PlanShift.plan(meals, skippedId: id, choice: choice, today: today, lastDay: lastDay)!
    }

    private func move(_ id: Int64, _ from: Int64, _ to: Int64) -> PlanShift.Move {
        PlanShift.Move(mealId: id, fromDay: from, toDay: to)
    }

    // MARK: Push the rest of the week

    func testPushMovesTheSkippedMealAndEveryLaterOneByADay() {
        let result = plan([meal(1, 100), meal(2, 101), meal(3, 103)], 1, .pushWeek)
        XCTAssertEqual(result.moved, [move(1, 100, 101), move(2, 101, 102), move(3, 103, 104)])
        XCTAssertTrue(result.removed.isEmpty)
    }

    func testPushNeverMovesAMealBeforeTheSkippedOne() {
        let result = plan([meal(1, 100), meal(2, 101), meal(3, 102), meal(4, 103)], 3, .pushWeek)
        XCTAssertEqual(result.moved, [move(3, 102, 103), move(4, 103, 104)])
    }

    func testPushOnADayWithSeveralMealsMovesTheOnesAfterTheSkippedMealNotTheOnesBefore() {
        // Breakfast (type 1), lunch, dinner on day 100, in plan order.
        let meals = [meal(1, 100, 1), meal(2, 100, lunch), meal(3, 100, dinner), meal(4, 101, dinner)]
        XCTAssertEqual(plan(meals, 2, .pushWeek).moved, [move(2, 100, 101), move(3, 100, 101), move(4, 101, 102)])
    }

    func testPushMovesTwoMealsOfOneSlotTogetherAndKeepsTheirTypes() {
        let meals = [meal(1, 100), meal(2, 101, lunch), meal(3, 101, dinner), meal(4, 101, dinner)]
        let result = plan(meals, 1, .pushWeek)
        XCTAssertEqual(result.moved.map(\.mealId), [1, 2, 3, 4])
        XCTAssertTrue(result.moved.allSatisfy { $0.toDay == $0.fromDay + 1 })
    }

    func testPushMovesPlannedNotesToo() {
        let meals = [meal(1, 100), meal(2, 101, note: "Eat out"), meal(3, 104, dinner, note: "Leftovers")]
        XCTAssertEqual(plan(meals, 1, .pushWeek).moved, [move(1, 100, 101), move(2, 101, 102), move(3, 104, 105)])
    }

    func testPushKeepsTheGapsItDoesntCloseThem() {
        XCTAssertEqual(plan([meal(1, 100), meal(2, 105)], 1, .pushWeek).moved, [move(1, 100, 101), move(2, 105, 106)])
    }

    func testPushAloneOnThePlanMovesJustThatMeal() {
        XCTAssertEqual(plan([meal(7, 100)], 7, .pushWeek).moved, [move(7, 100, 101)])
    }

    func testPushReportsTheMealsThatEndPastTheLastDayShown() {
        let meals = [meal(1, 100), meal(2, 150), meal(3, 151)]
        XCTAssertEqual(plan(meals, 1, .pushWeek, lastDay: 151).pastEnd, [3])
        XCTAssertEqual(plan(meals, 1, .pushWeek, lastDay: 152).pastEnd, [])
    }

    func testPushOfAMealInTheFarFutureLeavesEarlierOnesAlone() {
        XCTAssertEqual(plan([meal(1, 100), meal(2, 140)], 2, .pushWeek).moved, [move(2, 140, 141)])
    }

    // MARK: Move to the next free day

    func testNextFreeDayIsTheDayAfterWhenItsMealTypeIsFree() {
        XCTAssertEqual(plan([meal(1, 100), meal(2, 101, lunch)], 1, .nextFreeDay).moved, [move(1, 100, 101)])
    }

    func testNextFreeDaySkipsDaysThatAlreadyHoldAMealOfThatTypeNotesIncluded() {
        let meals = [meal(1, 100), meal(2, 101), meal(3, 102, note: "Pizza night"), meal(4, 104)]
        XCTAssertEqual(plan(meals, 1, .nextFreeDay).moved, [move(1, 100, 103)])
    }

    func testFreeIsDefinedPerMealTypeSoALunchDoesNotBlockADinner() {
        let meals = [meal(1, 100, dinner), meal(2, 101, lunch), meal(3, 102, lunch)]
        XCTAssertEqual(plan(meals, 1, .nextFreeDay).moved, [move(1, 100, 101)])
        let lunchSkipped = [meal(1, 100, lunch), meal(2, 101, lunch), meal(3, 102, dinner)]
        XCTAssertEqual(plan(lunchSkipped, 1, .nextFreeDay).moved, [move(1, 100, 102)])
    }

    func testNextFreeDayIgnoresMealsBeforeItAndOtherMealsOnTheSkippedDay() {
        let meals = [meal(1, 99), meal(2, 100, lunch), meal(3, 100, dinner), meal(4, 100, dinner)]
        XCTAssertEqual(plan(meals, 3, .nextFreeDay).moved, [move(3, 100, 101)])
    }

    func testNextFreeDayMovesNothingElse() {
        XCTAssertEqual(plan([meal(1, 100), meal(2, 101), meal(3, 103)], 1, .nextFreeDay).moved, [move(1, 100, 102)])
    }

    func testNextFreeDayPastTheLastDayShownIsReported() {
        XCTAssertEqual(plan([meal(1, 100), meal(2, 101)], 1, .nextFreeDay, lastDay: 101).pastEnd, [1])
    }

    // MARK: Remove

    func testRemoveDropsOnlyTheSkippedMeal() {
        let result = plan([meal(1, 100), meal(2, 101)], 1, .remove)
        XCTAssertEqual(result.removed, [1])
        XCTAssertTrue(result.moved.isEmpty)
        XCTAssertTrue(result.pastEnd.isEmpty)
    }

    // MARK: What is offered

    func testAMealInThePastIsNotOfferedAndHasNoPlan() {
        let meals = [meal(1, 99), meal(2, 100)]
        XCTAssertFalse(PlanShift.canSkip(meals[0], today: today))
        XCTAssertTrue(PlanShift.canSkip(meals[1], today: today))
        for choice in PlanShift.Choice.allCases {
            XCTAssertNil(PlanShift.plan(meals, skippedId: 1, choice: choice, today: today, lastDay: far))
        }
    }

    func testAnUnknownMealHasNoPlan() {
        XCTAssertNil(PlanShift.plan([meal(1, 100)], skippedId: 9, choice: .pushWeek, today: today, lastDay: far))
    }

    func testAPastMealInTheListIsNeverMovedByAPushOfALaterOne() {
        XCTAssertEqual(
            plan([meal(1, 90), meal(2, 100), meal(3, 101)], 2, .pushWeek).moved,
            [move(2, 100, 101), move(3, 101, 102)]
        )
    }
}
