import XCTest
@testable import RecipeClipper

/// ImageLoader keeps a photo it has fetched once and serves it with the network gone, which is
/// what makes a previously seen recipe's photo show offline (Coil does this on Android).
final class ImageLoaderTests: XCTestCase {
    private let url = URL(string: "https://img.example/soup.jpg")!

    override func tearDown() {
        ImageStubURLProtocol.reset()
    }

    private func loader() -> ImageLoader {
        // Memory only: a disk cache would outlive the test.
        ImageLoader(cache: URLCache(memoryCapacity: 4 * 1024 * 1024, diskCapacity: 0),
                    protocolClasses: [ImageStubURLProtocol.self])
    }

    func testASecondLoadIsServedFromTheCacheWithTheNetworkOff() async throws {
        let loader = loader()
        let first = try await loader.data(for: url)

        ImageStubURLProtocol.online = false
        let second = try await loader.data(for: url)

        XCTAssertEqual(first, ImageStubURLProtocol.body)
        XCTAssertEqual(second, ImageStubURLProtocol.body)
        XCTAssertEqual(ImageStubURLProtocol.requests, 1, "the second load never reached the network")
    }

    func testAPhotoMarkedNoStoreIsStillKeptForOffline() async throws {
        // Left to URLSession, a no-store response would never be cached, and so be missing
        // offline. The loader stores what it fetched itself.
        ImageStubURLProtocol.headers = ["Cache-Control": "no-store"]
        let loader = loader()
        _ = try await loader.data(for: url)

        ImageStubURLProtocol.online = false
        let second = try await loader.data(for: url)

        XCTAssertEqual(second, ImageStubURLProtocol.body)
        XCTAssertEqual(ImageStubURLProtocol.requests, 1)
    }

    func testAFailedResponseIsNotCached() async throws {
        ImageStubURLProtocol.status = 404
        let loader = loader()
        do {
            _ = try await loader.data(for: url)
            XCTFail("a 404 should throw")
        } catch {}

        ImageStubURLProtocol.status = 200
        let data = try await loader.data(for: url)

        XCTAssertEqual(data, ImageStubURLProtocol.body)
        XCTAssertEqual(ImageStubURLProtocol.requests, 2, "the 404 wasn't served back from the cache")
    }
}

/// Serves a fixed body (or fails as offline), counting requests.
final class ImageStubURLProtocol: URLProtocol {
    static let body = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) // the loader only moves bytes
    static var online = true
    static var status = 200
    static var headers: [String: String] = [:]
    static var requests = 0

    static func reset() {
        online = true
        status = 200
        headers = [:]
        requests = 0
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests += 1
        guard Self.online else {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: Self.status, httpVersion: "HTTP/1.1",
                                       headerFields: Self.headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
