import XCTest
@testable import RecipeClipper

/// Text shared in (#306): the review, split or as written. Android's EditRecipeTextViewModelTest.
@MainActor
final class EditRecipeTextTests: XCTestCase {

    private func viewModel(_ text: String, repository: FakeRecipeRepository = FakeRecipeRepository()) -> EditRecipeViewModel {
        EditRecipeViewModel(recipeId: nil, repository: repository, sharedText: text)
    }

    private func saved(_ id: Int64) -> Recipe {
        var recipe = Recipe(
            name: "Kuchen", image: nil, ingredients: ["500 g Mehl"], instructions: [], prepTime: nil,
            cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "manual:abc"
        )
        recipe.id = id
        return recipe
    }

    func testARecipeInTheTextIsSplitIntoTheFieldsWithNoNameGuessed() async {
        let vm = viewModel("Grandma's scones\nServes 4\nIngredients:\n- 2 cups flour\n- 1 tsp salt\nMethod:\n1. Mix.\n2. Bake.")
        XCTAssertTrue(vm.uiState.loading)
        await settleMain(until: { !vm.uiState.loading })

        let state = vm.uiState
        XCTAssertTrue(state.shared)
        XCTAssertTrue(state.isNew)
        XCTAssertEqual(state.sharedSorted, true)
        XCTAssertNil(state.photo)
        XCTAssertEqual(state.draft.name, "")
        XCTAssertEqual(state.draft.yield, "Serves 4")
        XCTAssertEqual(state.draft.ingredientsText, "2 cups flour\n1 tsp salt")
        XCTAssertEqual(state.draft.instructionsText, "Mix.\nBake.")
    }

    func testTextThatDoesNotSplitIsEveryLineAStepAsWritten() async {
        let vm = viewModel("Hi Mum,\nMix the flour with the butter and bake it.\n\nLove, Sam")
        await settleMain(until: { !vm.uiState.loading })

        XCTAssertEqual(vm.uiState.sharedSorted, false)
        XCTAssertEqual(vm.uiState.draft.ingredientsText, "")
        XCTAssertEqual(vm.uiState.draft.instructionsText, "Hi Mum,\nMix the flour with the butter and bake it.\nLove, Sam")
    }

    func testNothingIsSavedUntilSaveThenAsTheCooksOwnInTheTextsLanguage() async {
        let repository = FakeRecipeRepository()
        repository.addManualResult = saved(7)
        let vm = viewModel(
            "Zutaten:\n- 500 g Mehl\n- 2 EL Zucker\n\nZubereitung:\n1. Mehl und Zucker mischen.\n2. Backen.",
            repository: repository
        )
        await settleMain(until: { !vm.uiState.loading })
        XCTAssertTrue(repository.addManualCalls.isEmpty)

        var draft = vm.uiState.draft
        draft.name = "Kuchen"
        vm.onDraftChange(draft)
        vm.onSave()
        await settleMain(until: { vm.uiState.savedId != nil })

        XCTAssertEqual(repository.addManualCalls.map(\.name), ["Kuchen"])
        XCTAssertEqual(repository.addManualLanguages, ["de"])
        XCTAssertEqual(vm.uiState.savedId, 7)
    }

    func testWithNoNameSavePointsOutTheRuleAndSavesNothing() async {
        let repository = FakeRecipeRepository()
        let vm = viewModel("Ingredients:\n- 2 cups flour\nMethod:\n1. Mix.", repository: repository)
        await settleMain(until: { !vm.uiState.loading })

        vm.onSave()

        XCTAssertTrue(vm.uiState.showInvalid)
        XCTAssertTrue(repository.addManualCalls.isEmpty)
    }
}
