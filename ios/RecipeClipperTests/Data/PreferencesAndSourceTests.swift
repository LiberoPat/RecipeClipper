import Combine
import XCTest
@testable import RecipeClipper

final class UserDefaultsAppPreferencesTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        suiteName = "rc-prefs-test-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
    }

    func testDefaultsWhenNothingIsStored() {
        let prefs = UserDefaultsAppPreferences(defaults: defaults)
        XCTAssertEqual(prefs.unitSystem, .asWritten)
        XCTAssertFalse(prefs.convertLiquids)
        XCTAssertEqual(prefs.temperatureUnit, .asWritten)
        XCTAssertFalse(prefs.darkWhileCooking)
    }

    func testValuesRoundTripAcrossInstances() {
        let prefs = UserDefaultsAppPreferences(defaults: defaults)
        prefs.unitSystem = .metric
        prefs.convertLiquids = true
        prefs.temperatureUnit = .celsius
        prefs.darkWhileCooking = true

        let reread = UserDefaultsAppPreferences(defaults: defaults)
        XCTAssertEqual(reread.unitSystem, .metric)
        XCTAssertTrue(reread.convertLiquids)
        XCTAssertEqual(reread.temperatureUnit, .celsius)
        XCTAssertTrue(reread.darkWhileCooking)
    }

    func testEnumsAreStoredByTheirAndroidNamesUnderTheAndroidKeys() {
        let prefs = UserDefaultsAppPreferences(defaults: defaults)
        prefs.unitSystem = .ounces
        prefs.temperatureUnit = .fahrenheit
        XCTAssertEqual(defaults.string(forKey: "unit_system"), "OUNCES")
        XCTAssertEqual(defaults.string(forKey: "temperature_unit"), "FAHRENHEIT")
        prefs.convertLiquids = true
        XCTAssertEqual(defaults.object(forKey: "convert_liquids") as? Bool, true)
    }

    func testAnUnknownStoredValueFallsBackToTheDefault() {
        defaults.set("KELVIN", forKey: "temperature_unit")
        defaults.set("CUBITS", forKey: "unit_system")
        let prefs = UserDefaultsAppPreferences(defaults: defaults)
        XCTAssertEqual(prefs.temperatureUnit, .asWritten)
        XCTAssertEqual(prefs.unitSystem, .asWritten)
    }

    func testAStoredGramsReadsAsMetric() {
        // GRAMS was a fourth option until #17. Its users wanted weights, not As written.
        defaults.set("GRAMS", forKey: "unit_system")
        let prefs = UserDefaultsAppPreferences(defaults: defaults)
        XCTAssertEqual(prefs.unitSystem, .metric)
        XCTAssertEqual(prefs.current.unitSystem, .metric)
    }

    func testSettingsPublishesTheCurrentValuesThenEachChangeWithoutRepeats() {
        let prefs = UserDefaultsAppPreferences(defaults: defaults)
        prefs.unitSystem = .ounces
        var received: [AppSettings] = []
        let subscription = prefs.settings.sink { received.append($0) }
        defer { subscription.cancel() }

        prefs.darkWhileCooking = true
        prefs.darkWhileCooking = true // unchanged: no emission
        // Written through another instance on the same suite, as a second screen might.
        UserDefaultsAppPreferences(defaults: defaults).temperatureUnit = .celsius

        XCTAssertEqual(received, [
            AppSettings(unitSystem: .ounces),
            AppSettings(unitSystem: .ounces, darkWhileCooking: true),
            AppSettings(unitSystem: .ounces, temperatureUnit: .celsius, darkWhileCooking: true)
        ])
    }
}

