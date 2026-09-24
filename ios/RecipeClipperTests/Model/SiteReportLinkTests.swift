import XCTest
@testable import RecipeClipper

/// Mirrors Android's SiteReportLinkTest: the same inputs give the same bytes.
final class SiteReportLinkTests: XCTestCase {

    private func url(_ link: String) -> String {
        SiteReportLink.issueUrl(link: link, platform: "Android 14 (API 34)", appVersion: "1.0 (1)")
    }

    /// The query parameters, decoded, the way GitHub reads them.
    private func params(_ url: String) -> [String: String] {
        let items = URLComponents(string: url)?.queryItems ?? []
        return Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
    }

    func testTheWholeLinkExactly() {
        XCTAssertEqual(
            url("https://example.com/recipe?id=1&x=2"),
            "https://github.com/LiberoPat/RecipeClipper/issues/new"
                + "?title=Site%20not%20supported%3A%20example.com"
                + "&body=Recipe%20Clipper%20found%20no%20recipe%20on%20this%20page.%0A%0A"
                + "Link%3A%20https%3A%2F%2Fexample.com%2Frecipe%3Fid%3D1%26x%3D2%0A"
                + "Platform%3A%20Android%2014%20%28API%2034%29%0A"
                + "App%20version%3A%201.0%20%281%29"
                + "&labels=site-report"
        )
    }

    func testTitleBodyAndLabelDecodeToWhatTheIssueShows() {
        let p = params(url("https://www.Smitten-Kitchen.com/2024/01/soup/?print=1"))
        XCTAssertEqual(p["title"], "Site not supported: smitten-kitchen.com")
        XCTAssertEqual(
            p["body"],
            "Recipe Clipper found no recipe on this page.\n\n"
                + "Link: https://www.smitten-kitchen.com/2024/01/soup/?print=1\n"
                + "Platform: Android 14 (API 34)\n"
                + "App version: 1.0 (1)"
        )
        XCTAssertEqual(p["labels"], "site-report")
        XCTAssertEqual(Set(p.keys), ["title", "body", "labels"])
    }

    func testAmpersandsEqualsSignsAndHashesInTheLinkStayInsideTheBody() {
        let link = "https://example.com/r?a=1&b=2&title=x"
        let raw = url(link)
        XCTAssertEqual(URLComponents(string: raw)?.queryItems?.count, 3)
        XCTAssertTrue(params(raw)["body"]?.contains("Link: \(link)\n") == true)
        XCTAssertFalse(raw.contains("#"))
    }

    func testTrackingTagsAndTheFragmentNeverReachAPublicIssue() {
        let body = params(url("https://example.com/r?utm_source=x&id=7&fbclid=abc#step-2"))["body"] ?? ""
        XCTAssertTrue(body.contains("Link: https://example.com/r?id=7\n"))
        XCTAssertFalse(body.contains("utm_"))
        XCTAssertFalse(body.contains("fbclid"))
        XCTAssertFalse(body.contains("step-2"))
    }

    func testSpacesArePercentEncodedNeverPlus() {
        let raw = url("https://example.com/r")
        XCTAssertFalse(raw.contains("+"))
        XCTAssertFalse(raw.contains(" "))
        XCTAssertNotNil(URL(string: raw))
    }

    func testNonAsciiIsEncodedAsUtf8Bytes() {
        XCTAssertEqual(SiteReportLink.percentEncode("café — 🍲"), "caf%C3%A9%20%E2%80%94%20%F0%9F%8D%B2")
        XCTAssertEqual(SiteReportLink.percentEncode("AZaz09-._~"), "AZaz09-._~")
        XCTAssertEqual(
            SiteReportLink.percentEncode("!*'();:@&=+$,/?#[]%"),
            "%21%2A%27%28%29%3B%3A%40%26%3D%2B%24%2C%2F%3F%23%5B%5D%25"
        )
    }

    func testTheTitleNamesTheSite() {
        XCTAssertEqual(SiteReportLink.domain(of: "https://cooking.nytimes.com/recipes/1"), "cooking.nytimes.com")
        XCTAssertEqual(SiteReportLink.domain(of: "https://user@www.example.com:8443/x"), "example.com")
        XCTAssertEqual(SiteReportLink.domain(of: "https://example.com"), "example.com")
        XCTAssertEqual(SiteReportLink.domain(of: "https://example.com?x=1"), "example.com")
        XCTAssertNil(SiteReportLink.domain(of: "not a link"))
        XCTAssertNil(SiteReportLink.domain(of: "https:///path"))
    }

    func testALinkWithNoHostIsNamedInFullRatherThanGuessed() {
        XCTAssertEqual(params(url("not a link"))["title"], "Site not supported: not a link")
    }
}
