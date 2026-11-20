import XCTest
@testable import RecipeClipper

/// Android's `FreshFoodTest` (#396): the Shop for this week split, and the plan's note.
final class FreshFoodTests: XCTestCase {
    private let today: Int64 = 100

    private func row(_ name: String?, _ days: Int64?..., language: String? = "en") -> NeedRow {
        NeedRow(
            name: name,
            lines: days.enumerated().map { i, day in
                NeedLine(text: "1 \(name ?? "x")", recipeId: Int64(i + 1), title: "Recipe \(i + 1)", day: day, language: language)
            },
            status: .buy, pantryName: nil
        )
    }

    // MARK: The split

    func testAPerishableOnlyForMealsFourOrMoreDaysAwayGoesLaterEachWithTheDayItIsFor() {
        let trips = FreshShop.split([row("salmon fillets", today + 4), row("onions", today + 5)], today: today)
        XCTAssertEqual(trips.now.map(\.name), ["onions"])
        XCTAssertEqual(trips.later.map(\.row.name), ["salmon fillets"])
        XCTAssertEqual(trips.later.first?.group.key, "fish")
        XCTAssertEqual(trips.later.first?.firstDay, today + 4)
    }

    func testTwoDaysIsStillNowAndTheConstantIsThree() {
        XCTAssertEqual(Perishables.laterDays, 3)
        let trips = FreshShop.split([row("salmon", today + 2), row("basil", today + 3)], today: today)
        XCTAssertEqual(trips.now.map(\.name), ["salmon"])
        XCTAssertEqual(trips.later.map(\.row.name), ["basil"])
    }

    func testEveryMealItIsForMustBeFarEnoughOrTheWholeRowIsBoughtNow() {
        let trips = FreshShop.split(
            [row("salmon", today + 6, today + 2), row("basil", today + 6, today + 5), row("spinach", today + 4, nil)], today: today
        )
        XCTAssertEqual(trips.now.map(\.name), ["salmon", "spinach"])
        XCTAssertEqual(trips.later.map(\.row.name), ["basil"])
        XCTAssertEqual(trips.later.first?.firstDay, today + 5)
    }

    func testAnIngredientNotInTheTableOrALineWithNoNameIsBoughtNowHoweverFar() {
        let trips = FreshShop.split([row("flour", today + 6), row(nil, today + 6), row("smoked salmon", today + 6)], today: today)
        XCTAssertEqual(trips.now.count, 3)
        XCTAssertTrue(trips.later.isEmpty)
    }

    func testAMealTodayOrAlreadyPassedIsNow() {
        let trips = FreshShop.split([row("salmon", today), row("basil", today - 2)], today: today)
        XCTAssertEqual(trips.now.count, 2)
    }

    func testTheRowsKeepTheirOrderAndTheirLinesUntouched() {
        let rows = [row("onions", today + 5), row("salmon", today + 5), row("garlic", today + 1), row("basil", today + 6)]
        let trips = FreshShop.split(rows, today: today)
        XCTAssertEqual(trips.now.map(\.name), ["onions", "garlic"])
        XCTAssertEqual(trips.later.map(\.row.name), ["salmon", "basil"])
        XCTAssertEqual(trips.later[0].row, rows[1])
    }

    func testTheRecipesLanguagePicksTheTable() {
        let trips = FreshShop.split([row("lachsfilets", today + 5, language: "de"), row("lachsfilets", today + 5, language: "en")], today: today)
        XCTAssertEqual(trips.later.count, 1)
        XCTAssertEqual(trips.later.first?.row.lines.first?.language, "de")
    }

    // MARK: The nudge

    private let fish = Perishable(group: PerishableGroup(key: "fish", minDays: 1, maxDays: 2), name: "salmon fillets")
    private let herbs = Perishable(group: PerishableGroup(key: "herbs", minDays: 3, maxDays: 5), name: "basil")

