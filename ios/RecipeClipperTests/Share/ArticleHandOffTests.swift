import XCTest
@testable import RecipeClipper

/// A recipe written inside a shared page's article (#435): the share extension can't open the
/// editor, so it leaves what it read for the app (`PendingArticle`), as it leaves shared text.
@MainActor
final class ArticleHandOffTests: XCTestCase {
    private let url = "https://recetas.example.com/articulo-mejillones.html"

    private var article: ArticleRecipe {
        ArticleRecipe(
            name: "Los mejillones de lata", image: nil,
            ingredients: ["1 lata de mejillones.", "100 gramos de parmesano.", "1 huevo."],
            steps: ["Mezclamos el queso con el huevo."], language: "es", sourceUrl: url
        )
    }

    private func pending() -> PendingArticle {
        PendingArticle(defaults: UserDefaults(suiteName: "ArticleHandOff-\(UUID().uuidString)")!)
    }

    func testTheArticlesRecipeIsLeftForTheAppsEditor() async {
        let pending = pending()
        let vm = ShareImportViewModel(
            repository: ReconnectCountingRepository(.error(.inArticle([article]))), clock: DataTestClock(5_000),
            pendingArticle: pending
        )
        vm.start(with: SharedInput(url: url))
        await vm.currentLoad?.value

        XCTAssertEqual(vm.uiState, .articleInApp)
        XCTAssertEqual(pending.take(now: 5_000 + PendingArticle.window), [article])
        XCTAssertNil(pending.take(now: 5_000), "taken once")
    }

    func testANewShareClearsTheArticleLeftBefore() async {
        let pending = pending()
        pending.put([article], at: 5_000)
        var recipe = Recipe(
            name: "Scones", image: nil, ingredients: ["2 cups flour"], instructions: ["Mix."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "https://example.com/scones"
        )
        recipe.id = 7
        let vm = ShareImportViewModel(
            repository: ReconnectCountingRepository(.success(recipe)), clock: DataTestClock(5_000), pendingArticle: pending
        )
        vm.start(with: SharedInput(url: "https://example.com/scones"))
        await vm.currentLoad?.value

        XCTAssertEqual(vm.uiState, .saved(title: "Scones"))
        XCTAssertNil(pending.take(now: 5_000))
    }

    func testWithNowhereToLeaveItTheCardSaysWhy() async {
        let vm = ShareImportViewModel(repository: ReconnectCountingRepository(.error(.inArticle([article]))))
        vm.start(with: SharedInput(url: url))
        await vm.currentLoad?.value
        XCTAssertEqual(vm.uiState, .failed(.inArticle([article])))
    }

    func testSeveralAreAllLeftForTheAppsChooser() async {
        let pending = pending()
        var second = article
        second.name = "Crema de tomate"
        second.sourceUrl = url + "?pith_recipe=2"
        let vm = ShareImportViewModel(
            repository: ReconnectCountingRepository(.error(.inArticle([article, second]))), clock: DataTestClock(5_000),
            pendingArticle: pending
        )
        vm.start(with: SharedInput(url: url))
        await vm.currentLoad?.value
        XCTAssertEqual(vm.uiState, .articleInApp)
        let left = pending.take(now: 5_000)
        XCTAssertEqual(left, [article, second])
        XCTAssertEqual(left.map(Route.article), .articleChoice([article, second]))
        XCTAssertEqual(Route.article([article]), .articleRecipe(article))
    }

    func testAnOlderHandOffIsDropped() {
        let pending = pending()
        pending.put([article], at: 1_000)
        XCTAssertNil(pending.take(now: 1_000 + PendingArticle.window + 1))
    }
}
