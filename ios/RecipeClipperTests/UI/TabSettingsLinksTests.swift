import XCTest
@testable import RecipeClipper

/// Each tab's own Settings section from its menu (#412): the flag, and the Pantry's new stack that
/// its page opens on. The menus and Back are `TabShellUITests`'. Android: `TabSettingsLinksTest`.
@MainActor
final class TabSettingsLinksTests: XCTestCase {

    func testTheLinksAreOnByDefaultAndPlanSettingsNeedsTheReminders() {
        for isDebug in [true, false] {
            let flags = FeatureFlags(store: MemoryFeatureFlagStore(), definitions: FlagRegistry.definitions, isDebug: isDebug)
            XCTAssertTrue(flags.isOn(.tabSettingsLinks), "debug: \(isDebug)")
            XCTAssertTrue(flags.planSettingsLink, "debug: \(isDebug)")
            XCTAssertTrue(flags.pantrySettingsLink, "debug: \(isDebug)")

            // Settings' Plan section shows only with eveningReminders or todayCardClose (#448), so its link goes with them.
            flags.set(.eveningReminders, false)
            XCTAssertTrue(flags.planSettingsLink)
            flags.set(.todayCardClose, false)
            XCTAssertFalse(flags.planSettingsLink)
            XCTAssertTrue(flags.pantrySettingsLink)

            flags.reset()
            flags.set(.tabSettingsLinks, false)
            XCTAssertFalse(flags.planSettingsLink)
            XCTAssertFalse(flags.pantrySettingsLink)
        }
    }

    func testThePantryKeepsItsPageAndChoosingItAgainGoesBackToThePantry() {
        let router = Router()
        router.select(.pantry)
        router.pantryPath.append(.settingsSection(.pantry))

        router.select(.recipes)
        router.select(.pantry)
        XCTAssertEqual(router.pantryPath, [.settingsSection(.pantry)], "the tab keeps its place")

        router.select(.pantry)
        XCTAssertEqual(router.pantryPath, [], "the open tab again: its first screen")
    }

    func testAnExpiryRemindersTapOpensThePantryItselfNotItsSettingsPage() {
        let router = Router()
        router.pantryPath = [.settingsSection(.pantry)]
        router.select(.week)

        router.requestUseItUp()

        XCTAssertEqual(router.selectedTab, .pantry)
        XCTAssertEqual(router.pantryPath, [], "the Use it up sheet is the pantry's, under the page")
        XCTAssertTrue(router.takeUseItUp())
    }

    func testTheWeekPageOpensOnTheWeeksOwnStack() {
        let router = Router()
        router.select(.week)
        router.weekPath.append(.settingsSection(.week))
        XCTAssertEqual(router.path, [], "nothing pushed on Recipes")

        router.select(.week)
        XCTAssertEqual(router.weekPath, [])
    }

    func testTheMenuItemsHaveTheirWords() {
        XCTAssertEqual(Strings.planSettings, "Plan settings")
        XCTAssertEqual(Strings.menuDescPlanSettings, "Reminders about the meals you plan")
        XCTAssertEqual(Strings.pantrySettings, "Pantry settings")
        XCTAssertEqual(Strings.menuDescPantrySettings, "Expiry reminders")
    }
}
