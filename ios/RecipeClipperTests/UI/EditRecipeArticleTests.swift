import XCTest
@testable import RecipeClipper

/// Recipes written inside an article (#435): the import hands them on in its own place (the editor
/// on one, the chooser over several), the chooser lists them and which are saved, and the editor
/// opens filled in from one, saved only on Save, as a clip under its own link. Past Cloudflare's
/// check (#220) the clip view hands them on the same way. Android's `EditRecipeArticleViewModelTest`.
@MainActor
final class EditRecipeArticleTests: XCTestCase {
    private let url = "https://recetas.example.com/articulo-mejillones.html"

    private var article: ArticleRecipe {
        ArticleRecipe(
            name: "Bolas de parmesano",
            image: "https://cdn.example.com/mejillones.jpg",
            ingredients: ["1 lata de mejillones en escabeche.", "100-120 gramos de queso parmesano rallado.", "1 huevo."],
            steps: ["Rallamos el parmesano y lo mezclamos con el huevo.", "Freímos las bolas durante dos minutos."],
            language: "es",
            sourceUrl: url,
            headline: "Los cocineros coinciden: los mejillones de lata"
        )
    }

    private var second: ArticleRecipe {
        var second = article
        second.name = "Crema de tomate"
        second.steps = ["Trituramos los tomates."]
        second.sourceUrl = url + "?pith_recipe=2"
        return second
    }

    private func failed(_ error: ParseError) -> RecipeUiState {
        FailedLoad(shareUrl: url, appInfo: StaticAppInfo(), redditOn: { true }).shown(RecipeUiState(), error)
    }

    func testOneFoundIsHandedOnInTheImportsPlaceWithNoErrorScreen() {
        let shown = failed(.inArticle([article]))
        XCTAssertEqual(shown.articleRecipes, [article])
        XCTAssertEqual(shown.content, RecipeUiState().content)
        XCTAssertNil(shown.clipUrl)
        XCTAssertNil(shown.reportSiteUrl)
        XCTAssertEqual(Route.article(shown.articleRecipes ?? []), .articleRecipe(article))
    }

    func testSeveralFoundAreAllHandedOnForTheChooser() {
        let shown = failed(.inArticle([article, second]))
        XCTAssertEqual(shown.articleRecipes, [article, second])
        XCTAssertEqual(Route.article(shown.articleRecipes ?? []), .articleChoice([article, second]))
    }

    func testTheEditorOpensFilledInFromThePageAsWritten() {
        let repository = FakeRecipeRepository()
        let vm = EditRecipeViewModel(recipeId: nil, repository: repository, article: article)
        let state = vm.uiState
        XCTAssertFalse(state.loading)
        XCTAssertTrue(state.article)
        XCTAssertEqual(state.draft.name, article.name)
        XCTAssertEqual(state.draft.image, article.image)
        XCTAssertEqual(state.draft.ingredientsText, article.ingredients.joined(separator: "\n"))
        XCTAssertEqual(state.draft.instructionsText, article.steps.joined(separator: "\n"))
        XCTAssertTrue(repository.saveClipCalls.isEmpty, "nothing is saved until Save; Back saves nothing")
        XCTAssertTrue(repository.addManualCalls.isEmpty)
    }

    func testSaveKeepsTheCheckedRecipeAsAClipUnderItsOwnLinkInItsLanguage() async {
        let repository = FakeRecipeRepository()
        let vm = EditRecipeViewModel(recipeId: nil, repository: repository, article: second)
        var draft = vm.uiState.draft
        draft.name = "Crema de tomate seco"
        vm.onDraftChange(draft)
        vm.onSave()
        await settleMain(until: { vm.uiState.savedId != nil })

        let saved = repository.saveClipCalls.first
        XCTAssertEqual(repository.saveClipCalls.count, 1)
        XCTAssertEqual(saved?.name, "Crema de tomate seco")
        XCTAssertEqual(saved?.sourceUrl, url + "?pith_recipe=2")
        XCTAssertEqual(saved?.sourceType, .blog)
        XCTAssertEqual(saved?.language, "es")
        XCTAssertEqual(vm.uiState.savedId, 1)
    }

