import XCTest
@testable import RecipeClipper

/// The recipe screen's error content carries the cause the repository gave, and each cause
/// gets the right copy. Every error shows Try again (no-recipe included: a captive portal's
/// login page parses as a page with no recipe), so there is no per-cause switch for it.
@MainActor
final class RecipeErrorKindTests: XCTestCase {

    private func errorShown(for result: ParseResult) async -> ParseError? {
        let repository = FakeRecipeRepository()
        repository.importResult = result
        let clock = TestClock()
        let vm = RecipeViewModel(
            recipeId: nil, url: "https://example.com/recipe", repository: repository,
            preferences: FakeAppPreferences(), clock: clock, sleep: clock.sleep
        )
        await settleMain()
        guard case .error(let error) = vm.uiState.content else {
            XCTFail("Expected an error, got \(vm.uiState.content)")
            return nil
        }
        return error
    }

    func testEveryErrorKindReachesTheScreenState() async {
        let kinds: [ParseError] = [
            .blocked(httpStatus: 403), .offline, .fetchFailed("HTTP 400"),
            .fetchFailed("The request timed out.", timedOut: true), .noRecipeFound, .saveFailed,
        ]
        for kind in kinds {
            let shown = await errorShown(for: .error(kind))
            XCTAssertEqual(shown, kind)
        }
    }

    func testTheCopyForEachCause() {
        XCTAssertEqual(
            Strings.message(for: .blocked(httpStatus: 403)),
            "The site didn't let the app in (HTTP 403). Sites often do this for a moment — try again in a minute."
        )
        XCTAssertEqual(
            Strings.message(for: .offline),
            "You're offline. The recipe will load when you're back online."
        )
        XCTAssertEqual(Strings.message(for: .fetchFailed("HTTP 400")), "Couldn't load that page (HTTP 400).")
        XCTAssertEqual(
            Strings.message(for: .fetchFailed("The request timed out.", timedOut: true)),
            "Couldn't load that page (The request timed out.)."
        )
        XCTAssertEqual(Strings.message(for: .noRecipeFound), Strings.errorNoRecipeFound)
    }

    func testWhichCausesAreRetriedAutomatically() {
        XCTAssertTrue(ParseError.blocked(httpStatus: 404).shouldAutoRetry)
        XCTAssertTrue(ParseError.fetchFailed(nil).shouldAutoRetry)
        XCTAssertFalse(ParseError.fetchFailed(nil, timedOut: true).shouldAutoRetry)
        XCTAssertFalse(ParseError.offline.shouldAutoRetry)
        XCTAssertFalse(ParseError.noRecipeFound.shouldAutoRetry)
        XCTAssertFalse(ParseError.saveFailed.shouldAutoRetry)
    }

    func testWhichCausesReloadOnReconnect() {
        XCTAssertTrue(ParseError.offline.reloadsOnReconnect)
        XCTAssertTrue(ParseError.fetchFailed("x", timedOut: true).reloadsOnReconnect)
        XCTAssertFalse(ParseError.blocked(httpStatus: 403).reloadsOnReconnect)
        XCTAssertFalse(ParseError.noRecipeFound.reloadsOnReconnect)
    }
}
