import XCTest
@testable import RecipeClipper

/// Stands in for Reddit: a share link (`/s/`) redirects to the post as Reddit does (with the
/// share's tracking query), a `.json` path answers from `listings` by path, else with
/// `jsonStatus` and `jsonBody`, anything else with an empty page. Records every path.
final class RedditStubURLProtocol: URLProtocol {
    static var jsonStatus = 200
    static var jsonBody = RedditFixtures.selfPost
    static var listings: [String: String] = [:]
    static var listingStatus: [String: Int] = [:]
    static var offline = false
    static var requests: [String] = []

    static let shareTarget = "/r/recipes/comments/1f4b2cd/weeknight_lemon_chicken_orzo/?share_id=oqqXWtvgcCuonkcpck8yD"
        + "&utm_content=1&utm_medium=android_app&utm_name=androidcss&utm_source=share&utm_term=1"

    static func reset() {
        jsonStatus = 200
        jsonBody = RedditFixtures.selfPost
        listings = [:]
        listingStatus = [:]
        offline = false
        requests = []
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        // The path as sent, trailing slash kept (`URL.path` drops it).
        let sent = URLComponents(url: url, resolvingAgainstBaseURL: true)?.percentEncodedPath ?? url.path
        Self.requests.append(sent + (url.query.map { "?" + $0 } ?? ""))
        if Self.offline {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        if url.path.contains("/s/") {
            let target = URL(string: Self.shareTarget, relativeTo: url)!.absoluteURL
            let response = HTTPURLResponse(url: url, statusCode: 301, httpVersion: "HTTP/1.1",
                                           headerFields: ["Location": target.absoluteString])!
            // The session follows it with a fresh request to this protocol.
            client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: target), redirectResponse: response)
            return
        }
        let isJson = url.path.hasSuffix(".json")
        let status = isJson ? Self.listingStatus[url.path] ?? Self.jsonStatus : 200
        let body = isJson ? Self.listings[url.path] ?? Self.jsonBody : "<html></html>"
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// `RedditRecipeSource` over a stubbed network (Android's `RedditRecipeSourceTest` uses a local
/// socket for the same cases), plus the host routing in front of it.
final class RedditRecipeSourceTests: XCTestCase {
    private var source: RedditRecipeSource!
    private let base = "https://stub.example"
    private let postUrl = "https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo/?utm_source=share"

    override func setUp() {
        RedditStubURLProtocol.reset()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [RedditStubURLProtocol.self]
        source = RedditRecipeSource(session: URLSession(configuration: config), base: base)
    }

    override func tearDown() {
        RedditStubURLProtocol.reset()
    }

    func testOneFetchOfThePostsJsonListingMakesTheRecipe() async {
        let result = await source.fetch(url: postUrl)
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.name, "Weeknight Lemon Chicken Orzo")
        XCTAssertEqual(recipe.sourceType, .reddit)
        XCTAssertEqual(recipe.sourceUrl, postUrl)
        XCTAssertEqual(RedditStubURLProtocol.requests, ["/r/recipes/comments/1abc01/lemon_orzo.json?raw_json=1&limit=200"])
    }

