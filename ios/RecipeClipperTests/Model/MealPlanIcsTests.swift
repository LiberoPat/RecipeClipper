import XCTest
@testable import RecipeClipper

/// Android's MealPlanIcsTest: the week as an .ics file (#52).
final class MealPlanIcsTests: XCTestCase {
    // 2026-09-23, a Wednesday; stamped 14:25:00 UTC that day.
    private let wednesday: Int64 = 20_719
    private var stamp: Int64 { wednesday * PlanDays.millisPerDay + (14 * 3600 + 25 * 60) * 1000 }
    private let types: [Int64: String] = [1: "Lunch", 3: "Dinner"]

    private func recipe(_ id: Int64, _ day: Int64, _ title: String, uid: String? = nil) -> PlannedMeal {
        PlannedMeal(id: id, day: day, mealTypeId: 3, recipeId: 7, title: title, imageUrl: nil, servings: 4, note: nil, uid: uid ?? "u\(id)")
    }

    private func note(_ id: Int64, _ day: Int64, _ text: String) -> PlannedMeal {
        PlannedMeal(id: id, day: day, mealTypeId: 1, recipeId: nil, title: nil, imageUrl: nil, servings: nil, note: text, uid: "u\(id)")
    }

    func testAWeekBecomesAllDayEventsOnePerMealWithCRLFLines() {
        let ics = MealPlanIcs.calendar(
            [note(1, wednesday, "Leftovers"), recipe(2, wednesday, "Chicken Adobo")],
            mealTypeNames: types, stampMillis: stamp
        )
        let expected = [
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Recipe Clipper//Meal plan//EN",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "BEGIN:VEVENT",
            "UID:u1@recipe-clipper",
            "DTSTAMP:20260923T142500Z",
            "DTSTART;VALUE=DATE:20260923",
            "DTEND;VALUE=DATE:20260924",
            "SUMMARY:Lunch · Leftovers",
            "TRANSP:TRANSPARENT",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "UID:u2@recipe-clipper",
            "DTSTAMP:20260923T142500Z",
            "DTSTART;VALUE=DATE:20260923",
            "DTEND;VALUE=DATE:20260924",
            "SUMMARY:Dinner · Chicken Adobo",
            "TRANSP:TRANSPARENT",
            "END:VEVENT",
            "END:VCALENDAR",
        ].joined(separator: "\r\n") + "\r\n"
        XCTAssertEqual(ics, expected)
    }

    func testTheLastDayOfAMonthAndYearEndsOnTheNext() {
        let day = PlanDays.epochDay(year: 2026, month: 12, dayOfMonth: 31)
        let ics = MealPlanIcs.calendar([recipe(1, day, "Soup")], mealTypeNames: types, stampMillis: stamp)
        XCTAssertTrue(ics.contains("DTSTART;VALUE=DATE:20261231\r\nDTEND;VALUE=DATE:20270101\r\n"))
    }

    func testAnEntryWithoutAUidFallsBackToItsRowId() {
        let ics = MealPlanIcs.calendar([recipe(9, wednesday, "Soup", uid: "")], mealTypeNames: types, stampMillis: stamp)
        XCTAssertTrue(ics.contains("UID:entry-9@recipe-clipper\r\n"))
    }

    func testAnUnnamedMealTypeLeavesOnlyTheTitle() {
        XCTAssertEqual(MealPlanIcs.summary(recipe(1, wednesday, " Soup "), mealTypeName: " "), "Soup")
    }

    func testTextIsEscapedAndLongLinesFoldAt75OctetsNeverInsideACharacter() {
        XCTAssertEqual(MealPlanIcs.contentLine("SUMMARY", "a, b; c\\d\r\ne"), "SUMMARY:a\\, b\\; c\\\\d\\ne")
        let long = MealPlanIcs.contentLine("SUMMARY", String(repeating: "é", count: 60))
        let lines = long.components(separatedBy: "\r\n")
        XCTAssertTrue(lines.dropFirst().allSatisfy { $0.hasPrefix(" ") })
        XCTAssertTrue(lines.allSatisfy { $0.utf8.count <= 75 })
        XCTAssertEqual(lines.map { $0.hasPrefix(" ") ? String($0.dropFirst()) : $0 }.joined(), "SUMMARY:" + String(repeating: "é", count: 60))
    }

    func testTheFileIsNamedForTheWeeksFirstDay() {
        XCTAssertEqual(MealPlanIcs.fileName(weekStart: wednesday - 2), "meal-plan-2026-09-21.ics")
    }
}
