import XCTest
@testable import RecipeClipper

/// The tags under a Groceries row (#422): Kotlin's `GroceryRecipeTagsTest`, the same cases.
final class GroceryRecipeTagsTests: XCTestCase {

    private func item(_ id: Int64, _ recipeId: Int64?, day: Int64? = nil) -> GroceryItem {
        GroceryItem(
            id: id, text: "1 onion", language: "en", aisle: .produce, checked: false, sortOrder: Int(id),
            recipeId: recipeId, plannedDay: day
        )
    }

    private let titles: [Int64: String] = [1: "Soup", 2: "  Stew  ", 3: "Curry", 4: "Pasta", 5: "Tacos", 6: ""]

    func testATypedItemAndAGoneRecipeHaveNoTags() {
        XCTAssertTrue(GroceryRecipeTags.of([item(1, nil)], recipeTitles: titles).isEmpty)
        XCTAssertTrue(GroceryRecipeTags.of([item(1, 99)], recipeTitles: titles).isEmpty)
        XCTAssertTrue(GroceryRecipeTags.of([item(1, 6)], recipeTitles: titles).isEmpty)
    }

    func testOneTagPerRecipeInTheOrderAddedWithItsDay() {
        let tags = GroceryRecipeTags.of([item(1, 2, day: 100), item(2, 1), item(3, 2, day: 100), item(4, nil)], recipeTitles: titles)
        XCTAssertEqual(tags, [RecipeTag(recipeId: 2, title: "Stew", day: 100), RecipeTag(recipeId: 1, title: "Soup", day: nil)])
    }

    func testTheSameRecipePlannedOnTwoDaysIsTwoTags() {
        let tags = GroceryRecipeTags.of([item(1, 1, day: 100), item(2, 1, day: 103)], recipeTitles: titles)
        XCTAssertEqual(tags, [RecipeTag(recipeId: 1, title: "Soup", day: 100), RecipeTag(recipeId: 1, title: "Soup", day: 103)])
    }

    func testUpToThreeShowWholeAndMoreShowTwoAndPlusN() {
        func tags(_ n: Int) -> [RecipeTag] { (1...max(n, 1)).prefix(n).map { RecipeTag(recipeId: Int64($0), title: "R\($0)", day: nil) } }
        XCTAssertEqual(GroceryRecipeTags.shown(tags(1)).tags, tags(1))
        XCTAssertEqual(GroceryRecipeTags.shown(tags(1)).more, 0)
        XCTAssertEqual(GroceryRecipeTags.shown(tags(3)).tags, tags(3))
        XCTAssertEqual(GroceryRecipeTags.shown(tags(3)).more, 0)
        XCTAssertEqual(GroceryRecipeTags.shown(tags(4)).tags, tags(2))
        XCTAssertEqual(GroceryRecipeTags.shown(tags(4)).more, 2)
        XCTAssertEqual(GroceryRecipeTags.shown(tags(7)).more, 5)
        XCTAssertEqual(GroceryRecipeTags.shown([]).more, 0)
    }

    /// Shop for this week's lines carry the servings the week plans their recipe at.
    func testAWeekLineCarriesItsPlannedServings() {
        let planned = [
            PlannedIngredients(
                entryId: 1, day: 101, servings: 6, recipeId: 7, title: "Pasta", ingredients: ["1 lb spaghetti"],
                yield: nil, language: "en"
            ),
            PlannedIngredients(
                entryId: 2, day: 105, servings: nil, recipeId: 9, title: "Salmon", ingredients: ["2 salmon fillets"],
                yield: nil, language: "en"
            ),
        ]
        let sources = GrocerySources.fromPlan(planned, system: .asWritten, convertLiquids: false)
        let needs = PantryMatch.weekNeeds(sources, pantry: [])
        let lines = needs.buy.flatMap(\.lines)
        XCTAssertEqual(lines.map(\.servings), [6, nil])
        XCTAssertEqual(lines.map(\.recipeId), [7, 9])
    }
}

/// A tag's tap on Groceries (#422): the recipe opens at the servings the week plans it at, or at its
/// own yield when it is not planned. Kotlin's `GroceryRecipeTagsScreenTest`.
@MainActor
final class GroceryRecipeTagsViewModelTests: XCTestCase {
    private let repository = FakeGroceryRepository()
    private let plan = FakeMealPlanRepository()
    private let library = FakeRecipeRepository()

    private func viewModel() async -> GroceriesViewModel {
        let vm = GroceriesViewModel(
            repository: repository, pantry: FakePantryRepository(), calendar: FakePlanCalendar(), mealPlan: plan,
            recipes: library
        )
        await settleMain()
        return vm
    }

