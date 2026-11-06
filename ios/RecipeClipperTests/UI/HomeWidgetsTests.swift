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

    // MARK: - WidgetRefresher: when the app asks WidgetKit to redraw

    private let plan = FakeMealPlanRepository()
    private let groceries = FakeGroceryRepository()
    private var reloads = 0
    private var onReload: (() -> Void)?
    private var refresher: WidgetRefresher?

    /// A refresher over the fakes, started on day 100 and past its first redraw.
    private func startRefresher() async throws {
        let suite = "HomeWidgetsTests.refresher"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        let refresher = WidgetRefresher(switchStore: DefaultsWidgetsSwitch(defaults: defaults), pause: .milliseconds(20)) { [weak self] in
            self?.reloads += 1
            self?.onReload?()
        }
        self.refresher = refresher
        await redraw { refresher.start(mealPlan: self.plan, groceries: self.groceries, today: 100) }
    }

    /// Runs `change` and waits for the redraw it must ask for.
    private func redraw(_ change: () -> Void, file: StaticString = #filePath, line: UInt = #line) async {
        let asked = expectation(description: "a redraw is asked for")
        asked.assertForOverFulfill = false
        onReload = { asked.fulfill() }
        change()
        await fulfillment(of: [asked], timeout: 5)
        onReload = nil
    }

    private func meal(_ id: Int64, day: Int64, _ title: String) -> PlannedMeal {
        PlannedMeal(id: id, day: day, mealTypeId: FakeMealPlanRepository.dinner, recipeId: id, title: title,
                    imageUrl: nil, servings: nil, note: nil)
    }

    // #329: the watched days were today ± 1 as of launch, so two days on nothing asked.
    func testAMealPlannedDaysAfterTheAppStartedAsksForARedraw() async throws {
        try await startRefresher()
        XCTAssertEqual(reloads, 1)

        await redraw { plan.meals.value = [meal(1, day: 102, "Focaccia")] }
        await redraw { plan.meals.value.append(meal(2, day: 130, "Next month's roast")) }
        XCTAssertEqual(reloads, 3)
        XCTAssertEqual(plan.observedRanges, [99...Int64.max], "one window, with no last day")
    }

    // #329: meal types weren't followed, so Tonight kept the old name.
    func testARenamedMealTypeAsksForARedraw() async throws {
        try await startRefresher()

        await redraw {
            plan.types.value = plan.types.value.map {
                $0.id == FakeMealPlanRepository.dinner
                    ? MealType(id: $0.id, name: "Supper", builtInKey: $0.builtInKey, sortOrder: $0.sortOrder) : $0
            }
        }
        XCTAssertEqual(reloads, 2)
    }

    func testTheListAndTheFlagAskForARedraw() async throws {
        try await startRefresher()

        await redraw {
            groceries.items.value = [GroceryItem(id: 1, text: "2 onions", language: "en", aisle: .other, checked: false, sortOrder: 1)]
        }
        XCTAssertEqual(reloads, 2)

        refresher?.flag(on: false)
        XCTAssertEqual(reloads, 3, "the flag redraws at once")
    }
}
