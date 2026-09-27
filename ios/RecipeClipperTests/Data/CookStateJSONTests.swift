import XCTest
@testable import RecipeClipper

final class CookStateJSONTests: XCTestCase {
    func testRoundTripsEveryField() {
        let progress = CookProgress(
            active: true,
            currentStep: 2,
            doneSteps: [0, 1],
            timers: [
                1: SavedTimer(totalSeconds: 600, remainingSeconds: 600, endsAt: 1_700_000_000_000),
                3: SavedTimer(totalSeconds: 300, remainingSeconds: 120, endsAt: nil),
            ]
        )
        XCTAssertEqual(CookStateJSON.decode(CookStateJSON.encode(progress)), progress)
    }

    func testAnEmptyCookStateIsStoredAsNull() {
        XCTAssertNil(CookStateJSON.encode(CookProgress()))
        XCTAssertEqual(CookStateJSON.decode(nil), CookProgress())
    }

    func testUnreadableTextDecodesToAnEmptyCookState() {
        XCTAssertEqual(CookStateJSON.decode("not json"), CookProgress())
        XCTAssertEqual(CookStateJSON.decode(#"{"timers":[{"step":"x"}]}"#), CookProgress())
    }

    /// Android writes this; iOS must read it (and vice versa), so the shape is shared.
    func testReadsAndroidsShape() {
        let json = #"{"active":true,"currentStep":1,"doneSteps":[0],"timers":[{"step":1,"totalSeconds":60,"remainingSeconds":60,"endsAt":5000}]}"#
        XCTAssertEqual(
            CookStateJSON.decode(json),
            CookProgress(active: true, currentStep: 1, doneSteps: [0],
                         timers: [1: SavedTimer(totalSeconds: 60, remainingSeconds: 60, endsAt: 5000)])
        )
    }
}
