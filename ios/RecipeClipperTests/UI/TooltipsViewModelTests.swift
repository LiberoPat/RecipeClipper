import XCTest
@testable import RecipeClipper

/// Port of Android's TooltipsViewModelTest: the app's one TooltipsViewModel (#190) — visits, what
/// a screen reports, dismissing, a tap outside the popover, and Show tips again.
@MainActor
final class TooltipsViewModelTests: XCTestCase {
    private var preferences: MemoryTourPreferences!
    private let home: Set<Tooltip> = [.homeLink]
    private let recipe: Set<Tooltip> = [.recipeUnits, .recipeMenu]

    override func setUp() {
        preferences = MemoryTourPreferences(sampleAdded: true, seen: [])
    }

    private func make() -> TooltipsViewModel { TooltipsViewModel(preferences: preferences) }

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
        vm.onVisit(token: "a", screen: .recipe)
        vm.onReport(token: "a", visible: recipe, ready: true)

        vm.onDismiss(.recipeUnits)
        XCTAssertEqual(preferences.seenTooltips, [.recipeUnits])
        XCTAssertNil(vm.uiState.current, "never chained")

        vm.onLeave(token: "a")
        vm.onVisit(token: "a", screen: .recipe) // the same view, back on screen: a later visit
        vm.onReport(token: "a", visible: recipe, ready: true)
        XCTAssertEqual(vm.uiState.current, .recipeMenu)
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
        vm.onReport(token: "a", visible: [.recipeUnits, .recipeStartCooking], ready: true)
        XCTAssertEqual(vm.uiState.current, .recipeUnits)

        vm.onReport(token: "a", visible: [.recipeStartCooking], ready: true)
        XCTAssertNil(vm.uiState.current)

        vm.onReport(token: "a", visible: [.recipeUnits, .recipeStartCooking], ready: true)
        XCTAssertEqual(vm.uiState.current, .recipeUnits, "back in view, back again")
    }

    func testShowTipsAgainBringsEveryTooltipBack() {
        for tooltip in Tooltip.allCases { preferences.setTooltipSeen(tooltip, true) }
        let vm = make()
        vm.onReplay()
        XCTAssertEqual(preferences.seenTooltips, [])

        vm.onVisit(token: "a", screen: .settings)
        vm.onReport(token: "a", visible: [.settingsShowTips], ready: true)
        XCTAssertEqual(vm.uiState.current, .settingsShowTips)
    }

    /// A NavigationStack's root may hear neither onDisappear when a screen is pushed over it nor
    /// onAppear when that screen goes: leaving the top screen makes the one under it the visit
    /// again, after its settling second.
    func testLeavingTheTopScreenResumesTheOneUnderItAfterASecond() async throws {
        let vm = make()
        vm.onVisit(token: "home", screen: .home)
        vm.onReport(token: "home", visible: home, ready: true)
        vm.onClosed(.homeLink) // not now: unseen, so it can show again
        vm.onVisit(token: "recipe", screen: .recipe)
        vm.onLeave(token: "recipe")
        XCTAssertNil(vm.uiState.current, "not in its first second")

        try await Task.sleep(for: .seconds(Tooltips.settleSeconds + 0.3))
        XCTAssertEqual(vm.uiState, TooltipsUiState(current: .homeLink, token: "home"))
    }

    func testATestContainerSeesNoTooltip() {
        let vm = TooltipsViewModel(preferences: MemoryTourPreferences())
        vm.onVisit(token: "a", screen: .home)
        vm.onReport(token: "a", visible: home, ready: true)
        XCTAssertNil(vm.uiState.current)
    }
}
