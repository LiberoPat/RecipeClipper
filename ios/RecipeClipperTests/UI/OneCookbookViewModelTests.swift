import XCTest
@testable import RecipeClipper

/// One cookbook (#345, Android's RecipesViewModelTest additions): the strip's counts and lists, a
/// view opened on its own screen, the count against 50 and Home's See all.
@MainActor
final class OneCookbookViewModelTests: XCTestCase {
    private func summary(_ id: Int64, saved: Bool = false, cooked: Int64? = nil, link: String = "") -> RecipeSummary {
        RecipeSummary(
            id: id, title: "Recipe \(id)", imageUrl: nil, totalTime: nil, lastViewedAt: id, isSaved: saved,
            lastCookedDay: cooked, sourceUrl: link.isEmpty ? "https://example.com/\(id)" : link
        )
    }

    private func policy(_ flags: [String: Bool], unlocked: Bool = false) -> LibraryPolicy {
        let store = FakeEntitlements()
        return LibraryPolicy(flags: FeatureFlags(store: MemoryFeatureFlagStore(flags), isDebug: false), entitlements: store)
    }

    func testTheStripsCountsAndListsComeFromWhatIsStored() async {
        let repository = FakeRecipeRepository()
        repository.history.send([
            summary(1, saved: true), summary(2, cooked: 1), summary(3, link: "manual:x"), summary(4, link: SampleRecipe.sourceUrl),
        ])
        let lists = FakeListRepository()
        lists.lists.send([RecipeList(id: 1, name: "Favorites", isBuiltIn: true, isFavorites: true, recipeCount: 0)])
        let vm = RecipesViewModel(
            repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep, lists: lists
        )
        await settleMain()

        let counts = vm.uiState.counts
        XCTAssertEqual(counts?.notInList, 3)
        XCTAssertEqual(counts?.cooked, 1)
        XCTAssertEqual(counts?.typed, 1, "never the tour's sample")
        XCTAssertEqual(vm.uiState.lists.map(\.name), ["Favorites"])
        XCTAssertTrue(vm.uiState.showsViewsStrip)
    }

    func testOpenedOnAViewTheScreenAsksForThatViewAndHasNoStrip() async {
        let repository = FakeRecipeRepository()
        repository.history.send([summary(1), summary(2, cooked: 20_000)])
        let vm = RecipesViewModel(
            repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep, view: .cooked
        )
        await settleMain()

        XCTAssertEqual(vm.uiState.view, .cooked)
        XCTAssertEqual(repository.historyFilters.last, .cooked)
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [2])
        XCTAssertFalse(vm.uiState.showsViewsStrip)
    }

    func testAnUnknownViewIsTheWholeCookbook() async {
        let vm = RecipesViewModel(
            repository: FakeRecipeRepository(), preferences: FakeAppPreferences(), sleep: immediateSleep, view: .inAList
        )
        await settleMain()
        XCTAssertNil(vm.uiState.view)
        XCTAssertEqual(vm.uiState.filter, .all)
    }

    func testTheFreeTierCountsAgainstFiftyAndNothingIsClearedOnItsOwn() async {
        let repository = FakeRecipeRepository()
        repository.history.send((1...12).map { summary(Int64($0)) })
        let vm = RecipesViewModel(
            repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep,
            library: policy(["freeTier": true, "oneCookbook": true])
        )
        await settleMain()

        XCTAssertEqual(vm.count?.saved, 12)
        XCTAssertEqual(vm.count?.max, 50)
        XCTAssertNil(vm.cullKeep)

        let off = RecipesViewModel(
            repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep,
            library: policy(["freeTier": true, "oneCookbook": false])
        )
        await settleMain()
        XCTAssertEqual(off.count?.max, 20, "with the flag off the old swap stands")
    }

    func testWithOneCookbookOnNoCleanUpNoteEverShows() async {
        let repository = FakeRecipeRepository()
        repository.history.send([summary(1, saved: true), summary(2)])
        let vm = RecipesViewModel(
            repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep,
            library: policy(["oneCookbook": true])
        )
        vm.onFilterChange(.notInAList)
        await settleMain()
        XCTAssertNil(vm.cullKeep)
        XCTAssertFalse(vm.showsCullNote)
    }

    func testSeeAllOnHomeOpensRecipesRecentlyViewed() async {
        let preferences = FakeAppPreferences()
        preferences.recipeSort = .name
        let vm = HomeViewModel(repository: FakeRecipeRepository(), preferences: preferences)
        vm.onSeeAllRecent()
        XCTAssertEqual(preferences.recipeSort, .recentlyViewed)
    }
}
