import XCTest
@testable import RecipeClipper

/// The chips under Recipes' search (#378) and the layout toggle (#380), as the ViewModel serves
/// them. The matching itself is `RecipeQuickFilters`, pinned by the corpus and its own tests.
@MainActor
final class RecipesQuickFiltersViewModelTests: XCTestCase {

    private func summary(
        _ id: Int64, total: String? = nil, cooked: Int64? = nil, title: String? = nil
    ) -> RecipeSummary {
        RecipeSummary(
            id: id, title: title ?? "Recipe \(id)", imageUrl: nil, totalTime: total, lastViewedAt: id,
            isSaved: false, lastCookedDay: cooked, language: "en"
        )
    }

    private func item(_ name: String, inStock: Bool = true, id: Int64 = 1) -> PantryItem {
        PantryItem(
            id: id, name: name, quantity: nil, language: "en", aisle: .other, inStock: inStock, alwaysHave: false,
            purchasedDay: nil, expiresDay: nil
        )
    }

    private func makeVM(
        _ repository: FakeRecipeRepository, preferences: AppPreferences = FakeAppPreferences(),
        pantry: PantryRepository? = nil, view: RecipeFilter? = nil
    ) -> RecipesViewModel {
        RecipesViewModel(
            repository: repository, preferences: preferences, sleep: immediateSleep, view: view, pantry: pantry
        )
    }

    func testTheChipsCombineWithEachOtherAndTheSortAndTurnOffAgain() async {
        let repository = FakeRecipeRepository()
        repository.history.send([
            summary(1, total: "20m", title: "Zucchini Fritters"),
            summary(2, total: "25m", cooked: 20_000, title: "Apple Tart"),
            summary(3, total: "2h", title: "Brisket"),
            summary(4, total: "10m", title: "Miso Soup"),
            summary(5, total: "Overnight", title: "Cured Salmon")
        ])
        let vm = makeVM(repository, preferences: FakeAppPreferences(recipeSort: .name))
        await settleMain()
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [2, 3, 5, 4, 1], "all five, A to Z")

        vm.onQuickFilterToggle(.thirtyOrLess)
        XCTAssertEqual(vm.uiState.recipes?.map(\.title), ["Apple Tart", "Miso Soup", "Zucchini Fritters"],
                       "30 min or less reads the stored time; Overnight never matches; the sort still applies")

        vm.onQuickFilterToggle(.notCooked)
        XCTAssertEqual(vm.uiState.recipes?.map(\.title), ["Miso Soup", "Zucchini Fritters"], "the chips combine")
        XCTAssertEqual(vm.uiState.listed, 2, "the count is of what is shown")

