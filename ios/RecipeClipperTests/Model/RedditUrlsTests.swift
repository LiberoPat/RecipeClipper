import XCTest
@testable import RecipeClipper

/// The same cases and expectations as Android's `RedditUrlsTest`.
final class RedditUrlsTests: XCTestCase {

    func testRedditHostsAreRecognisedLookalikesAreNot() {
        for url in [
            "https://www.reddit.com/r/recipes/comments/abc/x/",
            "https://reddit.com/r/recipes/comments/abc/x/",
            "https://old.reddit.com/r/recipes/comments/abc/x/",
            "https://m.reddit.com/r/recipes/comments/abc/x/",
            "https://redd.it/abc",
            "https://www.reddit.com/r/recipes/s/AbCd123",
        ] { XCTAssertTrue(RedditUrls.isReddit(url), url) }
        for url in [
            "https://www.notreddit.com/r/recipes/comments/abc/x/",
            "https://reddit.com.evil.example/r/x/comments/abc/",
            "https://www.seriouseats.com/reddit-recipe",
            "not a url",
        ] { XCTAssertFalse(RedditUrls.isReddit(url), url) }
    }

    func testAPostLinkBecomesItsJsonListingOnWwwQueryAndFragmentDropped() {
        let expected = "https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo.json?raw_json=1&limit=200"
        XCTAssertEqual(RedditUrls.jsonUrl("https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo/"), expected)
        XCTAssertEqual(RedditUrls.jsonUrl("https://old.reddit.com/r/recipes/comments/1abc01/lemon_orzo/?share_id=x#top"), expected)
        XCTAssertEqual(RedditUrls.jsonUrl("https://reddit.com/r/recipes/comments/1abc01/lemon_orzo"), expected)
        XCTAssertEqual(RedditUrls.jsonUrl("https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo.json"), expected)
    }

    func testShortAndSluglessForms() {
        XCTAssertEqual(RedditUrls.jsonUrl("https://redd.it/1abc01"),
                       "https://www.reddit.com/comments/1abc01.json?raw_json=1&limit=200")
        XCTAssertEqual(RedditUrls.jsonUrl("https://www.reddit.com/gallery/1abc03"),
                       "https://www.reddit.com/comments/1abc03.json?raw_json=1&limit=200")
        XCTAssertEqual(RedditUrls.jsonUrl("https://www.reddit.com/r/recipes/comments/1abc01"),
                       "https://www.reddit.com/r/recipes/comments/1abc01.json?raw_json=1&limit=200")
    }

    func testALinkToOneCommentKeepsThatCommentsThread() {
        XCTAssertEqual(
            RedditUrls.jsonUrl("https://www.reddit.com/r/Old_Recipes/comments/1abc02/card/k3xyz/"),
            "https://www.reddit.com/r/Old_Recipes/comments/1abc02/card/k3xyz.json?raw_json=1&limit=200"
        )
    }

    func testTheBaseIsSwappableForTests() {
        XCTAssertEqual(RedditUrls.jsonUrl("https://redd.it/abc", base: "http://127.0.0.1:8080/"),
                       "http://127.0.0.1:8080/comments/abc.json?raw_json=1&limit=200")
    }

    func testLinksThatArentOnePostHaveNoListing() {
        XCTAssertNil(RedditUrls.jsonUrl("https://www.reddit.com/r/recipes/"))
        XCTAssertNil(RedditUrls.jsonUrl("https://www.reddit.com/user/someone/"))
        XCTAssertNil(RedditUrls.jsonUrl("https://www.reddit.com/r/recipes/s/AbCd123"))
        XCTAssertNil(RedditUrls.jsonUrl("https://redd.it/"))
        XCTAssertNil(RedditUrls.jsonUrl("not a url"))
    }

    func testShareLinksAreMarkedForARedirect() {
        XCTAssertTrue(RedditUrls.isShareLink("https://www.reddit.com/r/recipes/s/AbCd123"))
        XCTAssertTrue(RedditUrls.isShareLink("https://reddit.com/r/Old_Recipes/s/AbCd123/"))
        XCTAssertFalse(RedditUrls.isShareLink("https://www.reddit.com/r/recipes/comments/abc/x/"))
        XCTAssertFalse(RedditUrls.isShareLink("https://redd.it/abc"))
    }
}
