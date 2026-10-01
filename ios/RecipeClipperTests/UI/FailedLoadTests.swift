import XCTest
@testable import RecipeClipper

/// `FailedLoad` on its own (#234; Android's FailedLoadTest): where a load that failed goes.
final class FailedLoadTests: XCTestCase {
    private let appInfo = StaticAppInfo()
    private let link = "https://example.com/cake"
    private let post = "https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo/"

    private func shared(_ url: String?, redditOn: Bool = true) -> FailedLoad {
        FailedLoad(shareUrl: url, appInfo: appInfo, redditOn: { redditOn })
    }

    func testASharedPageWithNoRecipeOffersTheClipAndTheReport() {
        let shown = shared(link).shown(RecipeUiState(), .noRecipeFound)

        XCTAssertEqual(shown.content, .error(.noRecipeFound))
        XCTAssertEqual(shown.clipUrl, link)
        XCTAssertEqual(
            shown.reportSiteUrl,
            SiteReportLink.issueUrl(link: link, platform: appInfo.platform, appVersion: appInfo.appVersion)
        )
    }

    func testARecipeOpenedByIdHasNoPageToClipOrReport() {
        let shown = shared(nil).shown(RecipeUiState(), .noRecipeFound)

        XCTAssertEqual(shown.content, .error(.noRecipeFound))
        XCTAssertNil(shown.clipUrl)
        XCTAssertNil(shown.reportSiteUrl)
    }

    func testABlockThatUsuallyLiftsOffersNeither() {
        let shown = shared(link).shown(RecipeUiState(), .blocked(httpStatus: 403))

        XCTAssertEqual(shown.content, .error(.blocked(httpStatus: 403)))
        XCTAssertNil(shown.clipUrl)
        XCTAssertNil(shown.reportSiteUrl)
    }

    func testAWalledRedditPostOpensTheClipInTheScreensPlace() {
        let shown = shared(post).shown(RecipeUiState(), .blocked(httpStatus: 403))

        XCTAssertEqual(shown.clipBlockedPost, post)
        XCTAssertEqual(shown.content, .loading)
    }

    func testWithTheRedditFlagOffAWalledPostIsAnErrorLikeAnyOther() {
        let shown = shared(post, redditOn: false).shown(RecipeUiState(), .blocked(httpStatus: 403))

        XCTAssertNil(shown.clipBlockedPost)
        XCTAssertEqual(shown.content, .error(.blocked(httpStatus: 403)))
    }

    func testCloudflaresCheckOpensThePageForTheCookToPass() {
        let shown = shared(link).shown(RecipeUiState(), .humanCheck)

        XCTAssertEqual(shown.humanCheckPage, link)
        XCTAssertEqual(shown.content, .loading)
    }

    func testAPostWithNoRecipeTextButAPhotoCanHaveItsPhotoRead() {
        let error = ParseError.noTranscription(title: "Lemon orzo", imageUrl: "https://i.redd.it/a.jpg")

        let shown = shared(post).shown(RecipeUiState(), error)

        XCTAssertEqual(shown.photoPost, PhotoPost(url: post, title: "Lemon orzo", imageUrls: ["https://i.redd.it/a.jpg"]))
        let noPhoto = ParseError.noTranscription(title: "Lemon orzo", imageUrl: nil)
        XCTAssertNil(shared(post).shown(RecipeUiState(), noPhoto).photoPost)
    }
}
