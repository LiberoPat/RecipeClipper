import XCTest
@testable import RecipeClipper

/// Port of Android's TooltipsTest: the tooltips' catalogue and "which tooltip now" rule (#190), pure.
final class TooltipsTests: XCTestCase {
    private let everything = Set(Tooltip.allCases)

    private func visit(_ screen: TooltipScreen) -> TooltipVisit {
        Tooltips.visit(nil, token: "t1", screen: screen)
    }

    private struct Entry: Decodable, Equatable {
        let id: String
        let screen: String
    }

    private struct Catalogue: Decodable { let tooltips: [Entry] }

    func testTheCatalogueIsSharedTooltipsJsonInItsOrderWithItsScreens() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "tooltips", withExtension: "json"))
        let shared = try JSONDecoder().decode(Catalogue.self, from: Data(contentsOf: url)).tooltips
        XCTAssertEqual(shared, Tooltip.allCases.map { Entry(id: $0.id, screen: $0.screen.rawValue) })
    }

    func testEachIsStoredUnderItsOwnKey() {
        XCTAssertEqual(Tooltip.homeLink.key, "tooltip_home_link")
        XCTAssertEqual(Set(Tooltip.allCases.map(\.key)).count, Tooltip.allCases.count)
    }

    func testTheFirstUnseenTooltipOnScreenShowsOnceTheScreenIsReady() {
        let recipe = visit(.recipe)
        XCTAssertEqual(Tooltips.current(recipe, seen: [], visible: everything, ready: true), .recipeUnits)
        XCTAssertNil(Tooltips.current(recipe, seen: [], visible: everything, ready: false))
        XCTAssertEqual(Tooltips.current(recipe, seen: [.recipeUnits], visible: everything, ready: true), .recipeStartCooking)
        XCTAssertNil(Tooltips.current(recipe, seen: Set(Tooltips.forScreen(.recipe)), visible: everything, ready: true))
    }

    func testAnAnchorThatIsntOnScreenIsPassedOverForTheNext() {
        let recipe = visit(.recipe)
        let visible: Set<Tooltip> = [.recipeStartCooking, .recipeMadeThis]
        XCTAssertEqual(Tooltips.current(recipe, seen: [], visible: visible, ready: true), .recipeStartCooking)
        XCTAssertNil(Tooltips.current(recipe, seen: [], visible: [], ready: true))
    }

    func testOnlyTheVisitsScreenShowsOne() {
        let cook = visit(.cook)
        XCTAssertEqual(
            Tooltips.current(cook, seen: [], visible: [.homeLink, .cookDoneNext], ready: true), .cookDoneNext
        )
        XCTAssertNil(Tooltips.current(nil, seen: [], visible: everything, ready: true))
    }

    func testAVisitKeepsTheTooltipItShowedAndShowsNoOther() {
        let shown = Tooltips.shown(visit(.recipe), .recipeUnits)
        XCTAssertEqual(Tooltips.current(shown, seen: [], visible: everything, ready: true), .recipeUnits)
        XCTAssertNil(Tooltips.current(shown, seen: [], visible: [.recipeStartCooking], ready: true))
        XCTAssertEqual(Tooltips.shown(shown, .recipeMadeThis), shown)
    }

    func testDismissedTheScreensNextTooltipWaitsForALaterVisitNeverChained() {
        let closed = Tooltips.closed(Tooltips.shown(visit(.cook), .cookDoneNext))
        XCTAssertNil(Tooltips.current(closed, seen: [.cookDoneNext], visible: everything, ready: true))

        let later = Tooltips.visit(Tooltips.leave(closed, token: "t1"), token: "t2", screen: .cook)
        XCTAssertEqual(Tooltips.current(later, seen: [.cookDoneNext], visible: everything, ready: true), .cookTapStep)
    }

    func testTheSameAppearanceIsTheSameVisit() {
        let closed = Tooltips.closed(visit(.home))
        XCTAssertEqual(Tooltips.visit(closed, token: "t1", screen: .home), closed)
        XCTAssertEqual(Tooltips.visit(closed, token: "t2", screen: .home), TooltipVisit(token: "t2", screen: .home))
        XCTAssertEqual(
            Tooltips.visit(closed, token: "t1", screen: .cook), TooltipVisit(token: "t1", screen: .cook),
            "cook mode is another screen, on the same view"
        )
    }

    func testLeavingAnotherScreenDoesntEndThisOnesVisit() {
        let home = visit(.home)
        XCTAssertEqual(Tooltips.leave(home, token: "other"), home)
        XCTAssertNil(Tooltips.leave(home, token: "t1"))
    }
}
