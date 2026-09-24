import XCTest
@testable import RecipeClipper

@MainActor
final class NavigationTests: XCTestCase {

    func testTheDeepLinkRoundTripsALinkWithItsOwnQueryAndFragment() throws {
        let shared = "https://example.com/recipe?id=3&utm_source=x+y#step-2"
        let deepLink = try XCTUnwrap(DeepLink.importUrl(for: shared))

        XCTAssertEqual(deepLink.scheme, "recipeclipper")
        XCTAssertEqual(deepLink.host, "import")
        XCTAssertEqual(DeepLink.sharedUrl(from: deepLink), shared)
    }

    func testForeignOrEmptyLinksAreIgnored() throws {
        XCTAssertNil(DeepLink.sharedUrl(from: try XCTUnwrap(URL(string: "https://example.com/import?url=x"))))
        XCTAssertNil(DeepLink.sharedUrl(from: try XCTUnwrap(URL(string: "recipeclipper://other?url=x"))))
        XCTAssertNil(DeepLink.sharedUrl(from: try XCTUnwrap(URL(string: "recipeclipper://import"))))
        XCTAssertNil(DeepLink.sharedUrl(from: try XCTUnwrap(URL(string: "recipeclipper://import?url="))))
    }

    func testEveryShareIsPushedEvenARepeatWhileRunning() throws {
        let router = Router()
        let link = try XCTUnwrap(DeepLink.importUrl(for: "https://example.com/soup"))

        router.handle(link)
        router.handle(link)

        XCTAssertEqual(router.path, [.importUrl("https://example.com/soup"), .importUrl("https://example.com/soup")])
    }

    func testAnUnrelatedUrlLeavesTheStackAlone() throws {
        let router = Router()
        router.push(.history)

        router.handle(try XCTUnwrap(URL(string: "recipeclipper://settings")))

        XCTAssertEqual(router.path, [.history])
    }

    /// Any app or page can open `recipeclipper://`, so only a web link is accepted, as Android
    /// only accepts `https?://\S+` from a share.
    func testOnlyAWebLinkIsAccepted() throws {
        for value in ["file:///etc/passwd", "javascript:alert(1)", "example.com/soup", "https://", "ftp://x.com/a"] {
            let link = try XCTUnwrap(DeepLink.importUrl(for: value))
            XCTAssertNil(DeepLink.sharedUrl(from: link), value)
        }
    }

    func testTheLinkIsFoundInsideSharedTextAndTheSchemeIsCaseInsensitive() throws {
        let link = try XCTUnwrap(DeepLink.importUrl(for: "  Try this: HTTPS://Example.com/soup?a=1&b=2 yum "))
        XCTAssertEqual(DeepLink.sharedUrl(from: link), "HTTPS://Example.com/soup?a=1&b=2")
    }

    func testAHandEncodedLinkWithAnEncodedAmpersandAndFragmentParses() throws {
        let link = try XCTUnwrap(URL(string: "recipeclipper://import?url=https%3A%2F%2Fa.com%2Fr%3Fx%3D1%26y%3D2%23f"))
        XCTAssertEqual(DeepLink.sharedUrl(from: link), "https://a.com/r?x=1&y=2#f")
    }

    func testTheSchemeAndHostOfTheDeepLinkAreCaseInsensitive() throws {
        let link = try XCTUnwrap(URL(string: "RecipeClipper://IMPORT?url=https%3A%2F%2Fa.com%2Fr"))
        XCTAssertEqual(DeepLink.sharedUrl(from: link), "https://a.com/r")
    }

    func testANonWebLinkLeavesTheStackAlone() throws {
        let router = Router()
        router.handle(try XCTUnwrap(DeepLink.importUrl(for: "file:///etc/passwd")))
        XCTAssertEqual(router.path, [])
    }

    /// A share while a recipe is showing pushes a new entry on top rather than replacing it.
    func testAShareWhileOnARecipePushesOnTop() throws {
        let router = Router()
        router.push(.recipe(id: 3))
        router.handle(try XCTUnwrap(DeepLink.importUrl(for: "https://example.com/soup")))
        XCTAssertEqual(router.path, [.recipe(id: 3), .importUrl("https://example.com/soup")])
    }
}
