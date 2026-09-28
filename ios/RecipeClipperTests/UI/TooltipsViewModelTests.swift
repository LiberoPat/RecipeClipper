import XCTest
@testable import RecipeClipper

/// Port of Android's TooltipsViewModelTest: the app's one TooltipsViewModel (#190) — visits, what
/// a screen reports, dismissing, a tap outside the popover, and Show tips again.
@MainActor
final class TooltipsViewModelTests: XCTestCase {
    private var preferences: MemoryTourPreferences!
    private var flags: FeatureFlags!
    private let home: Set<Tooltip> = [.homeLink, .homeNewRecipe]

    override func setUp() {
        preferences = MemoryTourPreferences(sampleAdded: true, seen: [])
        flags = FeatureFlags(store: MemoryFeatureFlagStore())
    }

    private func make() -> TooltipsViewModel { TooltipsViewModel(preferences: preferences, flags: flags) }

    func testAReadyScreenShowsItsFirstTooltipOnThatAppearanceOnly() {
        let vm = make()
        vm.onVisit(token: "a", screen: .home)
        vm.onReport(token: "a", visible: home, ready: false)
        XCTAssertNil(vm.uiState.current, "not while settling or covered")

        vm.onReport(token: "a", visible: home, ready: true)
        XCTAssertEqual(vm.uiState, TooltipsUiState(current: .homeLink, token: "a"))
    }

    func testAReportFromAScreenThatHasBeenLeftCountsForNothing() {
        let vm = make()
        vm.onVisit(token: "new", screen: .settings)
        vm.onReport(token: "old", visible: home, ready: true)
        XCTAssertNil(vm.uiState.current)
    }

    func testGotItMarksItSeenAndTheNextOneWaitsForTheNextVisit() {
        let vm = make()
        vm.onVisit(token: "a", screen: .home)
        vm.onReport(token: "a", visible: home, ready: true)

        vm.onDismiss(.homeLink)
        XCTAssertEqual(preferences.seenTooltips, [.homeLink])
        XCTAssertNil(vm.uiState.current, "never chained")

        vm.onLeave(token: "a")
        vm.onVisit(token: "a", screen: .home) // the same view, back on screen: a later visit
        vm.onReport(token: "a", visible: home, ready: true)
        XCTAssertEqual(vm.uiState.current, .homeNewRecipe)
    }

    func testATapOutsideThePopoverClosesItForTheVisitStillUnseen() {
        let vm = make()
        vm.onVisit(token: "a", screen: .home)
        vm.onReport(token: "a", visible: home, ready: true)

        vm.onClosed(.homeLink)
        XCTAssertNil(vm.uiState.current)
        XCTAssertEqual(preferences.seenTooltips, [])

        vm.onLeave(token: "a")
        vm.onVisit(token: "a", screen: .home)
        vm.onReport(token: "a", visible: home, ready: true)
        XCTAssertEqual(vm.uiState.current, .homeLink, "shown again on a later visit")
    }

    func testScrolledAwayItHidesAndNothingElseShowsInItsPlace() {
        let vm = make()
        vm.onVisit(token: "a", screen: .recipe)
        vm.onReport(token: "a", visible: [.recipeServings, .recipeStartCooking], ready: true)
        XCTAssertEqual(vm.uiState.current, .recipeServings)

        vm.onReport(token: "a", visible: [.recipeStartCooking], ready: true)
        XCTAssertNil(vm.uiState.current)

        vm.onReport(token: "a", visible: [.recipeServings, .recipeStartCooking], ready: true)
        XCTAssertEqual(vm.uiState.current, .recipeServings, "back in view, back again")
    }

    func testAFlaggedScreensTooltipsWaitForTheFlag() {
        flags.set(.mealPlan, false)
        let vm = make()
        vm.onVisit(token: "a", screen: .pantry)
        vm.onReport(token: "a", visible: [.pantryAdd], ready: true)
        XCTAssertNil(vm.uiState.current)

        flags.set(.mealPlan, true)
        vm.onReport(token: "a", visible: [.pantryAdd], ready: true)
        XCTAssertEqual(vm.uiState.current, .pantryAdd)
    }

    func testShowTipsAgainBringsEveryTooltipBack() {
        for tooltip in Tooltip.allCases { preferences.setTooltipSeen(tooltip, true) }
        let vm = make()
        vm.onReplay()
        XCTAssertEqual(preferences.seenTooltips, [])

        vm.onVisit(token: "a", screen: .settings)
        vm.onReport(token: "a", visible: [.settingsUnits], ready: true)
        XCTAssertEqual(vm.uiState.current, .settingsUnits)
    }

    func testATestContainerSeesNoTooltip() {
        let vm = TooltipsViewModel(preferences: MemoryTourPreferences(), flags: flags)
        vm.onVisit(token: "a", screen: .home)
        vm.onReport(token: "a", visible: home, ready: true)
        XCTAssertNil(vm.uiState.current)
    }
}
