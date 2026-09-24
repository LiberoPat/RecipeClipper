import XCTest
@testable import RecipeClipper

/// Port of Android's UrlCleanerTest.
final class UrlCleanerTests: XCTestCase {

    private let plain = "https://www.thekitchn.com/filipino-chicken-adobo-recipe-23652486"

    private func clean(_ url: String) -> String { UrlCleaner.clean(url) }

    func testALinkWithNothingToRemoveIsUnchanged() {
        XCTAssertEqual(plain, clean(plain))
    }

    func testUtmTagsAreRemoved() {
        XCTAssertEqual(plain, clean("\(plain)?utm_source=newsletter"))
        XCTAssertEqual(plain, clean("\(plain)?utm_source=ig&utm_medium=social&utm_campaign=spring"))
    }

    func testDifferentTrackingTagsOnTheSamePageCleanToTheSameLink() {
        let a = clean("\(plain)?utm_source=newsletter")
        let b = clean("\(plain)?utm_source=instagram&fbclid=abc123")
        XCTAssertEqual(a, b)
        XCTAssertEqual(plain, a)
    }

    func testWellKnownClickIdsAreRemoved() {
        XCTAssertEqual(plain, clean("\(plain)?fbclid=1"))
        XCTAssertEqual(plain, clean("\(plain)?gclid=1&msclkid=2&igshid=3"))
        XCTAssertEqual(plain, clean("\(plain)?mc_cid=1&mc_eid=2"))
    }

    func testParametersThatMayChooseTheRecipeAreKeptInTheirOriginalOrder() {
        XCTAssertEqual("https://x.com/r?id=5&slug=adobo", clean("https://x.com/r?id=5&utm_source=a&slug=adobo"))
        XCTAssertEqual("https://x.com/r?z=1&a=2", clean("https://x.com/r?z=1&a=2"))
    }

    func testOnlyTheTrackingParametersAreDroppedFromAMixedQuery() {
        XCTAssertEqual("https://x.com/r?id=5", clean("https://x.com/r?fbclid=z&id=5"))
    }

    func testTheFragmentIsRemoved() {
        XCTAssertEqual(plain, clean("\(plain)#comments"))
        XCTAssertEqual(plain, clean("\(plain)?utm_source=a#recipe"))
    }

    func testSchemeAndHostAreLowercasedButThePathIsNot() {
        XCTAssertEqual("https://example.com/Some/Path", clean("HTTPS://Example.COM/Some/Path"))
    }

    func testParameterNamesAreMatchedCaseInsensitively() {
        XCTAssertEqual(plain, clean("\(plain)?UTM_Source=a&FBCLID=b"))
    }

    func testAnEmptyQueryDoesNotLeaveADanglingQuestionMark() {
        XCTAssertEqual(plain, clean("\(plain)?"))
        XCTAssertEqual(plain, clean("\(plain)?&&"))
    }

    func testALookalikeParameterNameIsNotTreatedAsTracking() {
        XCTAssertEqual("https://x.com/r?utmost=1&fbclidx=2", clean("https://x.com/r?utmost=1&fbclidx=2"))
    }

    func testAValueContainingAnEqualsSignOrAPercentEscapeIsKeptWhole() {
        XCTAssertEqual("https://x.com/r?q=a%20b=c", clean("https://x.com/r?utm_source=z&q=a%20b=c"))
    }

    func testALinkWithAHostAndNoPathWorks() {
        XCTAssertEqual("https://example.com", clean("https://EXAMPLE.com?utm_source=a"))
    }

    func testSurroundingWhitespaceIsTrimmedAndNonLinksPassThrough() {
        XCTAssertEqual(plain, clean("  \(plain)  "))
        XCTAssertEqual("not a link", clean("not a link"))
    }

    func testAnHttpLinkIsUpgradedToHttps() {
        XCTAssertEqual(plain, clean("http://www.thekitchn.com/filipino-chicken-adobo-recipe-23652486"))
    }

    func testAnHttpsLinkIsLeftAsHttps() {
        XCTAssertEqual(plain, clean(plain))
    }

    func testAnUppercaseHTTPSchemeIsUpgradedAndLowercased() {
        XCTAssertEqual(plain, clean("HTTP://www.thekitchn.com/filipino-chicken-adobo-recipe-23652486"))
    }

    func testAStringWithNoSchemeIsLeftUntouched() {
        XCTAssertEqual("not a link", clean("not a link"))
    }
}
