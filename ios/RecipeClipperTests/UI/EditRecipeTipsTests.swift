import XCTest
@testable import RecipeClipper

/// The editor and a recipe's tips (#432): a box below the steps, only for a recipe that has them.
/// Android's EditRecipeTipsViewModelTest.
@MainActor
final class EditRecipeTipsTests: XCTestCase {

    private var pies: Recipe {
        var recipe = Recipe(
            name: "Mini Apple Pies", image: nil, ingredients: ["1 apple"], instructions: ["Bake."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "https://example.com/pies"
        )
        recipe.id = 3
        recipe.tips = ["Use a stone.", "Or a steel."]
        return recipe
    }

    func testARecipeWithTipsOpensWithItsTipsBoxOneTipPerLine() async {
        let repository = FakeRecipeRepository()
        repository.openResult = pies
        let vm = EditRecipeViewModel(recipeId: 3, repository: repository)
        await settleMain(until: { !vm.uiState.loading })
        XCTAssertTrue(vm.uiState.showsTips)
        XCTAssertEqual(vm.uiState.draft.tipsText, "Use a stone.\nOr a steel.")
        XCTAssertEqual(vm.uiState.draft.instructionsText, "Bake.")
    }

    func testARecipeWithoutTipsAndANewOneHaveNoTipsBox() async {
        let repository = FakeRecipeRepository()
        var plain = pies
        plain.tips = []
        repository.openResult = plain
        let vm = EditRecipeViewModel(recipeId: 3, repository: repository)
        await settleMain(until: { !vm.uiState.loading })
        XCTAssertFalse(vm.uiState.showsTips)
        XCTAssertFalse(EditRecipeViewModel(recipeId: nil, repository: FakeRecipeRepository()).uiState.showsTips)
    }

    private func chat(tipsOn: Bool) -> EditRecipeViewModel {
        let text = "```json\n{\"@type\":\"Recipe\",\"name\":\"Pies\",\"recipeIngredient\":[\"1 apple\"],"
            + "\"recipeInstructions\":[{\"@type\":\"HowToStep\",\"text\":\"Bake.\"},{\"@type\":\"HowToTip\",\"text\":\"Use a stone.\"}]}\n```"
        guard case .editor(let reading) = SharedText.route(text) else { fatalError("not the editor's") }
        return EditRecipeViewModel(recipeId: nil, repository: FakeRecipeRepository(), shared: reading, tipsOn: tipsOn)
    }

    func testAChatsHowToTipFillsTheTipsBoxWithRecipeTipsOn() async {
        let vm = chat(tipsOn: true)
        await settleMain(until: { !vm.uiState.loading })
        XCTAssertTrue(vm.uiState.showsTips)
        XCTAssertEqual(vm.uiState.draft.instructionsText, "Bake.")
        XCTAssertEqual(vm.uiState.draft.tipsText, "Use a stone.")
    }

    func testWithRecipeTipsOffAChatsHowToTipIsTheLastStepAsBefore() async {
        let vm = chat(tipsOn: false)
        await settleMain(until: { !vm.uiState.loading })
        XCTAssertFalse(vm.uiState.showsTips)
        XCTAssertEqual(vm.uiState.draft.instructionsText, "Bake.\nUse a stone.")
        XCTAssertEqual(vm.uiState.draft.tipsText, "")
    }
}