    func testARowGetsOneTagPerRecipeAndATypedRowNone() async {
        repository.recipeTitles.value = [1: "Chicken Adobo", 2: "Weeknight Chili"]
        await repository.add([
            NewGroceryLine(text: "200 g flour", language: "en", recipeId: 1),
            NewGroceryLine(text: "100 g flour", language: "en", recipeId: 2, plannedDay: 100),
            NewGroceryLine(text: "milk", language: "en"),
        ])
        let vm = await viewModel()

        let rows = (vm.uiState.sections ?? []).flatMap(\.rows)
        let flour = rows.first { $0.items.contains { $0.text == "200 g flour" } }!
        let milk = rows.first { $0.items.contains { $0.text == "milk" } }!
        XCTAssertEqual(
            vm.uiState.recipeTags[flour.id],
            [RecipeTag(recipeId: 1, title: "Chicken Adobo", day: nil), RecipeTag(recipeId: 2, title: "Weeknight Chili", day: 100)]
        )
        XCTAssertNil(vm.uiState.recipeTags[milk.id])
        // The muted line is still worked out, for the flag off.
        XCTAssertEqual(vm.uiState.recipeLines[flour.id], "Chicken Adobo, Weeknight Chili")
    }

    func testATapOpensThePlannedRecipeAtItsPlannedServings() async {
        repository.recipeTitles.value = [1: "Mushroom omelette"]
        await plan.addRecipe(recipeId: 1, day: 100, mealTypeId: FakeMealPlanRepository.dinner, servings: 6)
        let vm = await viewModel()

        let opened = expectation(description: "opened")
        var got: (Int64, Int?)?
        vm.onOpenRecipe(RecipeTag(recipeId: 1, title: "Mushroom omelette", day: 100)) { id, servings in
            got = (id, servings)
            opened.fulfill()
        }
        await fulfillment(of: [opened], timeout: 2)
        XCTAssertEqual(got?.0, 1)
        XCTAssertEqual(got?.1, 6)
    }

    func testATapOnARecipeNotPlannedOpensItAtItsOwnYield() async {
        let vm = await viewModel()

        let opened = expectation(description: "opened")
        var got: (Int64, Int?)?
        vm.onOpenRecipe(RecipeTag(recipeId: 1, title: "Chicken Adobo", day: nil)) { id, servings in
            got = (id, servings)
            opened.fulfill()
        }
        await fulfillment(of: [opened], timeout: 2)
        XCTAssertEqual(got?.0, 1)
        XCTAssertNil(got?.1)
    }

    // #422 (the owner's addition): a typed item is linked to a recipe from its long-press menu.

    private func summary(_ id: Int64, _ title: String) -> RecipeSummary {
        RecipeSummary(id: id, title: title, imageUrl: nil, totalTime: nil, lastViewedAt: id, isSaved: false)
    }

    func testATypedItemIsLinkedToARecipeWithNoDayWhenItIsNotPlannedThisWeek() async {
        library.history.value = [summary(1, "Chicken Adobo"), summary(2, "Weeknight Chili")]
        await repository.add([NewGroceryLine(text: "2 limes", language: "en")])
        let vm = await viewModel()
        let row = (vm.uiState.sections ?? []).flatMap(\.rows)[0]

        vm.onLinkStart(row)
        await settleMain()
        XCTAssertEqual(vm.uiState.linking?.results?.map(\.title), ["Chicken Adobo", "Weeknight Chili"])
        vm.onLinkPick(2)
        await settleMain()

        XCTAssertNil(vm.uiState.linking)
        XCTAssertEqual(repository.items.value[0].recipeId, 2)
        XCTAssertNil(repository.items.value[0].plannedDay)
    }

    func testARecipePlannedThisWeekLinksOnItsNextPlannedDay() async {
        library.history.value = [summary(1, "Mushroom omelette")]
        let today = FakePlanCalendar().today()
        await plan.addRecipe(recipeId: 1, day: today + 4, mealTypeId: FakeMealPlanRepository.dinner, servings: 3)
        await plan.addRecipe(recipeId: 1, day: today + 2, mealTypeId: FakeMealPlanRepository.dinner, servings: 5)
        await repository.add([NewGroceryLine(text: "6 eggs", language: "en")])
        let vm = await viewModel()

        vm.onLinkStart((vm.uiState.sections ?? []).flatMap(\.rows)[0])
        vm.onLinkPick(1)
        await settleMain()

        XCTAssertEqual(repository.items.value[0].plannedDay, today + 2)
    }

    func testTheLinkIsTakenOffAndAnAppAddedItemIsNeverRelinked() async {
        library.history.value = [summary(1, "Chicken Adobo")]
        repository.recipeTitles.value = [1: "Chicken Adobo"]
        await repository.add([NewGroceryLine(text: "2 limes", language: "en", recipeId: 1)])
        let vm = await viewModel()
        let row = (vm.uiState.sections ?? []).flatMap(\.rows)[0]

        vm.onLinkStart(row)
        XCTAssertNil(vm.uiState.linking, "the app's own link is kept")

        vm.onUnlink(row)
        await settleMain()
        XCTAssertNil(repository.items.value[0].recipeId)
        XCTAssertNil(vm.uiState.recipeTags[row.id])

        vm.onLinkStart((vm.uiState.sections ?? []).flatMap(\.rows)[0])
        XCTAssertNotNil(vm.uiState.linking)
    }
}
