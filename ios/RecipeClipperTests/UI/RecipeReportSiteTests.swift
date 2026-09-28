import XCTest
@testable import RecipeClipper

/// "Report this site" is offered only for a shared link whose page held no recipe: never for
/// a block, offline or a failed fetch, which mean "try again", and never for a saved recipe.
/// Mirrors the report cases in Android's RecipeViewModelTest.
@MainActor
final class RecipeReportSiteTests: XCTestCase {

    private let link = "https://example.com/recipe"

    private func viewModel(
        id: Int64? = nil,
        url: String?,
        repository: FakeRecipeRepository,
        appInfo: AppInfo = StaticAppInfo()
    ) -> RecipeViewModel {
        let clock = TestClock()
        return RecipeViewModel(
            recipeId: id, url: url, repository: repository, preferences: FakeAppPreferences(),
            clock: clock, sleep: clock.sleep, appInfo: appInfo
        )
    }

    private func repository(_ result: ParseResult) -> FakeRecipeRepository {
        let repository = FakeRecipeRepository()
        repository.importResult = result
        return repository
    }

    func testASharedLinkWithNoRecipeOffersAReportBuiltFromTheLinkAndAppInfo() async {
        let info = StaticAppInfo(platform: "iOS 18.2", appVersion: "2.1 (7)")
        let vm = viewModel(url: link, repository: repository(.error(.noRecipeFound)), appInfo: info)
        await settleMain()

        XCTAssertEqual(
            vm.uiState.reportSiteUrl,
            SiteReportLink.issueUrl(link: link, platform: "iOS 18.2", appVersion: "2.1 (7)")
        )
    }

    func testASharedLinkWithNoRecipeOffersToClipIt() async {
        let vm = viewModel(url: link, repository: repository(.error(.noRecipeFound)))
        await settleMain()
        XCTAssertEqual(vm.uiState.clipUrl, link)

        let blocked = viewModel(url: link, repository: repository(.error(.blocked(httpStatus: 403))))
        await settleMain()
        XCTAssertNil(blocked.uiState.clipUrl)
    }

    func testErrorsThatMeanTryAgainNeverOfferAReport() async {
        let kinds: [ParseError] = [
            .blocked(httpStatus: 403), .offline, .fetchFailed("HTTP 400"),
            .fetchFailed("The request timed out.", timedOut: true), .saveFailed,
        ]
        for kind in kinds {
            let vm = viewModel(url: link, repository: repository(.error(kind)))
            await settleMain()
            XCTAssertNil(vm.uiState.reportSiteUrl, "\(kind)")
        }
    }

    func testARedditPostWithNoTranscriptionOffersNeitherAReportNorAClip() async {
        // #11: an outcome, not an unsupported page; the screen shows the post with Try again only.
        let reddit = "https://www.reddit.com/r/Old_Recipes/comments/1abc02/grandmas_pie/"
        let vm = viewModel(url: reddit, repository: repository(.error(.noTranscription(title: "Pie", imageUrl: nil))))
        await settleMain()
        XCTAssertNil(vm.uiState.reportSiteUrl)
        XCTAssertNil(vm.uiState.clipUrl)
        XCTAssertNil(vm.uiState.photoPost, "no picture, nothing to read")
    }

    func testARedditPostWithAPhotoCanHaveItRead() async {
        // #198: every picture, in order, under the shared link.
        let reddit = "https://www.reddit.com/r/Old_Recipes/comments/1abc02/grandmas_pie/"
        let gallery = ["https://preview.redd.it/front.jpg", "https://preview.redd.it/back.jpg"]
        let error = ParseError.noTranscription(title: "Pie", imageUrl: gallery[0], imageUrls: gallery)
        let vm = viewModel(url: reddit, repository: repository(.error(error)))
        await settleMain()
        XCTAssertEqual(vm.uiState.photoPost, PhotoPost(url: reddit, title: "Pie", imageUrls: gallery))

        // A single photo with no list (as a test or an older caller builds it) still reads.
        let single = viewModel(url: reddit, repository: repository(.error(.noTranscription(title: "Pie", imageUrl: gallery[0]))))
        await settleMain()
        XCTAssertEqual(single.uiState.photoPost?.imageUrls, [gallery[0]])
    }

    func testASavedRecipeThatCantBeOpenedHasNoPageToReport() async {
        let repository = FakeRecipeRepository()
        repository.openResult = nil
        let vm = viewModel(id: 1, url: nil, repository: repository)
        await settleMain()
        XCTAssertNil(vm.uiState.reportSiteUrl)
    }

    func testTryingAgainClearsTheReportAndARecipeThatThenLoadsHasNone() async {
        let repository = repository(.error(.noRecipeFound))
        let vm = viewModel(url: link, repository: repository)
        await settleMain()
        XCTAssertNotNil(vm.uiState.reportSiteUrl)

        repository.importResult = .success(Recipe(
            name: "Soup", image: nil, ingredients: ["1 cup water"], instructions: ["Heat."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: "2", sourceUrl: link, id: 1
        ))
        vm.onRetry()
        XCTAssertNil(vm.uiState.reportSiteUrl) // gone while loading, too
        await settleMain()
        XCTAssertNotNil(vm.uiState.content.success)
        XCTAssertNil(vm.uiState.reportSiteUrl)
    }
}
