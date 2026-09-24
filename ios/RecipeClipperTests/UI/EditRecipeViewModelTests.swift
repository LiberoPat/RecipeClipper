import XCTest
@testable import RecipeClipper

/// Android's EditRecipeViewModelTest and RecipeUpdateFromSourceTest (#29).
@MainActor
final class EditRecipeViewModelTests: XCTestCase {
    private let soup = Recipe(
        name: "Soup", image: "https://example.com/soup.jpg", ingredients: ["1 onion", "2 carrots"],
        instructions: ["Chop.", "Simmer."], prepTime: "10m", cookTime: nil, totalTime: nil,
        yield: "4", sourceUrl: "https://example.com/soup", id: 3
    )

    func testEditingOpensOnTheRecipesContentOneLinePerIngredientAndStep() async {
        let repository = FakeRecipeRepository()
        repository.openResult = soup
        let vm = EditRecipeViewModel(recipeId: 3, repository: repository)
        await settleMain()

        XCTAssertFalse(vm.uiState.isNew)
        XCTAssertFalse(vm.uiState.loading)
        XCTAssertEqual(vm.uiState.draft.ingredientsText, "1 onion\n2 carrots")
        XCTAssertEqual(vm.uiState.draft.instructionsText, "Chop.\nSimmer.")
        XCTAssertEqual(vm.uiState.draft.prepTime, "10m")
        XCTAssertEqual(vm.uiState.draft.cookTime, "")
    }

    func testSavingAnEditHandsTheDraftToTheRepositoryAndReportsTheId() async {
        let repository = FakeRecipeRepository()
        repository.openResult = soup
        repository.saveEditResult = soup
        let vm = EditRecipeViewModel(recipeId: 3, repository: repository)
        await settleMain()

        var draft = vm.uiState.draft
        draft.name = "Better soup"
        vm.onDraftChange(draft)
        vm.onSave()
        await settleMain()

        XCTAssertEqual(repository.saveEditCalls.map(\.id), [3])
        XCTAssertEqual(repository.saveEditCalls.first?.draft.name, "Better soup")
        XCTAssertEqual(vm.uiState.savedId, 3)
    }

    func testNewRecipeStartsEmptyAndSavesAsManual() async {
        let repository = FakeRecipeRepository()
        var nine = soup
        nine.id = 9
        repository.addManualResult = nine
        let vm = EditRecipeViewModel(recipeId: nil, repository: repository)

        XCTAssertTrue(vm.uiState.isNew)
        XCTAssertFalse(vm.uiState.loading)
        vm.onDraftChange(RecipeDraft(name: "Toast", instructionsText: "Toast the bread."))
        vm.onSave()
        await settleMain()

        XCTAssertEqual(repository.addManualCalls.map(\.name), ["Toast"])
        XCTAssertEqual(vm.uiState.savedId, 9)
    }

    func testAnInvalidDraftIsNotSavedAndTheRuleIsShown() async {
        let repository = FakeRecipeRepository()
        let vm = EditRecipeViewModel(recipeId: nil, repository: repository)

        vm.onDraftChange(RecipeDraft(name: "Toast"))
        vm.onSave()
        await settleMain()

        XCTAssertTrue(vm.uiState.showInvalid)
        XCTAssertTrue(repository.addManualCalls.isEmpty)
        XCTAssertNil(vm.uiState.savedId)
    }

    func testAFailedSaveSaysSoAndStaysOnTheScreen() async {
        let vm = EditRecipeViewModel(recipeId: nil, repository: FakeRecipeRepository()) // addManual answers nil

        vm.onDraftChange(RecipeDraft(name: "Toast", ingredientsText: "Bread"))
        vm.onSave()
        await settleMain()

        XCTAssertTrue(vm.uiState.saveFailed)
        XCTAssertNil(vm.uiState.savedId)
    }

    func testARecipeDeletedElsewhereShowsAsMissingAndCantBeSaved() async {
        let repository = FakeRecipeRepository() // open answers nil
        let vm = EditRecipeViewModel(recipeId: 3, repository: repository)
        await settleMain()

        XCTAssertTrue(vm.uiState.missing)
        vm.onSave()
        XCTAssertTrue(repository.saveEditCalls.isEmpty)
    }

    // MARK: - Update from source, on the recipe screen

    private func edited(_ name: String = "Grandma's soup", origin: ContentOrigin = .edited) -> Recipe {
        var recipe = soup
        recipe.name = name
        recipe.origin = origin
        recipe.editedAt = origin == .parsed ? nil : 5
        return recipe
    }

    private func openRecipe(_ repository: FakeRecipeRepository) async -> RecipeViewModel {
        let vm = RecipeViewModel(
            recipeId: 3, url: nil, repository: repository, preferences: FakeAppPreferences(), clock: TestClock(now: 0)
        )
        await settleMain()
        return vm
    }

    func testUpdateFromSourceIsOfferedOnlyForTheUsersVersionOfALinkedRecipe() {
        XCTAssertTrue(edited(origin: .edited).canUpdateFromSource)
        XCTAssertTrue(edited(origin: .clipped).canUpdateFromSource)
        XCTAssertFalse(edited(origin: .parsed).canUpdateFromSource)
        var manual = edited(origin: .manual)
        manual.sourceUrl = ManualRecipe.newSourceUrl("x")
        XCTAssertFalse(manual.canUpdateFromSource)
    }

    func testUpdateFromSourceSuccessShowsTheSitesVersion() async {
        let repository = FakeRecipeRepository()
        repository.openResult = edited()
        repository.updateFromSourceResult = .success(edited("Soup", origin: .parsed))
        let vm = await openRecipe(repository)

        vm.onUpdateFromSource()
        await settleMain()

        XCTAssertEqual(repository.updateFromSourceCalls, [3])
        XCTAssertEqual(vm.uiState.content.success?.recipe.name, "Soup")
        XCTAssertFalse(vm.uiState.updatingFromSource)
        XCTAssertNil(vm.uiState.updateError)
    }

    func testUpdateFromSourceFailureKeepsTheUsersVersionAndReportsWhyOnce() async {
        let repository = FakeRecipeRepository()
        repository.openResult = edited()
        repository.updateFromSourceResult = .error(.offline)
        let vm = await openRecipe(repository)

        vm.onUpdateFromSource()
        await settleMain()

        XCTAssertEqual(vm.uiState.content.success?.recipe.name, "Grandma's soup")
        XCTAssertEqual(vm.uiState.updateError, .offline)
        vm.onUpdateErrorShown()
        XCTAssertNil(vm.uiState.updateError)
    }

    func testUpdateFromSourceDoesNothingForAParsedRecipe() async {
        let repository = FakeRecipeRepository()
        repository.openResult = edited(origin: .parsed)
        let vm = await openRecipe(repository)

        vm.onUpdateFromSource()
        await settleMain()

        XCTAssertTrue(repository.updateFromSourceCalls.isEmpty)
    }
}