    func testAFullLibraryIsNotLeftAsAClips() async {
        let repository = FakeRecipeRepository()
        repository.saveClipResult = .notKept(Recipe(
            name: article.name, image: nil, ingredients: article.ingredients, instructions: article.steps,
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: url
        ))
        let vm = EditRecipeViewModel(recipeId: nil, repository: repository, article: article)
        vm.onSave()
        await settleMain(until: { vm.uiState.libraryFull })
        XCTAssertTrue(vm.uiState.libraryFull)
        XCTAssertNil(vm.uiState.savedId)
    }

    func testTheChooserListsEachWithItsCountsAndASavedOneOnceItIsSaved() async {
        let repository = FakeRecipeRepository()
        let vm = ArticleChoiceViewModel(articles: [article, second], repository: repository)
        XCTAssertEqual(vm.uiState.headline, article.headline)
        XCTAssertEqual(vm.uiState.image, article.image)
        XCTAssertEqual(vm.uiState.rows, [
            ArticleChoiceRow(index: 0, name: "Bolas de parmesano", ingredients: 3, steps: 2),
            ArticleChoiceRow(index: 1, name: "Crema de tomate", ingredients: 3, steps: 1),
        ])

        // Back from the editor, which saved the second.
        repository.savedLinks[url + "?pith_recipe=2"] = 9
        vm.onShown()
        await vm.currentRefresh?.value
        XCTAssertEqual(vm.uiState.rows.map(\.savedId), [nil, 9])
    }

    private func clip(_ repository: FakeRecipeRepository) -> ClipViewModel {
        ClipViewModel(url: url, repository: repository, drafts: ClipDraftStore(), check: true)
    }

    func testPastTheCheckOneFoundIsHandedOnInTheClipsPlace() async {
        let repository = FakeRecipeRepository()
        repository.importPageResults["<html>page</html>"] = .error(.inArticle([article]))
        let vm = clip(repository)
        vm.onPageLoaded("<html>page</html>")
        await vm.currentCheck?.value
        XCTAssertEqual(vm.uiState.articleRecipes, [article])
        // Nothing is read again while it is handed on.
        vm.onPageLoaded("<html>other</html>")
        await vm.currentCheck?.value
        XCTAssertEqual(repository.importPageCalls.count, 1)
    }

    func testPastTheCheckSeveralFoundAreHandedOnForTheChooser() async {
        let repository = FakeRecipeRepository()
        repository.importPageResults["<html>page</html>"] = .error(.inArticle([article, second]))
        let vm = clip(repository)
        vm.onPageLoaded("<html>page</html>")
        await vm.currentCheck?.value
        XCTAssertEqual(vm.uiState.articleRecipes?.count, 2)
    }

    func testPastTheCheckNoneFoundOffersTheClipAsBefore() async {
        let repository = FakeRecipeRepository()
        repository.importPageResults["<html>page</html>"] = .error(.noRecipeFound)
        let vm = clip(repository)
        vm.onPageLoaded("<html>page</html>")
        await vm.currentCheck?.value
        XCTAssertNil(vm.uiState.articleRecipes)
        XCTAssertEqual(vm.uiState.check, .noRecipe)
    }

    func testTheEditorReplacesTheImportSoBackWithoutSavingLeavesForWhereTheShareCameFrom() {
        let router = Router()
        router.path = [.recipes, .importUrl(url)]
        router.replace(last: 1, with: .article([article]))
        XCTAssertEqual(router.path, [.recipes, .articleRecipe(article)])
        router.path.removeLast() // Back, nothing saved
        XCTAssertEqual(router.path, [.recipes])

        router.path = [.recipes, .importUrl(url)]
        router.replace(last: 1, with: .article([article, second]))
        router.path.append(.articleRecipe(second))
        router.path.removeLast() // Back from the editor: the chooser
        XCTAssertEqual(router.path, [.recipes, .articleChoice([article, second])])
    }

    func testASavedEditorOverTheChooserReturnsToItAndBackFromTheEditorAlone() {
        let router = Router()
        router.path = [.articleChoice([article, second]), .articleRecipe(second)]
        router.openSavedClip(5)
        XCTAssertEqual(router.path, [.articleChoice([article, second]), .recipe(id: 5)])

        // One found: the editor replaced the import, so a save leaves only the recipe.
        router.path = [.articleRecipe(article)]
        router.openSavedClip(6)
        XCTAssertEqual(router.path, [.recipe(id: 6)])
    }
}
