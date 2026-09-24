import XCTest
@testable import RecipeClipper

/// Stands in for Reddit: a share link (`/s/`) redirects to the post, a `.json` path answers
/// with `jsonStatus` and `jsonBody`, anything else with an empty page. Records every path.
final class RedditStubURLProtocol: URLProtocol {
    static var jsonStatus = 200
    static var jsonBody = RedditFixtures.selfPost
    static var offline = false
    static var requests: [String] = []

    static func reset() {
        jsonStatus = 200
        jsonBody = RedditFixtures.selfPost
        offline = false
        requests = []
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        Self.requests.append(url.path + (url.query.map { "?" + $0 } ?? ""))
        if Self.offline {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        if url.path.contains("/s/") {
            let target = URL(string: "/r/recipes/comments/1abc01/lemon_orzo/?share_id=x", relativeTo: url)!.absoluteURL
            let response = HTTPURLResponse(url: url, statusCode: 301, httpVersion: "HTTP/1.1",
                                           headerFields: ["Location": target.absoluteString])!
            // The session follows it with a fresh request to this protocol.
            client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: target), redirectResponse: response)
            return
        }
        let isJson = url.path.hasSuffix(".json")
        let response = HTTPURLResponse(url: url, statusCode: isJson ? Self.jsonStatus : 200,
                                       httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data((isJson ? Self.jsonBody : "<html></html>").utf8))
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

    func testAShareLinkIsFollowedToThePostFirst() async {
        let share = "\(base)/r/recipes/s/AbCd123"
        let result = await source.fetch(url: share)
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.sourceUrl, share)
        XCTAssertEqual(RedditStubURLProtocol.requests.first, "/r/recipes/s/AbCd123")
        XCTAssertEqual(RedditStubURLProtocol.requests.last, "/r/recipes/comments/1abc01/lemon_orzo.json?raw_json=1&limit=200")
    }

    func testAPostWithNoRecipeTextIsNoTranscription() async {
        RedditStubURLProtocol.jsonBody = RedditFixtures.photoOnly
        let result = await source.fetch(url: postUrl)
        guard case .error(.noTranscription) = result else { return XCTFail("\(result)") }
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
        var fetched: [String] = []
        init(_ result: ParseResult) { self.result = result }
        func fetch(url: String) async -> ParseResult {
            fetched.append(url)
            return result
        }
    }

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
}
