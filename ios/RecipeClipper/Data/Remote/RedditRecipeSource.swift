import Foundation

/// A Reddit post: one fetch of its public `.json` listing (the post and its comment tree
/// together), handed to `RedditRecipeParser`. A share link (`/r/<sub>/s/<code>`) is followed to
/// the post first, since only the post's own address has a listing. Android's
/// `RedditRecipeSource`.
///
/// The endpoint is public and unauthenticated, so heavy use can meet HTTP 429: that is
/// `.blocked`, like any other refusal. Failures map to causes exactly as in `BlogRecipeSource`.
final class RedditRecipeSource: RecipeSource {
    /// Reddit asks API clients to say who they are, in this shape.
    static let userAgent = "ios:com.example.recipeclipper:1.0 (RecipeClipper)"

    private let session: URLSession
    private let base: String

    /// `base` is where the listing is fetched from; a parameter only so a test can change it.
    init(session: URLSession = BlogRecipeSource.defaultSession, base: String = RedditUrls.defaultBase) {
        self.session = session
        self.base = base
    }

    func fetch(url: String) async -> ParseResult {
        var postUrl = url
        if RedditUrls.isShareLink(url) {
            switch await get(url) {
            case .failure(let error):
                return .error(error)
            case .success(let (_, landed, status)):
                guard RedditUrls.jsonUrl(landed, base: base) != nil else {
                    return .error((200..<300).contains(status) ? .noRecipeFound : ParseError.forHttpStatus(status))
                }
                postUrl = landed
            }
        }
        guard let jsonUrl = RedditUrls.jsonUrl(postUrl, base: base) else { return .error(.noRecipeFound) }
        switch await get(jsonUrl) {
        case .failure(let error):
            return .error(error)
        case .success(let (data, _, status)):
            guard (200..<300).contains(status) else { return .error(ParseError.forHttpStatus(status)) }
            return RedditRecipeParser.parse(String(decoding: data, as: UTF8.self), sourceUrl: url)
        }
    }

    /// Reddit's rendered pages hold no recipe data the blog parsers read, and their text is a
    /// whole thread: a block stays a block, and no page text goes to the on-device model.
    func readsRenderedPage(url: String) -> Bool { false }

    /// One GET, following redirects: the body, the address it finally came from, and the
    /// status. A transport failure is already a cause.
    private func get(_ address: String) async -> Result<(Data, String, Int), ParseError> {
        guard let target = URL(string: address),
              let scheme = target.scheme?.lowercased(), scheme == "http" || scheme == "https"
        else { return .failure(.fetchFailed(URLError(.badURL).localizedDescription)) }
        var request = URLRequest(url: target, timeoutInterval: BlogRecipeSource.timeout)
        request.httpMethod = "GET"
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        do {
            let (data, response) = try await session.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 200
            return .success((data, response.url?.absoluteString ?? address, status))
        } catch {
            // As BlogRecipeSource: a cancelled fetch reports nothing; the repository discards it.
            if error is CancellationError || (error as? URLError)?.code == .cancelled {
                return .failure(.fetchFailed(nil))
            }
            return .failure(BlogRecipeSource.cause(of: error))
        }
    }
}

/// The `RecipeSource` the repository sees: Reddit links go to `reddit`, everything else to
/// `blog`. Chosen by host alone (`RedditUrls.isReddit`), and only while `redditOn` (the
/// `reddit` flag, #11): off, a Reddit link is read like any page, as before.
struct RoutingRecipeSource: RecipeSource {
    let blog: RecipeSource
    let reddit: RecipeSource
    var redditOn: () -> Bool = { true }

    private func source(for url: String) -> RecipeSource {
        redditOn() && RedditUrls.isReddit(url) ? reddit : blog
    }

    func fetch(url: String) async -> ParseResult { await source(for: url).fetch(url: url) }

    func fetchPage(url: String) async -> FetchedPage { await source(for: url).fetchPage(url: url) }

    func readsRenderedPage(url: String) -> Bool { source(for: url).readsRenderedPage(url: url) }
}
