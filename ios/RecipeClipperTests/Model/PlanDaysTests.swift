import XCTest
@testable import RecipeClipper

/// Android's PlanDaysTest: the plan's epoch-day calendar (#49).
final class PlanDaysTests: XCTestCase {
    // 2026-09-23 is a Wednesday, epoch day 20719.
    private let wednesday: Int64 = 20_719

    func testEpochDay0WasAThursday() {
        XCTAssertEqual(PlanDays.dayOfWeek(0), 5)
        XCTAssertEqual(PlanDays.dayOfWeek(wednesday), 4)
        XCTAssertEqual(PlanDays.dayOfWeek(-1), 4) // 1969-12-31, a Wednesday
    }

    func testTheWeekStartsOnTheLocalesFirstDay() {
        XCTAssertEqual(PlanDays.weekStart(wednesday, firstDayOfWeek: 2), wednesday - 2) // Monday
        XCTAssertEqual(PlanDays.weekStart(wednesday, firstDayOfWeek: 1), wednesday - 3) // Sunday
        XCTAssertEqual(PlanDays.weekStart(wednesday, firstDayOfWeek: 7), wednesday - 4) // Saturday
        XCTAssertEqual(PlanDays.weekStart(wednesday - 2, firstDayOfWeek: 2), wednesday - 2)
        // Sunday in a Monday-first week belongs to the week before it.
        XCTAssertEqual(PlanDays.weekStart(wednesday + 4, firstDayOfWeek: 2), wednesday - 2)
    }

    func testAWeekIsSevenConsecutiveDays() {
        XCTAssertEqual(PlanDays.weekDays(10), Array(10...16))
    }

    func testTodayFollowsTheZoneNotUTC() {
        // 2026-09-23 23:30 UTC is already the 24th in Tokyo and still the 23rd in New York.
        let late = wednesday * PlanDays.millisPerDay + 23 * 3_600_000 + 30 * 60_000
        XCTAssertEqual(PlanDays.today(millis: late, zone: TimeZone(identifier: "UTC")!), wednesday)
        XCTAssertEqual(PlanDays.today(millis: late, zone: TimeZone(identifier: "Asia/Tokyo")!), wednesday + 1)
        XCTAssertEqual(PlanDays.today(millis: late, zone: TimeZone(identifier: "America/New_York")!), wednesday)
    }

    func testAMomentBefore1970FloorsToTheDayBefore() {
        XCTAssertEqual(PlanDays.epochDay(millis: -1, offsetMillis: 0), -1)
    }

    func testDayLabelsUseTheDayNotTheLocalZone() {
        // Midnight UTC of the day, formatted in UTC: never the day before, whatever the zone.
        XCTAssertEqual(PlanDayFormat.dayOfMonth(wednesday), "23")
    }
}
