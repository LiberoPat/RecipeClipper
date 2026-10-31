import XCTest
@testable import RecipeClipper

/// Running timers outside the app (#309): the Live Activity beside each time's-up alert, behind
/// `liveTimers`, and the link a tap on it opens.
@MainActor
final class LiveTimersTests: XCTestCase {
    private final class FakeTimerActivityDisplay: TimerActivityDisplay {
        private(set) var shown: [String: StepAlarm] = [:]

        private static func key(_ recipeId: Int64, _ step: Int) -> String { "\(recipeId).\(step)" }

        func show(_ alarm: StepAlarm) { shown[Self.key(alarm.recipeId, alarm.step)] = alarm }
        func end(recipeId: Int64, step: Int) { shown[Self.key(recipeId, step)] = nil }
        func replaceAll(recipeId: Int64, with alarms: [StepAlarm]) {
            shown = shown.filter { $0.value.recipeId != recipeId }
            for alarm in alarms { show(alarm) }
        }
    }

    private let alarms = FakeTimerAlarmScheduler()
    private let display = FakeTimerActivityDisplay()
    private let flags = FeatureFlags(store: MemoryFeatureFlagStore(), isDebug: true)
    private lazy var scheduler = LiveActivityTimerScheduler(wrapping: alarms, display: display, flags: flags)
    private let alarm = StepAlarm(recipeId: 7, recipeTitle: "Focaccia", step: 2, endsAt: 90_000)

    func testARunningTimerHasItsAlertAndItsCountdown() {
        scheduler.schedule(alarm)

        XCTAssertEqual(alarms.scheduleCalls, [alarm])
        XCTAssertEqual(Array(display.shown.values), [alarm])
    }

    func testCancellingEndsBoth() {
        scheduler.schedule(alarm)
        scheduler.schedule(StepAlarm(recipeId: 7, recipeTitle: "Focaccia", step: 4, endsAt: 60_000))
        scheduler.cancel(recipeId: 7, step: 2)

        XCTAssertEqual(display.shown.values.map(\.step), [4])
        XCTAssertEqual(alarms.pending.keys.map(\.step), [4])
    }

    func testOpeningARecipeKeepsOnlyItsRunningTimersCountdowns() {
        scheduler.schedule(alarm)
        scheduler.replaceAll(recipeId: 7, with: [])

        XCTAssertTrue(display.shown.isEmpty)
    }

    func testWithTheFlagOffOnlyTheAlertIsScheduled() {
        scheduler.schedule(alarm)
        flags.set(.liveTimers, false)
        scheduler.schedule(StepAlarm(recipeId: 8, recipeTitle: "Soup", step: 0, endsAt: 60_000))
        scheduler.replaceAll(recipeId: 7, with: [alarm])

        XCTAssertEqual(alarms.pending.count, 2)
        XCTAssertTrue(display.shown.isEmpty, "turned off mid-cook, the countdown left over goes too")
    }

    func testTheCookLinkOpensTheRecipeInCookMode() throws {
        let link = AppLinks.cook(recipeId: 42)
        XCTAssertEqual(link.absoluteString, "recipeclipper://cook?id=42")
        XCTAssertEqual(AppLinks.cookRecipeId(from: link), 42)
        XCTAssertNil(AppLinks.cookRecipeId(from: try XCTUnwrap(URL(string: "recipeclipper://cook?id=x"))))
        XCTAssertNil(AppLinks.cookRecipeId(from: try XCTUnwrap(URL(string: "https://cook?id=4"))))

        let router = Router()
        router.handle(link)
        XCTAssertEqual(router.selectedTab, .recipes)
        XCTAssertEqual(router.path.last, .cookRecipe(id: 42))
    }
}