/// Serves canned responses to a URLSession, so BlogRecipeSource runs without a network.
final class DataStubURLProtocol: URLProtocol {
    static var handler: ((URLRequest) -> (Int, Data))?
    static var lastRequest: URLRequest?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lastRequest = request
        let (status, body) = Self.handler?(request) ?? (500, Data())
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class BlogRecipeSourceTests: XCTestCase {
    private var source: BlogRecipeSource!

    override func setUp() {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [DataStubURLProtocol.self]
        source = BlogRecipeSource(session: URLSession(configuration: config))
    }

    override func tearDown() {
        DataStubURLProtocol.handler = nil
        DataStubURLProtocol.lastRequest = nil
        DataFailingURLProtocol.code = .notConnectedToInternet
    }

    private func fetch(status: Int) async -> ParseResult {
        DataStubURLProtocol.handler = { _ in (status, Data("<html>Nope</html>".utf8)) }
        return await source.fetch(url: "https://blocked.example/recipe")
    }

    func test403IsBlocked() async {
        let result = await fetch(status: 403)
        XCTAssertEqual(result, .error(.blocked(httpStatus: 403)))
    }

    func test404IsBlockedSinceSitesDisguiseBlocksAsNotFound() async {
        let result = await fetch(status: 404)
        XCTAssertEqual(result, .error(.blocked(httpStatus: 404)))
    }

    func test429And5xxAreBlocked() async {
        let tooMany = await fetch(status: 429)
        XCTAssertEqual(tooMany, .error(.blocked(httpStatus: 429)))
        let serverError = await fetch(status: 500)
        XCTAssertEqual(serverError, .error(.blocked(httpStatus: 500)))
        let unavailable = await fetch(status: 503)
        XCTAssertEqual(unavailable, .error(.blocked(httpStatus: 503)))
    }

    func testAnyOtherNon2xxStatusIsAFetchFailureNamingTheStatus() async {
        let result = await fetch(status: 400)
        XCTAssertEqual(result, .error(.fetchFailed("HTTP 400")))
    }

    func testWhichStatusesCountAsABlock() {
        for status in [403, 404, 429, 500, 502, 503, 599] { XCTAssertTrue(ParseError.isBlockStatus(status), "\(status)") }
        for status in [400, 401, 410, 418, 499, 600] { XCTAssertFalse(ParseError.isBlockStatus(status), "\(status)") }
    }

    func testTheRequestCarriesTheUserAgentAndTimeout() async {
        DataStubURLProtocol.handler = { _ in (200, Data("<html></html>".utf8)) }
        _ = await source.fetch(url: "https://a.example/recipe")
        let request = DataStubURLProtocol.lastRequest
        XCTAssertEqual(request?.value(forHTTPHeaderField: "User-Agent"), BlogRecipeSource.userAgent)
        XCTAssertEqual(request?.httpMethod, "GET")
        XCTAssertEqual(request?.timeoutInterval, 15)
    }

    func testAPageWithNoRecipeIsNoRecipeFound() async {
        DataStubURLProtocol.handler = { _ in (200, Data("<html><body>Just a story.</body></html>".utf8)) }
        let result = await source.fetch(url: "https://a.example/story")
        XCTAssertEqual(result, .error(.noRecipeFound))
    }

    func testALatin1BodyStillDecodes() async {
        // 0xE9 alone is invalid UTF-8; the Latin-1 fallback must still hand over a page.
        DataStubURLProtocol.handler = { _ in (200, Data([0x3C, 0x70, 0x3E, 0xE9, 0x3C, 0x2F, 0x70, 0x3E])) }
        let result = await source.fetch(url: "https://a.example/latin1")
        XCTAssertEqual(result, .error(.noRecipeFound))
    }

    func testAUtf8PageWithOneStrayByteKeepsItsAccents() {
        // Strict UTF-8 fails on the lone 0xFF; the old Latin-1 fallback then turned every
        // "è" on the page into "Ã¨". Lenient UTF-8 costs one U+FFFD instead.
        var data = Data("<h1>Crème brûlée</h1>".utf8)
        data.append(0xFF)
        let text = BlogRecipeSource.decode(data, textEncodingName: nil)
        XCTAssertTrue(text.hasPrefix("<h1>Crème brûlée</h1>"), text)
    }

    func testTheDeclaredCharsetIsHonoured() {
        // windows-1252: 0x93/0x94 are curly quotes, 0xE9 is é.
        let data = Data([0x93, 0x43, 0x61, 0x66, 0xE9, 0x94])
        XCTAssertEqual(BlogRecipeSource.decode(data, textEncodingName: "windows-1252"), "\u{201C}Café\u{201D}")
        // A page labelled ISO-8859-1 is treated as windows-1252, as browsers do.
        XCTAssertEqual(BlogRecipeSource.decode(data, textEncodingName: "ISO-8859-1"), "\u{201C}Café\u{201D}")
    }

    func testAMetaCharsetIsUsedWhenTheServerDeclaresNone() {
        var data = Data(#"<html><head><meta http-equiv="Content-Type" content="text/html; charset=windows-1252"></head><p>"#.utf8)
        data.append(contentsOf: [0x43, 0x61, 0x66, 0xE9])
        XCTAssertTrue(BlogRecipeSource.decode(data, textEncodingName: nil).hasSuffix("<p>Café"))
    }

    func testTheResponseCharsetReachesTheParser() async {
        DataCharsetURLProtocol.body = Data([0x43, 0x61, 0x66, 0xE9])
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [DataCharsetURLProtocol.self]
        let session = URLSession(configuration: config)
        let (data, response) = try! await session.data(from: URL(string: "https://a.example/cp1252")!)
        XCTAssertEqual(BlogRecipeSource.decode(data, textEncodingName: response.textEncodingName), "Café")
    }

    func testTheAppSessionCapsTheWholeRequestNotJustIdleTime() {
        let config = BlogRecipeSource.defaultSession.configuration
        XCTAssertEqual(config.timeoutIntervalForResource, BlogRecipeSource.timeout)
    }

    func testANonHttpLinkIsRefusedWithoutReadingIt() async {
        DataStubURLProtocol.handler = { _ in (200, Data("<html></html>".utf8)) }
        let result = await source.fetch(url: "file:///etc/hosts")
        guard case .error(.fetchFailed) = result else { return XCTFail("\(result)") }
        XCTAssertNil(DataStubURLProtocol.lastRequest)
    }

    private func failingFetch(_ code: URLError.Code) async -> ParseResult {
        DataFailingURLProtocol.code = code
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [DataFailingURLProtocol.self]
        let failing = BlogRecipeSource(session: URLSession(configuration: config))
        return await failing.fetch(url: "https://failing.example/recipe")
    }

    func testNoConnectionIsOffline() async {
        let result = await failingFetch(.notConnectedToInternet)
        XCTAssertEqual(result, .error(.offline))
    }

    func testEveryNoConnectionCodeIsOffline() {
        for code in [URLError.Code.notConnectedToInternet, .networkConnectionLost, .dataNotAllowed,
                     .internationalRoamingOff] {
            XCTAssertEqual(BlogRecipeSource.cause(of: URLError(code)), .offline, "\(code)")
        }
    }

    func testATimeoutIsAFetchFailureMarkedTimedOut() async {
        let result = await failingFetch(.timedOut)
        guard case .error(.fetchFailed(let detail, let timedOut)) = result else { return XCTFail("\(result)") }
        XCTAssertTrue(timedOut)
        XCTAssertNotEqual(detail ?? "", "")
    }

    func testAnyOtherNetworkErrorIsAFetchFailureWithItsMessage() async {
        let result = await failingFetch(.cannotFindHost)
        guard case .error(.fetchFailed(let detail, let timedOut)) = result else { return XCTFail("\(result)") }
        XCTAssertFalse(timedOut)
        XCTAssertNotEqual(detail ?? "", "")
    }

    func testParseHtmlWithJsonLdFindsTheRecipeWhenTheParserIsPresent() throws {
        let html = """
            <html><head><script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Recipe","name":"Toast",
             "recipeIngredient":["1 slice bread"],"recipeInstructions":["Toast it."]}
            </script></head><body></body></html>
            """
        if JsonLdRecipeParser.extractJsonLdBlocks(fromHtml: html).isEmpty {
            throw XCTSkip("JsonLdRecipeParser is still a stub")
        }
        let result = BlogRecipeSource.parse(html: html, url: "https://a.example/toast")
        guard case .success(let recipe) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(recipe.name, "Toast")
        XCTAssertEqual(recipe.sourceUrl, "https://a.example/toast")
    }
}

/// Fails every request with `code`, as the network layer would.
final class DataFailingURLProtocol: URLProtocol {
    static var code: URLError.Code = .notConnectedToInternet
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        client?.urlProtocol(self, didFailWithError: URLError(Self.code))
    }
    override func stopLoading() {}
}

/// Answers with a windows-1252 body and says so in Content-Type.
final class DataCharsetURLProtocol: URLProtocol {
    static var body = Data()
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let response = HTTPURLResponse(
            url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/html; charset=windows-1252"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
