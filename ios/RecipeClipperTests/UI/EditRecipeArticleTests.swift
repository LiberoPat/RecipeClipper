import XCTest
@testable import RecipeClipper

/// A recipe written inside an article (#435): the import's screen offers it, and the editor opens
/// filled in from it, saved only on Save, as a clip under the page's link. Android's
/// `EditRecipeArticleViewModelTest`.
@MainActor
final class EditRecipeArticleTests: XCTestCase {
    private let url = "https://recetas.example.com/articulo-mejillones.html"

    private var article: ArticleRecipe {
        ArticleRecipe(
            name: "Los cocineros coinciden: los mejillones de lata",
            image: "https://cdn.example.com/mejillones.jpg",
            ingredients: ["1 lata de mejillones en escabeche.", "100-120 gramos de queso parmesano rallado.", "1 huevo."],
            steps: ["Rallamos el parmesano y lo mezclamos con el huevo.", "Freímos las bolas durante dos minutos."],
            language: "es",
            sourceUrl: url
        )
    }

    func testTheImportsScreenOffersTheArticlesRecipeWithNothingElse() {
        let shown = FailedLoad(shareUrl: url, appInfo: StaticAppInfo(), redditOn: { true })
            .shown(RecipeUiState(), .inArticle(article))
        XCTAssertEqual(shown.content, .error(.inArticle(article)))
        // Clip it yourself and Report this site are for pages where nothing was found.
        XCTAssertNil(shown.clipUrl)
        XCTAssertNil(shown.reportSiteUrl)
        XCTAssertNil(shown.photoPost)
    }

    func testTheEditorOpensFilledInFromThePageAsWritten() {
        let repository = FakeRecipeRepository()
        let vm = EditRecipeViewModel(recipeId: nil, repository: repository, article: article)
        let state = vm.uiState
        XCTAssertFalse(state.loading)
        XCTAssertTrue(state.article)
        XCTAssertTrue(state.isNew)
        XCTAssertEqual(state.draft.name, article.name)
        XCTAssertEqual(state.draft.image, article.image)
        XCTAssertEqual(state.draft.ingredientsText, article.ingredients.joined(separator: "\n"))
        XCTAssertEqual(state.draft.instructionsText, article.steps.joined(separator: "\n"))
        XCTAssertTrue(repository.saveClipCalls.isEmpty, "nothing is saved until Save")
    }

    func testSaveKeepsTheCheckedRecipeAsAClipUnderThePagesLinkInItsLanguage() async {
        let repository = FakeRecipeRepository()
        let vm = EditRecipeViewModel(recipeId: nil, repository: repository, article: article)
        var draft = vm.uiState.draft
        draft.name = "Bolas de parmesano con mejillones"
        vm.onDraftChange(draft)
        vm.onSave()
        await settleMain(until: { vm.uiState.savedId != nil })

        let saved = repository.saveClipCalls.first
        XCTAssertEqual(repository.saveClipCalls.count, 1)
        XCTAssertEqual(saved?.name, "Bolas de parmesano con mejillones")
        XCTAssertEqual(saved?.sourceUrl, url)
        XCTAssertEqual(saved?.sourceType, .blog)
        XCTAssertEqual(saved?.ingredients, article.ingredients)
        XCTAssertEqual(saved?.instructions, article.steps)
        XCTAssertEqual(saved?.image, article.image)
        XCTAssertEqual(saved?.language, "es")
        XCTAssertEqual(vm.uiState.savedId, 1)
        XCTAssertTrue(repository.addManualCalls.isEmpty)
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
}
