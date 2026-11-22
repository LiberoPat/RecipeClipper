import XCTest
@testable import RecipeClipper

/// The Today card's two choices (#448): a close for the rest of the day, and "Show today's meal".
/// Android: `TodayCardChoiceTest`.
final class TodayCardChoiceTests: XCTestCase {
    private let today: Int64 = 20_000

    func testShowsWhenNeverClosedAndTheSettingIsOn() {
        XCTAssertTrue(TodayCardChoice.shows(closable: true, showSetting: true, dismissedDay: nil, today: today))
    }

    func testClosedTodayHidesIt() {
        XCTAssertFalse(TodayCardChoice.shows(closable: true, showSetting: true, dismissedDay: today, today: today))
    }

    func testClosedYesterdayShowsItAgain() {
        XCTAssertTrue(TodayCardChoice.shows(closable: true, showSetting: true, dismissedDay: today - 1, today: today))
    }

    func testACloseDatedAfterTodayDoesNotHideIt() {
        // A clock moved back.
        XCTAssertTrue(TodayCardChoice.shows(closable: true, showSetting: true, dismissedDay: today + 1, today: today))
    }

    func testTheSettingOffHidesItWhateverTheClose() {
        XCTAssertFalse(TodayCardChoice.shows(closable: true, showSetting: false, dismissedDay: nil, today: today))
        XCTAssertFalse(TodayCardChoice.shows(closable: true, showSetting: false, dismissedDay: today - 1, today: today))
    }

    func testWithTheFlagOffNeitherChoiceCounts() {
        XCTAssertTrue(TodayCardChoice.shows(closable: false, showSetting: false, dismissedDay: today, today: today))
        XCTAssertTrue(TodayCardChoice.shows(closable: false, showSetting: true, dismissedDay: nil, today: today))
    }
}
