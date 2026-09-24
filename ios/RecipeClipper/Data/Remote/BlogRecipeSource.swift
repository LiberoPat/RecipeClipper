import Foundation

/// Fetches a blog / recipe-site page and hands its JSON-LD blocks to JsonLdRecipeParser, or,
/// when they hold no recipe, the page to MicrodataRecipeParser.
/// Pure network + parse; persists nothing (Android's BlogRecipeSource).
final class BlogRecipeSource: RecipeSource {
    static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) RecipeClipper/1.0"
    static let timeout: TimeInterval = 15

    /// The session used in the app. `URLRequest.timeoutInterval` is only an *idle* timeout (the
    /// longest gap between packets), so a server trickling bytes could hold an import open
    /// indefinitely. Jsoup's `timeout(15000)` on Android caps the whole request, connect plus
    /// full body read; `timeoutIntervalForResource` is the equivalent here.
    static let defaultSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout
        return URLSession(configuration: config)
    }()

    private let session: URLSession

    init(session: URLSession = BlogRecipeSource.defaultSession) {
        self.session = session
    }

    func fetch(url: String) async -> ParseResult {
        // http(s) only, as Jsoup enforces on Android. URLSession would otherwise happily read a
        // file: URL out of the app's own sandbox.
        guard let target = URL(string: url),
              let scheme = target.scheme?.lowercased(), scheme == "http" || scheme == "https"
        else {
            return .error(.fetchFailed(URLError(.badURL).localizedDescription))
        }
        var request = URLRequest(url: target, timeoutInterval: Self.timeout)
        request.httpMethod = "GET"
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            // A cancelled fetch isn't a failure to report. `fetch` can't throw, so the caller
            // (DefaultRecipeRepository) checks Task.isCancelled and discards this, writing
            // nothing; the detail is left nil so no platform "cancelled" text is ever shown.
            if error is CancellationError || (error as? URLError)?.code == .cancelled {
                return .error(.fetchFailed(nil))
            }
            return .error(Self.cause(of: error))
        }

        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            // 403/404/429/5xx are usually a bot block that lifts on its own (see
            // ParseError.blocked); anything else stays a plain fetch failure naming the status.
            return .error(ParseError.forHttpStatus(http.statusCode))
        }

        let html = Self.decode(data, textEncodingName: response.textEncodingName)
        return Self.parse(html: html, url: url)
    }

    /// The URLError codes that mean there is no connection at all, as opposed to a connection
    /// that failed. These become `.offline`: not retried, and reloaded on reconnect.
    static let offlineCodes: Set<URLError.Code> = [
        .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed, .internationalRoamingOff,
    ]

    /// A transport failure as a cause: offline, a timeout (not retried automatically), or any
    /// other failure with the platform's message as its detail.
    static func cause(of error: Error) -> ParseError {
        if let code = (error as? URLError)?.code {
            if offlineCodes.contains(code) { return .offline }
            if code == .timedOut { return .fetchFailed(error.localizedDescription, timedOut: true) }
        }
        return .fetchFailed(error.localizedDescription)
    }

    /// Bytes to text the way Jsoup does it: the charset the server declared, else one declared
    /// in a `<meta>` near the top of the page, else UTF-8 — decoded leniently, so one stray
    /// invalid byte becomes U+FFFD instead of turning the whole page into Latin-1 mojibake
    /// ("CrÃ¨me brÃ»lÃ©e"), which is what a strict-UTF-8-else-Latin-1 fallback did.
    static func decode(_ data: Data, textEncodingName: String?) -> String {
        if let name = textEncodingName ?? metaCharset(in: data),
           let encoding = encoding(named: name),
           encoding != .utf8,
           let text = String(data: data, encoding: encoding) {
            return text
        }
        return String(decoding: data, as: UTF8.self)
    }

    private static func encoding(named name: String) -> String.Encoding? {
        var iana = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        // As browsers do (WHATWG Encoding): a page labelled Latin-1 or ASCII is really
        // windows-1252, whose 0x80–0x9F are the curly quotes and dashes recipe text is full of.
        if ["iso-8859-1", "iso8859-1", "latin1", "l1", "us-ascii", "ascii"].contains(iana) {
            iana = "windows-1252"
        }
        let cf = CFStringConvertIANACharSetNameToEncoding(iana as CFString)
        guard cf != kCFStringEncodingInvalidId else { return nil }
        return String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cf))
    }

    private static let metaCharsetPattern = try! NSRegularExpression(
        pattern: #"<meta[^>]+charset\s*=\s*["']?\s*([A-Za-z0-9_.:\-]+)"#,
        options: [.caseInsensitive]
    )

    /// A `<meta charset=…>` or `<meta http-equiv="Content-Type" content="…; charset=…">` in the
    /// first 1024 bytes (where the HTML spec says it must be). Read as Latin-1, which maps
    /// every byte to one character, so the ASCII markup is found whatever the real encoding.
    private static func metaCharset(in data: Data) -> String? {
        guard let head = String(data: data.prefix(1024), encoding: .isoLatin1) else { return nil }
        let ns = head as NSString
        guard let match = metaCharsetPattern.firstMatch(in: head, range: NSRange(location: 0, length: ns.length))
        else { return nil }
        return ns.substring(with: match.range(at: 1))
    }

    /// The HTML-to-recipe step, separate from the network so it can be tested without one.
    static func parse(html: String, url: String) -> ParseResult {
        let blocks = JsonLdRecipeParser.extractJsonLdBlocks(fromHtml: html)
        // Microdata only when there is no JSON-LD recipe, so no working site changes.
        guard let recipe = JsonLdRecipeParser.parse(blocks, sourceUrl: url, pageLanguage: JsonLdRecipeParser.pageLanguage(html: html))
                ?? MicrodataRecipeParser.parse(html: html, sourceUrl: url) else {
            return .error(.noRecipeFound)
        }
        return .success(recipe)
    }
}
