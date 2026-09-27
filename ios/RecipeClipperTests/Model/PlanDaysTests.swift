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

    // MARK: Months (#52)

    func testCivilDatesRoundTripLeapDaysAndBefore1970Included() {
        XCTAssertEqual(PlanDays.epochDay(year: 1970, month: 1, dayOfMonth: 1), 0)
        XCTAssertEqual(PlanDays.epochDay(year: 2026, month: 9, dayOfMonth: 23), wednesday)
        let civil = PlanDays.civil(wednesday)
        XCTAssertEqual([civil.year, civil.month, civil.day], [2026, 9, 23])
        let eve = PlanDays.civil(-1)
        XCTAssertEqual([eve.year, eve.month, eve.day], [1969, 12, 31])
        XCTAssertEqual(PlanDays.epochDay(year: 2024, month: 3, dayOfMonth: 1), PlanDays.epochDay(year: 2024, month: 2, dayOfMonth: 29) + 1)
        XCTAssertEqual(PlanDays.epochDay(year: 1900, month: 3, dayOfMonth: 1), PlanDays.epochDay(year: 1900, month: 2, dayOfMonth: 28) + 1)
        XCTAssertEqual(PlanDays.epochDay(year: 2000, month: 3, dayOfMonth: 1), PlanDays.epochDay(year: 2000, month: 2, dayOfMonth: 28) + 2)
        for day in stride(from: Int64(-800), through: 800, by: 7) {
            let c = PlanDays.civil(day)
            XCTAssertEqual(PlanDays.epochDay(year: c.year, month: c.month, dayOfMonth: c.day), day)
        }
    }

    func testMonthsStartOnThe1stAndStepAcrossYears() {
        XCTAssertEqual(PlanDays.monthStart(wednesday), PlanDays.epochDay(year: 2026, month: 9, dayOfMonth: 1))
        XCTAssertEqual(PlanDays.addMonths(wednesday, 1), PlanDays.epochDay(year: 2026, month: 10, dayOfMonth: 1))
        XCTAssertEqual(PlanDays.addMonths(wednesday, 4), PlanDays.epochDay(year: 2027, month: 1, dayOfMonth: 1))
        XCTAssertEqual(PlanDays.addMonths(wednesday, -9), PlanDays.epochDay(year: 2025, month: 12, dayOfMonth: 1))
        XCTAssertEqual(
            PlanDays.addMonths(PlanDays.epochDay(year: 2026, month: 1, dayOfMonth: 31), 1),
            PlanDays.epochDay(year: 2026, month: 2, dayOfMonth: 1)
        )
    }

    func testTheMonthGridIsWholeWeeksFromTheLocalesFirstDay() {
        // September 2026: the 1st is a Tuesday, the 30th a Wednesday.
        let monday = PlanDays.monthGrid(wednesday, firstDayOfWeek: 2)
        XCTAssertEqual(monday.first, PlanDays.epochDay(year: 2026, month: 8, dayOfMonth: 31))
        XCTAssertEqual(monday.last, PlanDays.epochDay(year: 2026, month: 10, dayOfMonth: 4))
        XCTAssertEqual(monday.count, 35)
        let sunday = PlanDays.monthGrid(wednesday, firstDayOfWeek: 1)
        XCTAssertEqual(sunday.first, PlanDays.epochDay(year: 2026, month: 8, dayOfMonth: 30))
        XCTAssertEqual(sunday.last, PlanDays.epochDay(year: 2026, month: 10, dayOfMonth: 3))
        XCTAssertEqual(PlanDays.monthGrid(PlanDays.epochDay(year: 2026, month: 2, dayOfMonth: 10), firstDayOfWeek: 1).count, 28)
        XCTAssertEqual(PlanDays.monthGrid(PlanDays.epochDay(year: 2026, month: 8, dayOfMonth: 10), firstDayOfWeek: 2).count, 42)
    }
}