    func testAShareLinkIsFollowedToThePostFirstAndTheSharesQueryIsLeftOffTheListing() async {
        let share = "\(base)/r/recipes/s/AbCd123"
        let result = await source.fetch(url: share)
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.sourceUrl, share)
        XCTAssertEqual(RedditStubURLProtocol.requests, [
            "/r/recipes/s/AbCd123",
            RedditStubURLProtocol.shareTarget,
            "/r/recipes/comments/1f4b2cd/weeknight_lemon_chicken_orzo.json?raw_json=1&limit=200",
        ])
    }

    func testAPostWithNoRecipeTextIsNoTranscription() async {
        RedditStubURLProtocol.jsonBody = RedditFixtures.photoOnly
        let result = await source.fetch(url: postUrl)
        guard case .error(.noTranscription) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(RedditStubURLProtocol.requests.count, 1)
    }

    func testACrosspostWithNoRecipeOfItsOwnReadsTheOriginalsComments() async {
        RedditStubURLProtocol.jsonBody = RedditFixtures.crosspost
        RedditStubURLProtocol.listings = ["/comments/1f3k9xq.json": RedditFixtures.imageWithOpRecipe]
        let result = await source.fetch(url: postUrl)
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.name, "Sticky Honey Garlic Chicken Thighs")
        XCTAssertEqual(recipe.ingredients[4], "1/3 cup honey")
        XCTAssertEqual(recipe.sourceUrl, postUrl)
        XCTAssertEqual(RedditStubURLProtocol.requests.last, "/comments/1f3k9xq.json?raw_json=1&limit=200")
    }

    func testWhenTheOriginalHasNoRecipeOrWontLoadTheCrosspostsOwnOutcomeStands() async {
        RedditStubURLProtocol.jsonBody = RedditFixtures.crosspost
        RedditStubURLProtocol.listings = ["/comments/1f3k9xq.json": RedditFixtures.photoOnly]
        let expected = ParseResult.error(.noTranscription(
            title: "Saw this on r/recipes and had to share",
            imageUrl: "https://preview.redd.it/k2m8x7vq1abd1.jpeg?auto=webp&s=5c1e0f1a2b3c4d5e6f",
            imageUrls: ["https://preview.redd.it/k2m8x7vq1abd1.jpeg?auto=webp&s=5c1e0f1a2b3c4d5e6f"]
        ))
        let noRecipe = await source.fetch(url: postUrl)
        XCTAssertEqual(noRecipe, expected)
        RedditStubURLProtocol.listingStatus = ["/comments/1f3k9xq.json": 429]
        let blocked = await source.fetch(url: postUrl)
        XCTAssertEqual(blocked, expected)
    }

    func test429FromThePublicEndpointIsBlocked() async {
        RedditStubURLProtocol.jsonStatus = 429
        let result = await source.fetch(url: postUrl)
        XCTAssertEqual(result, .error(.blocked(httpStatus: 429)))
    }

    func testA500IsBlockedAndA400IsAPlainFetchFailure() async {
        RedditStubURLProtocol.jsonStatus = 500
        let serverError = await source.fetch(url: postUrl)
        XCTAssertEqual(serverError, .error(.blocked(httpStatus: 500)))
        RedditStubURLProtocol.jsonStatus = 400
        let badRequest = await source.fetch(url: postUrl)
        XCTAssertEqual(badRequest, .error(.fetchFailed("HTTP 400")))
    }

    func testARedditLinkThatIsntAPostIsNoRecipeFoundWithoutAFetch() async {
        let result = await source.fetch(url: "https://www.reddit.com/r/recipes/")
        XCTAssertEqual(result, .error(.noRecipeFound))
        XCTAssertTrue(RedditStubURLProtocol.requests.isEmpty)
    }

    func testOfflineIsOffline() async {
        RedditStubURLProtocol.offline = true
        let result = await source.fetch(url: postUrl)
        XCTAssertEqual(result, .error(.offline))
    }

    // MARK: - Routing

    private final class RecordingSource: RecipeSource {
        let result: ParseResult
        let page: PageText?
        let readsPages: Bool
        var fetched: [String] = []
        init(_ result: ParseResult, page: PageText? = nil, readsPages: Bool = true) {
            self.result = result
            self.page = page
            self.readsPages = readsPages
        }
        func fetch(url: String) async -> ParseResult {
            fetched.append(url)
            return result
        }
        func fetchPage(url: String) async -> FetchedPage { FetchedPage(result: await fetch(url: url), page: page) }
        func readsRenderedPage(url: String) -> Bool { readsPages }
    }

    private let redditPost = "https://www.reddit.com/r/recipes/comments/abc/x/"

    func testRoutingSendsRedditHostsToTheRedditSourceAndEverythingElseToTheBlogOne() async {
        let blog = RecordingSource(.error(.noRecipeFound))
        let reddit = RecordingSource(.error(.offline))
        let router = RoutingRecipeSource(blog: blog, reddit: reddit)

        _ = await router.fetch(url: "https://www.reddit.com/r/recipes/comments/abc/x/")
        _ = await router.fetch(url: "https://old.reddit.com/r/recipes/comments/abc/x/")
        _ = await router.fetch(url: "https://redd.it/abc")
        _ = await router.fetch(url: "https://www.seriouseats.com/reddit-inspired-pasta")
        _ = await router.fetch(url: "https://www.notreddit.com/r/x/comments/abc/")

        XCTAssertEqual(reddit.fetched.count, 3)
        XCTAssertEqual(blog.fetched, ["https://www.seriouseats.com/reddit-inspired-pasta", "https://www.notreddit.com/r/x/comments/abc/"])
    }

    func testWithTheRedditFlagOffARedditLinkGoesToTheBlogSourceAsBefore() async {
        let blog = RecordingSource(.error(.noRecipeFound))
        let reddit = RecordingSource(.error(.offline), readsPages: false)
        let router = RoutingRecipeSource(blog: blog, reddit: reddit, redditOn: { false })

        _ = await router.fetch(url: redditPost)

        XCTAssertEqual(blog.fetched, [redditPost])
        XCTAssertTrue(reddit.fetched.isEmpty)
        XCTAssertTrue(router.readsRenderedPage(url: redditPost))
    }

    func testRoutingPassesFetchPageThroughSoABlogPagesTextStillReachesTheModel() async {
        let text = PageText(title: "Soup", lines: ["A story about soup."])
        let blog = RecordingSource(.error(.noRecipeFound), page: text)
        let router = RoutingRecipeSource(blog: blog, reddit: RecordingSource(.error(.offline)))

        let fetched = await router.fetchPage(url: "https://example.com/soup")
        XCTAssertEqual(fetched.page, text)
    }

    func testARedditPostNeverGoesToTheRenderedPageABlogPageDoes() {
        let router = RoutingRecipeSource(blog: RecordingSource(.error(.noRecipeFound)), reddit: RedditRecipeSource())
        XCTAssertFalse(router.readsRenderedPage(url: redditPost))
        XCTAssertTrue(router.readsRenderedPage(url: "https://example.com/soup"))
    }
}