        vm.onQuickFilterToggle(.thirtyOrLess)
        vm.onQuickFilterToggle(.notCooked)
        XCTAssertEqual(vm.uiState.recipes?.count, 5)
        XCTAssertTrue(vm.uiState.quick.isEmpty)
    }

    func testAChipOnWithNothingMatchingKeepsTheLineAndAnEmptyList() async {
        let repository = FakeRecipeRepository()
        repository.history.send([summary(1, total: "2h")])
        let vm = makeVM(repository)
        await settleMain()

        vm.onQuickFilterToggle(.thirtyOrLess)
        XCTAssertEqual(vm.uiState.recipes, [])
        XCTAssertTrue(vm.showsSortLine, "the count and the toggle stay while a chip is on")
        XCTAssertTrue(vm.uiState.showsFilter, "so do the chips, to turn it off")
    }

    func testTheChipsComposeWithTheSearchAndTheNewListAnswersThemToo() async {
        let repository = FakeRecipeRepository()
        repository.history.send([summary(1, total: "20m"), summary(2, total: "3h")])
        let vm = makeVM(repository)
        await settleMain()
        vm.onQuickFilterToggle(.thirtyOrLess)

        // What the database answers for a search is narrowed by the chip as well.
        vm.onQueryChange("soup")
        repository.history.send([summary(2, total: "3h"), summary(3, total: "15m")])
        await settleMain()
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [3])
        XCTAssertEqual(repository.historyQueries.last, "soup")
    }

    func testNotCookedIsOfferedNowhereInsideTheCookedView() {
        let inCooked = makeVM(FakeRecipeRepository(), view: .cooked)
        XCTAssertEqual(inCooked.uiState.quickFilters, [.thirtyOrLess, .usesPantry])
        let whole = makeVM(FakeRecipeRepository())
        XCTAssertEqual(whole.uiState.quickFilters, [.thirtyOrLess, .notCooked, .usesPantry])
    }

    // MARK: - Uses what's in my pantry

    func testThePantryChipReadsEveryRecipesLinesOnlyWhileItIsOn() async {
        let repository = FakeRecipeRepository()
        repository.history.send([summary(1), summary(2), summary(3)])
        repository.recipeLines.send([
            SavedRecipe(id: 1, title: "Pasta", language: "en", lines: ["200 g pasta", "1 cup milk"]),
            SavedRecipe(id: 2, title: "Cake", language: "en", lines: ["2 cups flour", "1 cup sugar", "3 eggs"]),
            SavedRecipe(id: 3, title: "Soup", language: "en", lines: ["For the broth:", "1 onion", "2 carrots"])
        ])
        let pantry = FakePantryRepository([item("pasta", id: 1), item("milk", inStock: false, id: 2), item("onion", id: 3)])
        let vm = makeVM(repository, pantry: pantry)
        await settleMain()
        XCTAssertEqual(repository.recipeLinesSubscriptions, 0, "nothing is read while the chip is off")

        vm.onQuickFilterToggle(.usesPantry)
        await settleMain(until: { vm.uiState.recipes != nil && !vm.uiState.coverage.isEmpty })
        XCTAssertEqual(repository.recipeLinesSubscriptions, 1)
        // Half is enough (pasta of pasta and milk; onion of onion and carrots); the cake has none.
        XCTAssertEqual(vm.uiState.recipes?.map(\.id), [1, 3])
        XCTAssertEqual(vm.uiState.coverage[1], PantryCoverage(have: 1, total: 2))
        XCTAssertEqual(vm.uiState.coverage[3], PantryCoverage(have: 1, total: 2))

        vm.onQuickFilterToggle(.usesPantry)
        XCTAssertEqual(vm.uiState.recipes?.count, 3, "off again, every recipe")
        XCTAssertTrue(vm.uiState.coverage.isEmpty, "and no 'You have' lines")
    }

    func testNothingIsShownWhileThePantryChipsAnswerIsOnItsWay() async {
        let repository = FakeRecipeRepository()
        repository.history.send([summary(1)])
        let vm = makeVM(repository, pantry: FakePantryRepository())
        await settleMain()
        XCTAssertEqual(vm.uiState.recipes?.count, 1)

        vm.onQuickFilterToggle(.usesPantry)
        // Matched off the main actor: until then neither "no recipes" nor every recipe is claimed.
        XCTAssertNil(vm.uiState.recipes)
        await settleMain(until: { vm.uiState.recipes != nil })
        XCTAssertEqual(vm.uiState.recipes, [], "no lines, no match")
    }

    func testThePantryIsMatchedAgainOnlyWhenItsInputsChange() async {
        let repository = FakeRecipeRepository()
        repository.history.send([summary(1)])
        let lines = [SavedRecipe(id: 1, title: "Pasta", language: "en", lines: ["200 g pasta"])]
        repository.recipeLines.send(lines)
        let pantry = FakePantryRepository([item("pasta")])
        let vm = makeVM(repository, pantry: pantry)
        await settleMain()
        vm.onQuickFilterToggle(.usesPantry)
        await settleMain(until: { vm.uiState.recipes?.count == 1 })
        XCTAssertEqual(vm.coverageMatchings, 1)

        // A tick or a cook step rewrites a recipe without changing a line: the same lines again.
        repository.recipeLines.send(lines)
        await settleMain()
        XCTAssertEqual(vm.coverageMatchings, 1)

        pantry.items.send([])
        await settleMain(until: { vm.uiState.recipes?.isEmpty == true })
        XCTAssertEqual(vm.coverageMatchings, 2)
        XCTAssertEqual(vm.uiState.recipes, [], "the pantry emptied, the recipe no longer matches")
    }

    // MARK: - The layout

    func testTheLayoutIsStoredAtOnceAndFollowsWhatIsStored() async {
        let preferences = FakeAppPreferences()
        let vm = makeVM(FakeRecipeRepository(), preferences: preferences)
        XCTAssertEqual(vm.uiState.layout, .rows)

        vm.onLayoutChange(.grid)
        XCTAssertEqual(preferences.recipesLayout, .grid)
        XCTAssertEqual(vm.uiState.layout, .grid)

        preferences.recipesLayout = .rows // Settings, or another screen of the same ViewModel
        await settleMain()
        XCTAssertEqual(vm.uiState.layout, .rows)

        let stored = makeVM(FakeRecipeRepository(), preferences: FakeAppPreferences(recipesLayout: .grid))
        XCTAssertEqual(stored.uiState.layout, .grid, "the first frame is the stored choice")
    }
}
