import XCTest
@testable import RecipeClipper

/// Recipe versions (#354) on the recipe screen's ViewModel: the original a version is based on and
/// a recipe's own versions follow the repository once it is loaded, behind the `recipeVersions`
/// flag. Android's RecipeVersionsTest (its ViewModel side).
@MainActor
final class RecipeVersionsViewModelTests: XCTestCase {

    private func recipe(_ id: Int64, _ name: String, url: String) -> Recipe {
        var recipe = Recipe(
            name: name, image: nil, ingredients: ["2 cups tomatoes"], instructions: ["Simmer."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: "4 servings", sourceUrl: url
        )
        recipe.id = id
        return recipe
    }

    private func viewModel(_ shown: Recipe, _ repository: FakeRecipeRepository, flags: FeatureFlags? = nil) -> RecipeViewModel {
        repository.openResult = shown
        return RecipeViewModel(
            recipeId: shown.id, url: nil, repository: repository, preferences: FakeAppPreferences(),
            clock: TestClock(), flags: flags
        )
    }

    func testAVersionKnowsItsOriginal() async {
        let repository = FakeRecipeRepository()
        repository.basedOn.send([2: RecipeLink(id: 1, title: "Tomato Soup")])
        let vm = viewModel(recipe(2, "Spicy Tomato Soup", url: "manual:aaaa"), repository)
        await settleMain(until: { vm.uiState.basedOn != nil })

        XCTAssertEqual(vm.uiState.basedOn, RecipeLink(id: 1, title: "Tomato Soup"))
        XCTAssertEqual(vm.uiState.versions, [])
    }

    func testAnOriginalListsItsVersionsNewestFirst() async {
        let repository = FakeRecipeRepository()
        let versions = [RecipeLink(id: 3, title: "Even spicier soup"), RecipeLink(id: 2, title: "Spicy Tomato Soup")]
        repository.versions.send([1: versions])
        let vm = viewModel(recipe(1, "Tomato Soup", url: "https://example.com/soup"), repository)
        await settleMain(until: { !vm.uiState.versions.isEmpty })

        XCTAssertEqual(vm.uiState.versions, versions)
        XCTAssertNil(vm.uiState.basedOn)
    }

    func testTheLinksFollowTheRepositoryWhileTheScreenIsOpen() async {
        let repository = FakeRecipeRepository()
        let vm = viewModel(recipe(1, "Tomato Soup", url: "https://example.com/soup"), repository)
        await settleMain(until: { vm.uiState.content.success != nil })
        XCTAssertEqual(vm.uiState.versions, [])

        repository.versions.send([1: [RecipeLink(id: 2, title: "Spicy")]])
        await settleMain(until: { !vm.uiState.versions.isEmpty })
        XCTAssertEqual(vm.uiState.versions.map(\.title), ["Spicy"])

        repository.versions.send([:])
        await settleMain(until: { vm.uiState.versions.isEmpty })
        XCTAssertEqual(vm.uiState.versions, [])
    }

    func testWithTheFlagOffNothingIsFollowed() async {
        let repository = FakeRecipeRepository()
        repository.basedOn.send([2: RecipeLink(id: 1, title: "Tomato Soup")])
        let flags = FeatureFlags(store: MemoryFeatureFlagStore(["recipeVersions": false]), isDebug: true)
        let vm = viewModel(recipe(2, "Spicy Tomato Soup", url: "manual:aaaa"), repository, flags: flags)
        await settleMain(until: { vm.uiState.content.success != nil })
        await settleMain()

        XCTAssertNil(vm.uiState.basedOn)
        XCTAssertEqual(vm.uiState.versions, [])
    }
}
