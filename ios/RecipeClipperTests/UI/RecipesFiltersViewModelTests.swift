import XCTest
@testable import RecipeClipper

/// Recipes' views folded into the filter sheet (#454), as the ViewModel serves them: each control narrows the
/// list (the views through the repository's own view filters, the rest as #378's), they combine with each other
/// and the sort, the badge counts the pills, a pill's × and Clear undo them. Android's `RecipesFiltersViewModelTest`.
@MainActor
final class RecipesFiltersViewModelTests: XCTestCase {

    private func summary(
        _ id: Int64, _ title: String, total: String? = nil, saved: Bool = false, cooked: Int64? = nil,
        url: String? = nil, chat: Bool = false
    ) -> RecipeSummary {
        RecipeSummary(
            id: id, title: title, imageUrl: nil, totalTime: total, lastViewedAt: id, isSaved: saved,
            lastCookedDay: cooked, sourceUrl: url ?? "https://site.com/\(id)", isFromChat: chat, language: "en"
        )
    }

    private var repository: FakeRecipeRepository!

    // 1 typed, loose, cooked, quick; 2 typed, listed; 3 from a chat, loose; 4 parsed, loose, cooked, slow; 5 parsed, listed, quick.
    private func makeVM(preferences: FakeAppPreferences = FakeAppPreferences()) async -> RecipesViewModel {
        repository = FakeRecipeRepository()
        repository.history.send([
            summary(5, "Egg", total: "10m", saved: true),
            summary(4, "Dal", total: "2h", cooked: 20_001),
            summary(3, "Curry", url: "manual:chat-3", chat: true),
            summary(2, "Bread", saved: true, url: "manual:b"),
            summary(1, "Apple", total: "15m", cooked: 20_000, url: "manual:a")
        ])
        let vm = RecipesViewModel(repository: repository, preferences: preferences, sleep: immediateSleep)
        await settleMain()
        return vm
    }

    private func change(_ vm: RecipesViewModel, _ choices: RecipeFilterChoices) async {
        vm.onFilterChoicesChange(choices)
        await settleMain()
    }

    private func ids(_ vm: RecipesViewModel) -> [Int64]? { vm.uiState.recipes?.map(\.id) }

    func testNothingChosenListsEveryRecipeWithNoPillAndNoBadge() async {
        let vm = await makeVM()
        XCTAssertEqual(ids(vm), [5, 4, 3, 2, 1])
        XCTAssertEqual(vm.uiState.choices.pills, [])
        XCTAssertFalse(vm.uiState.filtersOn)
    }

    func testEachControlNarrowsTheListToWhatItsViewOrFilterSelects() async {
        let vm = await makeVM()
        let cases: [(RecipeFilterChoices, [Int64])] = [
            (RecipeFilterChoices(thirtyOrLess: true), [5, 1]),
            (RecipeFilterChoices(cooked: .cooked), [4, 1]),
            (RecipeFilterChoices(cooked: .notYet), [5, 3, 2]),
            (RecipeFilterChoices(notInAList: true), [4, 3, 1]),
            (RecipeFilterChoices(origin: .typed), [2, 1]),
            (RecipeFilterChoices(origin: .chat), [3])
        ]
        for (choices, expected) in cases {
            await change(vm, choices)
            XCTAssertEqual(ids(vm), expected, "\(choices)")
            XCTAssertTrue(vm.uiState.filtersOn)
            XCTAssertEqual(vm.uiState.choices.pills.count, 1)
        }
        // The views went to the repository as the view screens' own filters.
        XCTAssertTrue(repository.historyFilters.contains(.cooked))
        XCTAssertTrue(repository.historyFilters.contains(.typed))
        XCTAssertTrue(repository.historyFilters.contains(.chat))
    }

    func testTheControlsCombineWithEachOtherAndTheSort() async {
        let vm = await makeVM()
        await change(vm, RecipeFilterChoices(cooked: .cooked, notInAList: true))
        XCTAssertEqual(ids(vm), [4, 1])

        var next = vm.uiState.choices
        next.origin = .typed
        await change(vm, next)
        XCTAssertEqual(ids(vm), [1])

        await change(vm, RecipeFilterChoices(thirtyOrLess: true, cooked: .notYet))
        XCTAssertEqual(ids(vm), [5])

        await change(vm, RecipeFilterChoices(notInAList: true))
        vm.onSortChange(.name)
        XCTAssertEqual(ids(vm), [1, 3, 4])

        // Two views nothing satisfies at once: none, and the count line stays.
        await change(vm, RecipeFilterChoices(cooked: .cooked, origin: .chat))
        XCTAssertEqual(ids(vm), [])
        XCTAssertTrue(vm.showsSortLine)
    }

    func testCookedAndCameFromAreOneChoiceEach() async {
        let vm = await makeVM()
        await change(vm, RecipeFilterChoices(cooked: .cooked, origin: .typed))
        var next = vm.uiState.choices
        next.cooked = .notYet
        next.origin = .chat
        await change(vm, next)
        XCTAssertEqual(vm.uiState.choices.pills, [.notCooked, .chat])
        XCTAssertEqual(ids(vm), [3])
    }

    func testTheBadgeCountsThePillsAPillsXUndoesItsFilterAndClearUndoesThemAll() async {
        let vm = await makeVM()
        await change(vm, RecipeFilterChoices(thirtyOrLess: true, cooked: .cooked, notInAList: true, origin: .typed))
        XCTAssertEqual(vm.uiState.choices.pills, [.thirtyOrLess, .cooked, .notInAList, .typed])
        XCTAssertEqual(ids(vm), [1])

        vm.onRemoveFilter(.cooked)
        await settleMain()
        XCTAssertEqual(vm.uiState.choices.cooked, .any)
        XCTAssertEqual(vm.uiState.choices.pills.count, 3)
        XCTAssertEqual(ids(vm), [1])

        vm.onClearQuickFilters()
        await settleMain()
        XCTAssertTrue(vm.uiState.choices.isEmpty)
        XCTAssertFalse(vm.uiState.filtersOn)
        XCTAssertEqual(ids(vm)?.count, 5)
    }

    func testTheSheetsPantrySwitchTurnsOnTheYouHaveLinesAsTheChipDid() async {
        let vm = await makeVM()
        XCTAssertFalse(vm.uiState.pantryOn)
        await change(vm, RecipeFilterChoices(usesPantry: true))
        XCTAssertTrue(vm.uiState.pantryOn)
        // No pantry repository: nothing is covered, so nothing matches, never a guess.
        XCTAssertEqual(ids(vm), [])
        XCTAssertEqual(vm.uiState.choices.pills, [.usesPantry])
    }
}
