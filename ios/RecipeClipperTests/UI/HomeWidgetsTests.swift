import XCTest
@testable import RecipeClipper

/// The home-screen widgets (#309) from the app's side: the link a tap opens, and the flag's
/// mirror the widget extension reads. What they show is `WidgetContentTests`.
@MainActor
final class HomeWidgetsTests: XCTestCase {

    func testATabLinkOpensThatTab() throws {
        let week = AppLinks.tab("week")
        XCTAssertEqual(week.absoluteString, "recipeclipper://tab?name=week")
        XCTAssertEqual(AppLinks.tabName(from: week), "week")
        XCTAssertNil(AppLinks.tabName(from: try XCTUnwrap(URL(string: "recipeclipper://cook?id=4"))))

        let router = Router()
        router.path = [.recipes]
        router.handle(week)
        XCTAssertEqual(router.selectedTab, .week)
        router.handle(AppLinks.tab("groceries"))
        XCTAssertEqual(router.selectedTab, .groceries)
        router.handle(AppLinks.tab("nowhere"))
        XCTAssertEqual(router.selectedTab, .groceries, "an unknown tab changes nothing")
        XCTAssertEqual(router.path, [.recipes])
    }

    func testTheFlagsMirrorIsOnUntilTheAppWritesIt() throws {
        let suite = "HomeWidgetsTests"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        let mirror = DefaultsWidgetsSwitch(defaults: defaults)

        XCTAssertTrue(mirror.isOn)
        mirror.store(false)
        XCTAssertFalse(DefaultsWidgetsSwitch(defaults: defaults).isOn)
    }
}
