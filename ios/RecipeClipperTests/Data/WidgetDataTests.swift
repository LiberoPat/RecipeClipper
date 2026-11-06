import XCTest
@testable import RecipeClipper

/// What the home-screen widgets (#309) read and when they ask again (#329): Tonight's timeline
/// holds tomorrow from midnight, the list never reloads by the clock, and an unreadable
/// database is not an empty plan.
final class WidgetDataTests: XCTestCase {
    private var db: AppDatabase!
    private let clock = TestClock()
    /// 2026-09-23, a Wednesday.
    private let today: Int64 = 20_719
    private var utc: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    override func setUpWithError() throws { db = try AppDatabase(path: nil) }

    private func data(on: Bool = true) -> WidgetData {
        let db = db!
        return WidgetData(isOn: { on }, open: { db })
    }

    private var unreadable: WidgetData {
        WidgetData(isOn: { true }, open: { throw CocoaError(.fileReadNoPermission) })
    }

    /// 14:25 UTC on `today`.
    private var afternoon: Date { Date(timeIntervalSince1970: TimeInterval(today * 86_400 + 14 * 3600 + 25 * 60)) }

    private func dinner() async throws -> MealTypeRecord {
        let types = try await db.read { try MealPlanDao(db: $0).mealTypes() }
        return try XCTUnwrap(types.first { $0.builtInKey == MealType.dinner })
    }

    func testTonightHoldsTodayThenTomorrowFromMidnight() async throws {
        let plan = DefaultMealPlanRepository(db: db, clock: clock)
        let dinner = try await dinner()
        await plan.addNote("Focaccia", day: today, mealTypeId: dinner.id)
        await plan.addNote("Tomorrow's soup", day: today + 1, mealTypeId: dinner.id)
        await plan.addNote("Next week's roast", day: today + 7, mealTypeId: dinner.id)

        let timeline = await data().tonight(now: afternoon, calendar: utc)

        XCTAssertEqual(timeline.entries, [
            .init(date: afternoon, rows: .rows([.init(mealType: dinner.name, title: "Focaccia")])),
            .init(date: Date(timeIntervalSince1970: TimeInterval((today + 1) * 86_400)),
                  rows: .rows([.init(mealType: dinner.name, title: "Tomorrow's soup")])),
        ])
        // Tomorrow is already in hand, so a late reload after midnight changes nothing shown.
        XCTAssertEqual(timeline.reload, .atEnd)
    }

    func testTonightsDaysAreTheLocalOnes() async throws {
        var newYork = Calendar(identifier: .gregorian)
        newYork.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        let plan = DefaultMealPlanRepository(db: db, clock: clock)
        let dinner = try await dinner()
        await plan.addNote("Focaccia", day: today, mealTypeId: dinner.id)
        // 22:00 in New York on `today` is already tomorrow in UTC.
        let evening = Date(timeIntervalSince1970: TimeInterval((today + 1) * 86_400 + 2 * 3600))

        let timeline = await data().tonight(now: evening, calendar: newYork)

        XCTAssertEqual(timeline.entries.map(\.rows), [.rows([.init(mealType: dinner.name, title: "Focaccia")]), .rows([])])
        XCTAssertEqual(timeline.entries.last?.date, evening.addingTimeInterval(2 * 3600), "midnight in New York")
    }

    func testTheListIsReadOnceAndNeverReloadsByTheClock() async {
        let empty = await data().groceries(now: afternoon)
        XCTAssertEqual(empty, .init(entries: [.init(date: afternoon, rows: .rows([]))], reload: .never))

        await DefaultGroceryRepository(db: db, clock: clock).add([
            NewGroceryLine(text: "2 onions", language: "en"), NewGroceryLine(text: "paper towels", language: "en"),
        ])
        let list = await data().groceries(now: afternoon)
        XCTAssertEqual(list, .init(entries: [.init(date: afternoon, rows: .rows(["2 onions", "paper towels"]))], reload: .never))
    }

    // #329: `try?` read it as empty, so the widget said "Nothing planned for today" until midnight.
    func testAnUnreadableDatabaseClaimsNothingAndAsksAgainInMinutes() async {
        let soon = WidgetTimelinePlan<String>.Reload.after(afternoon.addingTimeInterval(5 * 60))

        let tonight = await unreadable.tonight(now: afternoon, calendar: utc)
        XCTAssertEqual(tonight.entries, [.init(date: afternoon, rows: .unavailable)])
        XCTAssertEqual(tonight.reload, .after(afternoon.addingTimeInterval(5 * 60)))

        let list = await unreadable.groceries(now: afternoon)
        XCTAssertEqual(list, .init(entries: [.init(date: afternoon, rows: .unavailable)], reload: soon))
    }

    func testWithTheFlagOffNothingIsReadAndOnlyTheAppAsksAgain() async {
        let off = WidgetData(isOn: { false }, open: { throw CocoaError(.fileReadNoPermission) })

        let tonight = await off.tonight(now: afternoon, calendar: utc)
        XCTAssertEqual(tonight, .init(entries: [.init(date: afternoon, rows: .off)], reload: .never))
        let list = await off.groceries(now: afternoon)
        XCTAssertEqual(list, .init(entries: [.init(date: afternoon, rows: .off)], reload: .never))
    }
}
