import XCTest
@testable import RecipeClipper

@MainActor
final class RecipesViewModelTests: XCTestCase {

    private func deleted(_ id: Int64, _ title: String) -> DeletedRecipe {
        DeletedRecipe(
            recipe: Recipe(
                name: title, image: nil, ingredients: ["1 egg"], instructions: ["Cook."],
                prepTime: nil, cookTime: nil, totalTime: nil, yield: nil,
                sourceUrl: "https://example.com/\(id)", id: id
            ),
            memberships: []
        )
    }

    func testRapidQueryChangesAreDebouncedIntoOneRepositoryQuery() async {
        let repository = FakeRecipeRepository()
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)

        vm.onQueryChange("a")
        vm.onQueryChange("ab")
        vm.onQueryChange("abc")
        await settleMain()

        XCTAssertEqual(repository.historyQueries, ["abc"])
        XCTAssertEqual(vm.uiState.query, "abc")
    }

    func testTheDebounceReallyWaits250ms() async {
        let repository = FakeRecipeRepository()
        let clock = TestClock()
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: clock.sleep)
        await clock.advance(by: 250)
        XCTAssertEqual(repository.historyQueries, [""])

        vm.onQueryChange("soup")
        await clock.advance(by: 249)
        XCTAssertEqual(repository.historyQueries, [""])

        await clock.advance(by: 1)
        XCTAssertEqual(repository.historyQueries, ["", "soup"])
    }

    func testTheSameQueryAgainDoesNotResubscribe() async {
        let repository = FakeRecipeRepository()
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()

        vm.onQueryChange("soup")
        await settleMain()
        vm.onQueryChange("soup")
        await settleMain()

        XCTAssertEqual(repository.historyQueries, ["", "soup"])
    }

    func testRecipesStaysNilUntilTheRepositoryAnswers() async {
        let repository = FakeRecipeRepository()
        repository.history.send([testSummary(1)])
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)

        // Still "loading", not "nothing matched".
        XCTAssertNil(vm.uiState.recipes)

        await settleMain()

        XCTAssertEqual(vm.uiState.recipes, [testSummary(1)])
    }

    func testAnEmptyAnswerIsEmptyNotNil() async {
        let vm = RecipesViewModel(repository: FakeRecipeRepository(), preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()

        XCTAssertEqual(vm.uiState.recipes, [])
    }

    /// #286: the line under search counts what is listed, and hides with nothing to order.
    func testTheLineUnderSearchCountsWhatIsListed() async {
        let repository = FakeRecipeRepository()
        repository.history.send([testSummary(1), testSummary(2)])
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        XCTAssertNil(vm.uiState.listed) // still loading
        XCTAssertFalse(vm.showsSortLine)

        await settleMain()
        XCTAssertEqual(vm.uiState.listed, 2)
        XCTAssertTrue(vm.showsSortLine)

        repository.history.send([]) // a search that matches nothing
        await settleMain()
        XCTAssertEqual(vm.uiState.listed, 0)
        XCTAssertFalse(vm.showsSortLine)
    }

    /// #286: a summary credits its link's site, as the reading view does; a typed one none.
    func testASummaryCreditsItsSiteAndATypedOneNone() {
        var summary = testSummary(1)
        summary.sourceUrl = "https://www.sundaysupper.blog/adobo/"
        XCTAssertEqual(summary.sourceDomain, "sundaysupper.blog")
        summary.sourceUrl = ManualRecipe.newSourceUrl("6f1c0d2e-1b7a-4f4e-9a55-0d3c1a2b3c4d")
        XCTAssertNil(summary.sourceDomain)
        summary.sourceUrl = "manual://not-a-site"
        XCTAssertNil(summary.sourceDomain)
        XCTAssertEqual(Strings.sourceCredit("cucinafacile.net", clipped: true), "Clipped by you · cucinafacile.net")
        XCTAssertEqual(Strings.sourceCredit(nil, clipped: true), "Clipped by you")
        XCTAssertNil(Strings.sourceCredit(nil, clipped: false))
    }

    func testDeletingARecipeCapturesItAndSetsPendingDeletes() async {
        let repository = FakeRecipeRepository()
        repository.deleteResults[1] = deleted(1, "Chicken Adobo")
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()

        vm.onDelete(testSummary(1, title: "Chicken Adobo"))
        await settleMain()

        XCTAssertEqual(vm.uiState.pendingDeletes, ["Chicken Adobo"])
        XCTAssertEqual(repository.deleteCalls, [1])
    }

    func testARecipeAlreadyGoneIsNotPending() async {
        let repository = FakeRecipeRepository()
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()

        vm.onDelete(testSummary(1))
        await settleMain()

        XCTAssertEqual(vm.uiState.pendingDeletes, [])
    }

    func testUndoRestoresThroughTheRepositoryAndClearsPendingDeletes() async {
        let repository = FakeRecipeRepository()
        let removed = deleted(1, "Chicken Adobo")
        repository.deleteResults[1] = removed
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()
        vm.onDelete(testSummary(1, title: "Chicken Adobo"))
        await settleMain()

        vm.onUndoDelete()
        await settleMain()

        XCTAssertEqual(vm.uiState.pendingDeletes, [])
        XCTAssertEqual(repository.restoreCalls, [removed])
    }

    // One cookbook (#345): the repository answers whether the free library had room for the Undo.

    func testAnUndoTheFullFreeLibraryRefusesSaysSoAndTheDeleteStands() async {
        let repository = FakeRecipeRepository()
        let removed = deleted(1, "Chicken Adobo")
        repository.deleteResults[1] = removed
        repository.restoreOutcome = .libraryFull
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()
        vm.onDelete(testSummary(1, title: "Chicken Adobo"))
        await settleMain()
        XCTAssertFalse(vm.uiState.restoreRefused)

        vm.onUndoDelete()
        await settleMain()

        XCTAssertTrue(vm.uiState.restoreRefused)
        XCTAssertEqual(vm.uiState.pendingDeletes, [])
        XCTAssertEqual(repository.restoreAllCalls, [[removed]])
        XCTAssertTrue(repository.restoreCalls.isEmpty)
        // The delete stands, so what only an undo needed (the photo files) goes.
        XCTAssertEqual(repository.forgetCalls, [removed])

        vm.onRestoreRefusedShown()
        XCTAssertFalse(vm.uiState.restoreRefused)
    }

    func testABurstsUndoIsAskedForAsOneAndRefusedAsOne() async {
        let repository = FakeRecipeRepository()
        let a = deleted(1, "A")
        let b = deleted(2, "B")
        repository.deleteResults = [1: a, 2: b]
        repository.restoreOutcome = .libraryFull
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()
        vm.onDelete(testSummary(1, title: "A"))
        await settleMain()
        vm.onDelete(testSummary(2, title: "B"))
        await settleMain()

        vm.onUndoDelete()
        await settleMain()

        XCTAssertEqual(repository.restoreAllCalls, [[a, b]], "one request for the whole burst")
        XCTAssertTrue(repository.restoreCalls.isEmpty)
        XCTAssertTrue(vm.uiState.restoreRefused)
        XCTAssertEqual(Set(repository.forgetCalls.map(\.recipe.id)), [1, 2])
    }

    func testAnUndoThatHasRoomSaysNothingAndForgetsNothing() async {
        let repository = FakeRecipeRepository()
        let removed = deleted(1, "Chicken Adobo")
        repository.deleteResults[1] = removed
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()
        vm.onDelete(testSummary(1, title: "Chicken Adobo"))
        await settleMain()

        vm.onUndoDelete()
        await settleMain()

        XCTAssertFalse(vm.uiState.restoreRefused)
        XCTAssertEqual(repository.restoreCalls, [removed])
        XCTAssertTrue(repository.forgetCalls.isEmpty)
    }

    func testDismissingTheSnackbarClearsPendingDeletesWithoutRestoring() async {
        let repository = FakeRecipeRepository()
        repository.deleteResults[1] = deleted(1, "Chicken Adobo")
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()
        vm.onDelete(testSummary(1, title: "Chicken Adobo"))
        await settleMain()

        vm.onSnackbarDismissed()
        vm.onUndoDelete() // too late: nothing left to restore
        await settleMain()

        XCTAssertEqual(vm.uiState.pendingDeletes, [])
        XCTAssertTrue(repository.restoreCalls.isEmpty)
    }

    func testTwoDeletesInARowDoNotClobberEachOtherAndBothAreRestoredOldestFirstByOneUndo() async {
        let repository = FakeRecipeRepository()
        let a = deleted(1, "A")
        let b = deleted(2, "B")
        repository.deleteResults[1] = a
        repository.deleteResults[2] = b
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()

        vm.onDelete(testSummary(1, title: "A"))
        await settleMain()
        vm.onDelete(testSummary(2, title: "B"))
        await settleMain()

        // The second swipe, within the same snackbar window, must not strand the first.
        XCTAssertEqual(vm.uiState.pendingDeletes, ["A", "B"])

        vm.onUndoDelete()
        await settleMain()

        XCTAssertEqual(vm.uiState.pendingDeletes, [])
        XCTAssertEqual(repository.restoreCalls, [a, b])
    }

    /// #268: a burst keeps one batch while its titles grow; Undo clears it at once, with nothing
    /// in between, the restore after changes nothing, and the next swipe is a new batch.
    func testABurstKeepsOneBatchAndUndoClearsItAtOnce() async {
        let repository = FakeRecipeRepository()
        repository.deleteResults = [1: deleted(1, "A"), 2: deleted(2, "B"), 3: deleted(3, "C")]
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()

        vm.onDelete(testSummary(1, title: "A"))
        await settleMain()
        let batch = vm.uiState.deleteBatch
        XCTAssertNotNil(batch)
        vm.onDelete(testSummary(2, title: "B"))
        await settleMain()
        XCTAssertEqual(vm.uiState.deleteBatch, batch)
        vm.onDelete(testSummary(3, title: "C"))
        await settleMain()
        XCTAssertEqual(vm.uiState.deleteBatch, batch)
        XCTAssertEqual(vm.uiState.pendingDeletes, ["A", "B", "C"])

        vm.onUndoDelete()
        XCTAssertEqual(vm.uiState.pendingDeletes, [])
        XCTAssertNil(vm.uiState.deleteBatch)
        await settleMain()
        XCTAssertEqual(vm.uiState.pendingDeletes, [])
        XCTAssertNil(vm.uiState.deleteBatch)
        XCTAssertEqual(repository.restoreCalls.count, 3)

        vm.onDelete(testSummary(1, title: "A"))
        await settleMain()
        XCTAssertNotNil(vm.uiState.deleteBatch)
        XCTAssertNotEqual(vm.uiState.deleteBatch, batch)
    }

    func testDismissingEndsTheBatch() async {
        let repository = FakeRecipeRepository()
        repository.deleteResults = [1: deleted(1, "A")]
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()
        vm.onDelete(testSummary(1, title: "A"))
        await settleMain()

        vm.onSnackbarDismissed()

        XCTAssertNil(vm.uiState.deleteBatch)
        XCTAssertEqual(vm.uiState.pendingDeletes, [])
    }

    // MARK: - Sort and Paste a link (#102)

    private let unsorted = [testSummary(2, title: "Bread"), testSummary(3, title: "apple pie"), testSummary(1, title: "Cake")]

    func testRecentlyViewedIsTheDefaultOrderAsTheRepositoryGivesIt() async {
        let repository = FakeRecipeRepository()
        repository.history.send(unsorted)
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()

        XCTAssertEqual(vm.uiState.sort, .recentlyViewed)
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [2, 3, 1])
    }

    func testNameSortsByTitleIgnoringCaseAndDateAddedIsNewestIdFirst() async {
        let repository = FakeRecipeRepository()
        repository.history.send(unsorted)
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()

        vm.onSortChange(.name)
        XCTAssertEqual(vm.uiState.recipes?.map(\.title), ["apple pie", "Bread", "Cake"])
        vm.onSortChange(.dateAdded)
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [3, 2, 1])
    }

    func testTheStoredSortIsAppliedOnOpenAndANewChoiceIsStored() async {
        let repository = FakeRecipeRepository()
        repository.history.send(unsorted)
        let preferences = FakeAppPreferences(recipeSort: .name)
        let vm = RecipesViewModel(repository: repository, preferences: preferences, sleep: immediateSleep)
        await settleMain()

        XCTAssertEqual(vm.uiState.sort, .name)
        XCTAssertEqual(vm.uiState.recipes?.map(\.title), ["apple pie", "Bread", "Cake"])

        vm.onSortChange(.dateAdded)
        await settleMain()
        XCTAssertEqual(preferences.recipeSort, .dateAdded)
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [3, 2, 1])
    }

    func testPasteALinkImportsALinkAndClosesTheSheetAndWithoutTheRouterOtherTextStays() {
        let vm = RecipesViewModel(repository: FakeRecipeRepository(), preferences: FakeAppPreferences(), sleep: immediateSleep)

        vm.onPasteLink()
        XCTAssertTrue(vm.uiState.pastingLink)
        vm.onLinkChange("not a link")
        XCTAssertTrue(vm.uiState.canOpenLink)
        XCTAssertNil(vm.onOpenLink())
        XCTAssertTrue(vm.uiState.pastingLink)
        XCTAssertTrue(vm.uiState.linkNoRecipe)

        vm.onLinkChange("  seriouseats.com/bread ")
        XCTAssertTrue(vm.uiState.canOpenLink)
        XCTAssertEqual(vm.onOpenLink(), .link("https://seriouseats.com/bread"))
        XCTAssertFalse(vm.uiState.pastingLink)
    }

    func testTheAlertGoingAwayKeepsTheTextForAGoThatRunsSecond() {
        let vm = RecipesViewModel(repository: FakeRecipeRepository(), preferences: FakeAppPreferences(), sleep: immediateSleep)
        vm.onPasteLink()
        vm.onLinkChange("https://example.com/soup")

        vm.onLinkDismissed()

        XCTAssertFalse(vm.uiState.pastingLink)
        XCTAssertEqual(vm.onOpenLink(), .link("https://example.com/soup"))
    }

    func testSnackbarMessageNamesOneAndCountsMany() {
        XCTAssertNil(Strings.deletedMessage([]))
        XCTAssertEqual(Strings.deletedMessage(["Soup"]), "Deleted \"Soup\"")
        XCTAssertEqual(Strings.deletedMessage(["A", "B"]), "2 recipes deleted")
    }

    // MARK: - The filter (#345)

    private func saved(_ id: Int64) -> RecipeSummary {
        RecipeSummary(id: id, title: "Recipe \(id)", imageUrl: nil, totalTime: nil, lastViewedAt: id, isSaved: true)
    }

    func testTheFilterOpensOnAllWithTheLibrarysCounts() async {
        let repository = FakeRecipeRepository()
        repository.history.send([saved(1), testSummary(2), testSummary(3)])
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()

        XCTAssertEqual(vm.uiState.filter, .all)
        XCTAssertEqual(vm.uiState.counts, RecipeCounts(all: 3, inList: 1))
        XCTAssertEqual(vm.uiState.counts?.notInList, 2)
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [1, 2, 3])
        XCTAssertTrue(vm.uiState.showsFilter)
    }

    func testAFilterGoesToTheRepositoryAtOnceWithTheQuery() async {
        let repository = FakeRecipeRepository()
        repository.history.send([saved(1), testSummary(2), testSummary(3)])
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()

        vm.onFilterChange(.notInAList)
        await settleMain()
        XCTAssertEqual(vm.uiState.filter, .notInAList)
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [2, 3])

        vm.onFilterChange(.inAList)
        await settleMain()
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [1])

        // Search applies inside the filter: the query and the filter are asked together.
        vm.onQueryChange("soup")
        await settleMain()
        XCTAssertEqual(repository.historyQueries.last, "soup")
        XCTAssertEqual(repository.historyFilters.last, .inAList)
        XCTAssertEqual(vm.uiState.filter, .inAList)
    }

    func testAFilterNeedsNoDebounce() async {
        let repository = FakeRecipeRepository()
        let clock = TestClock()
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: clock.sleep)
        await clock.advance(by: 250)

        vm.onFilterChange(.inAList)
        await settleMain()
        XCTAssertEqual(repository.historyFilters.last, .inAList, "no 250 ms wait for a tap")
    }

    func testTheCountsIgnoreTheFilterAndThePillsStayWhenAFilterMatchesNothing() async {
        let repository = FakeRecipeRepository()
        repository.history.send([testSummary(1), testSummary(2)])
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()
        vm.onFilterChange(.inAList)
        await settleMain()

        XCTAssertEqual(vm.uiState.recipes, [])
        XCTAssertEqual(vm.uiState.counts, RecipeCounts(all: 2, inList: 0))
        XCTAssertTrue(vm.uiState.showsFilter)
    }

    func testAnEmptyLibraryHasNoFilterToShow() async {
        let vm = RecipesViewModel(repository: FakeRecipeRepository(), preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()

        XCTAssertFalse(vm.uiState.showsFilter)
    }

    func testTheCleanUpNoteIsOnlyUnderNotInAListWithTheCapWhileThatCleanUpApplies() async {
        let repository = FakeRecipeRepository()
        repository.history.send([saved(1), testSummary(2)])
        // The clean-up is the flag-off behaviour (#345): `oneCookbook` off.
        let flags = FeatureFlags(store: MemoryFeatureFlagStore(["oneCookbook": false]), isDebug: false)
        let store = FakeEntitlements()
        let policy = LibraryPolicy(flags: flags, entitlements: store)
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep, library: policy)
        await settleMain()
        XCTAssertFalse(vm.showsCullNote, "All")

        vm.onFilterChange(.inAList)
        await settleMain()
        XCTAssertFalse(vm.showsCullNote)

        vm.onFilterChange(.notInAList)
        await settleMain()
        XCTAssertTrue(vm.showsCullNote)
        XCTAssertEqual(vm.cullKeep, 50)

        // The free tier and an unlocked library have their own rules: no note.
        let freeFlags = FeatureFlags(store: MemoryFeatureFlagStore(["freeTier": true, "oneCookbook": false]), isDebug: false)
        let free = RecipesViewModel(
            repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep,
            library: LibraryPolicy(flags: freeFlags, entitlements: FakeEntitlements())
        )
        free.onFilterChange(.notInAList)
        await settleMain()
        XCTAssertNil(free.cullKeep)
        XCTAssertFalse(free.showsCullNote)
    }

    func testNoNoteOverAnEmptyNotInAList() async {
        let repository = FakeRecipeRepository()
        repository.history.send([saved(1)])
        let flags = FeatureFlags(store: MemoryFeatureFlagStore(["oneCookbook": false]), isDebug: false)
        let policy = LibraryPolicy(flags: flags, entitlements: FakeEntitlements())
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep, library: policy)
        vm.onFilterChange(.notInAList)
        await settleMain()

        XCTAssertEqual(vm.uiState.recipes, [])
        XCTAssertFalse(vm.showsCullNote)
    }
}

