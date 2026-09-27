import XCTest
@testable import RecipeClipper

/// Port of Android's SourceDomainTest.
final class SourceDomainTests: XCTestCase {

    private func of(_ url: String) -> String? { SourceDomain.of(url) }

    func testALeadingWwwIsDropped() {
        XCTAssertEqual("smittenkitchen.com", of("https://www.smittenkitchen.com/2024/01/some-recipe/"))
    }

    func testAHostWithoutWwwIsKeptAsItIs() {
        XCTAssertEqual("smittenkitchen.com", of("https://smittenkitchen.com/2024/01/some-recipe/"))
    }

    func testOtherSubdomainsAreKept() {
        XCTAssertEqual("cooking.nytimes.com", of("https://cooking.nytimes.com/recipes/1234"))
        XCTAssertEqual("m.allrecipes.com", of("https://m.allrecipes.com/recipe/1"))
        XCTAssertEqual("www2.example.com", of("https://www2.example.com/a"))
    }

    func testOnlyOneLeadingWwwIsDroppedAndNeverOneInTheMiddle() {
        XCTAssertEqual("www.example.com", of("https://www.www.example.com/"))
        XCTAssertEqual("shop.www.example.com", of("https://shop.www.example.com/"))
    }

    func testTheHostIsLowercased() {
        XCTAssertEqual("seriouseats.com", of("https://WWW.SeriousEats.com/Recipe"))
    }

    func testPathQueryAndFragmentAreDropped() {
        XCTAssertEqual("example.com", of("https://example.com/a/b?c=d#e"))
        XCTAssertEqual("example.com", of("https://example.com?c=d"))
        XCTAssertEqual("example.com", of("https://example.com#e"))
        XCTAssertEqual("example.com", of("https://example.com"))
    }

    func testPortAndUserInfoAreDropped() {
        XCTAssertEqual("example.com", of("https://example.com:8443/recipe"))
        XCTAssertEqual("example.com", of("https://user:pass@www.example.com:8443/recipe"))
    }

    func testATrailingRootDotIsDropped() {
        XCTAssertEqual("example.com", of("https://www.example.com./recipe"))
    }

    func testAnIpv6LiteralKeepsItsBracketsAndLosesItsPort() {
        XCTAssertEqual("[::1]", of("http://[::1]:8080/recipe"))
    }

    func testSurroundingWhitespaceIsIgnored() {
        XCTAssertEqual("example.com", of("  https://www.example.com/recipe \n"))
    }

    func testHttpLinksWorkToo() {
        XCTAssertEqual("example.com", of("http://www.example.com/recipe"))
    }

    func testSomethingThatIsNotALinkHasNoDomain() {
        XCTAssertNil(of(""))
        XCTAssertNil(of("smittenkitchen.com"))
        XCTAssertNil(of("not a link"))
        XCTAssertNil(of("://example.com"))
    }

    func testALinkWithNoHostHasNoDomain() {
        XCTAssertNil(of("https://"))
        XCTAssertNil(of("https:///recipe"))
        XCTAssertNil(of("https://www./recipe"))
    }
}
