import XCTest
@testable import RecipeClipper

/// Finding the saved recipe a plan names by its link (#367).
final class SavedLinkTests: XCTestCase {

    private func summary(_ id: Int64, _ url: String) -> RecipeSummary {
        RecipeSummary(id: id, title: "R\(id)", imageUrl: nil, totalTime: nil, lastViewedAt: id, isSaved: true, sourceUrl: url)
    }

    private lazy var library = [
        summary(1, "https://example.com/adobo"), summary(2, "manual:abc-123"), summary(3, "https://example.com/soup?id=7"),
    ]

    func testTheLinkAsWrittenMatches() {
        XCTAssertEqual(SavedLink.find("https://example.com/adobo", in: library)?.id, 1)
        XCTAssertEqual(SavedLink.find("  manual:abc-123 ", in: library)?.id, 2)
    }

    func testAnHttpLinkMatchesOnceCleanedOfTrackingAndTheScheme() {
        XCTAssertEqual(SavedLink.find("https://example.com/adobo?utm_source=ai#top", in: library)?.id, 1)
        XCTAssertEqual(SavedLink.find("http://Example.com/soup?id=7&utm_medium=x", in: library)?.id, 3)
    }

    func testAnythingElseMatchesNothing() {
        XCTAssertNil(SavedLink.find("https://example.com/other", in: library))
        XCTAssertNil(SavedLink.find("manual:other", in: library))
        XCTAssertNil(SavedLink.find("  ", in: library))
        XCTAssertNil(SavedLink.find("https://example.com/adobo", in: []))
    }
}
