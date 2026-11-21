import XCTest
@testable import RecipeClipper

/// Library first (#375): the Recipes tab's first screen is the library, so the empty path is the
/// library; Android's `AppShellTest` and `LibraryFirstScreenTest` are the twins of the navigation
/// tests, and the views are the UI tests' (`LibraryFirstUITests`).
@MainActor
final class LibraryFirstTests: XCTestCase {

    /// Choosing the open Recipes tab again pops to the library, which is the empty path.
    func testChoosingTheOpenRecipesTabAgainPopsToTheLibrary() {
        let router = Router()
        router.push(.lists)
        router.push(.listDetail(id: 4))
        router.push(.recipe(id: 7))

        router.select(.recipes)

        XCTAssertEqual(router.path, [])
        XCTAssertEqual(router.selectedTab, .recipes)
    }

    /// A share lands in Recipes over the library, and Back from it returns to the library.
    func testAShareLandsInRecipesOverTheLibrary() throws {
        let router = Router()
        router.select(.pantry)

        router.handle(try XCTUnwrap(DeepLink.importUrl(for: "https://example.com/soup")))

        XCTAssertEqual(router.selectedTab, .recipes)
        XCTAssertEqual(router.path, [.importUrl("https://example.com/soup")])
        router.path.removeLast()
        XCTAssertEqual(router.path, [], "Back is the library")
    }

    /// A received file's "Recipes" (#149) goes back to the library rather than stacking a second one.
    func testShowingTheLibraryReplacesWhatWasOnTheStackWithTheLibrary() {
        let router = Router()
        router.push(.lists)
        router.select(.groceries)

        router.showLibrary()

        XCTAssertEqual(router.selectedTab, .recipes)
        XCTAssertEqual(router.path, [])
    }

    /// Start cooking on the card is pushed on the Recipes stack (Home's went to the Week's), so Back returns to the library.
    func testAMealStartedFromTheCardStaysOnTheRecipesStack() {
        let router = Router()

        router.push(.startWeekRecipe(id: 5, servings: 6))

        XCTAssertEqual(router.path, [.startWeekRecipe(id: 5, servings: 6)])
        XCTAssertEqual(router.weekPath, [])
        XCTAssertEqual(router.selectedTab, .recipes)
        router.path.removeLast()
        XCTAssertEqual(router.path, [])
    }

    /// Home's own card opens on the Week's stack, as before.
    func testHomesCardStillOpensOnTheWeeksStack() {
        let router = Router()

        router.openInWeek(.startWeekRecipe(id: 5, servings: 6))

        XCTAssertEqual(router.selectedTab, .week)
        XCTAssertEqual(router.weekPath, [.startWeekRecipe(id: 5, servings: 6)])
        XCTAssertEqual(router.path, [])
    }

    // MARK: - The tour's tip

    /// One tip, one id: a cook who has seen Home's keeps having seen it. Its words move to the +.
    func testTheFirstTipKeepsItsIdAndPointsAtThePlus() {
        XCTAssertEqual(Tooltip.homeLink.key, "tooltip_home_link")
        XCTAssertEqual(Tooltip.homeLink.screen, .home)
        XCTAssertEqual(
            Strings.tooltip(.homeLink, libraryFirst: true), "Share a recipe link to this app, or tap + to add one."
        )
        XCTAssertEqual(
            Strings.tooltip(.homeLink, libraryFirst: false), "Share a recipe link to this app, or tap here to paste one."
        )
        XCTAssertEqual(Strings.tooltip(.recipeUnits, libraryFirst: true), Strings.tooltip(.recipeUnits))
    }

    func testTheEmptyLibraryHasItsOwnWords() {
        XCTAssertEqual(Strings.libraryEmptyTitle, "Your recipes go here")
    }

    /// The flag is on by default in debug and release, in the shared file both apps read; it needs
    /// `oneCookbook` for Lists to stay reachable, so without that the tab keeps opening Home.
    func testTheFlagIsOnByDefaultAndNeedsOneCookbook() {
        for isDebug in [true, false] {
            let flags = FeatureFlags(store: MemoryFeatureFlagStore(), definitions: FlagRegistry.definitions, isDebug: isDebug)
            XCTAssertTrue(flags.isOn(.libraryFirst), "debug: \(isDebug)")
            XCTAssertTrue(flags.libraryFirst, "debug: \(isDebug)")

            flags.set(.oneCookbook, false)
            XCTAssertTrue(flags.isOn(.libraryFirst))
            XCTAssertFalse(flags.libraryFirst, "Home stays without one cookbook")

            flags.reset()
            flags.set(.libraryFirst, false)
            XCTAssertFalse(flags.libraryFirst)
        }
    }

    // MARK: - The empty library (HomeUiState, which the library reads)

    func testALibraryHoldingOnlyTheSampleIsEmptyAndOffersRestore() async {
        let repository = FakeRecipeRepository()
        repository.sample = 7
        repository.recent.send([RecipeSummary(id: 7, title: "Tomato and White Bean Soup", imageUrl: nil, totalTime: nil, lastViewedAt: 1, isSaved: false)])
        let vm = HomeViewModel(repository: repository, backups: FakeBackupRepository(), files: FakeBackupFiles())
        await settleMain()

        XCTAssertTrue(vm.uiState.libraryEmpty)
        XCTAssertTrue(vm.uiState.showsRestore)
        XCTAssertTrue(vm.canRestore)
    }

    func testALibraryWithARecipeIsNotEmpty() async {
        let repository = FakeRecipeRepository()
        repository.recent.send([RecipeSummary(id: 1, title: "Adobo", imageUrl: nil, totalTime: nil, lastViewedAt: 1, isSaved: false)])
        let vm = HomeViewModel(repository: repository)
        await settleMain()

        XCTAssertFalse(vm.uiState.libraryEmpty)
        XCTAssertFalse(vm.uiState.showsRestore)
    }
}