    func testAPerishableMealLaterThanItsGroupKeepsGetsOneSwapWithTheEarliestDayThatFits() {
        let meals = [
            FreshMeal(key: 1, day: today, perishable: nil),
            FreshMeal(key: 2, day: today + 1, perishable: nil),
            FreshMeal(key: 3, day: today + 5, perishable: fish),
            FreshMeal(key: 4, day: today + 2, perishable: nil),
        ]
        let nudges = FreshPlan.nudges(meals, today: today)
        XCTAssertEqual(nudges.count, 1)
        let nudge = nudges[0]
        XCTAssertEqual(nudge.key, 3)
        XCTAssertEqual(nudge.name, "Salmon fillets")
        XCTAssertEqual(nudge.keeps, "1-2")
        XCTAssertEqual(nudge.day, today + 5)
        XCTAssertEqual(nudge.swapKey, 1)
        XCTAssertEqual(nudge.swapDay, today)
    }

    func testWithinWhatTheGroupKeepsThereIsNoNoteCountedFromThePlansFirstDay() {
        XCTAssertTrue(FreshPlan.nudges([FreshMeal(key: 1, day: today, perishable: nil), FreshMeal(key: 2, day: today + 2, perishable: fish)], today: today).isEmpty)
        XCTAssertTrue(FreshPlan.nudges([FreshMeal(key: 1, day: today + 5, perishable: nil), FreshMeal(key: 2, day: today + 6, perishable: fish)], today: today).isEmpty)
        XCTAssertEqual(FreshPlan.nudges([FreshMeal(key: 1, day: today + 5, perishable: nil), FreshMeal(key: 2, day: today + 9, perishable: fish)], today: today).count, 1)
    }

    func testNoNoteWhenNothingCouldBeSwapped() {
        XCTAssertTrue(FreshPlan.nudges([FreshMeal(key: 1, day: today + 6, perishable: fish)], today: today).isEmpty)
        XCTAssertTrue(FreshPlan.nudges([FreshMeal(key: 1, day: today, perishable: fish), FreshMeal(key: 2, day: today + 6, perishable: fish)], today: today).isEmpty)
    }

    func testAPartnerWithAPerishableOfItsOwnMustKeepToTheLaterDay() {
        let fits = FreshPlan.nudges([FreshMeal(key: 1, day: today, perishable: herbs), FreshMeal(key: 2, day: today + 4, perishable: fish)], today: today)
        XCTAssertEqual(fits.first?.key, 2)
        XCTAssertEqual(fits.first?.swapKey, 1)
        let tooLate = FreshPlan.nudges(
            [FreshMeal(key: 1, day: today, perishable: herbs), FreshMeal(key: 2, day: today + 4, perishable: nil), FreshMeal(key: 3, day: today + 6, perishable: fish)],
            today: today
        )
        XCTAssertTrue(tooLate.isEmpty)
    }

    func testTheEarliestDayOnATieIsTheOneListedFirst() {
        let meals = [FreshMeal(key: 1, day: today, perishable: nil), FreshMeal(key: 2, day: today, perishable: nil), FreshMeal(key: 3, day: today + 5, perishable: fish)]
        XCTAssertEqual(FreshPlan.nudges(meals, today: today).first?.swapKey, 1)
    }

    func testWithTheFirstMealLateAlreadyThePlanCountsFromIt() {
        let meals = [FreshMeal(key: 1, day: today + 3, perishable: nil), FreshMeal(key: 2, day: today + 7, perishable: fish), FreshMeal(key: 3, day: today + 8, perishable: nil)]
        let nudge = FreshPlan.nudges(meals, today: today).first
        XCTAssertEqual(nudge?.swapKey, 1)
        XCTAssertEqual(nudge?.swapDay, today + 3)
    }

    func testMealsBeforeTodayAreLeftOut() {
        let meals = [FreshMeal(key: 1, day: today - 3, perishable: nil), FreshMeal(key: 2, day: today, perishable: nil), FreshMeal(key: 3, day: today + 5, perishable: fish)]
        XCTAssertEqual(FreshPlan.nudges(meals, today: today).first?.swapKey, 2)
        XCTAssertTrue(FreshPlan.nudges([FreshMeal(key: 1, day: today - 3, perishable: fish)], today: today).isEmpty)
        XCTAssertTrue(FreshPlan.nudges([], today: today).isEmpty)
    }
}
