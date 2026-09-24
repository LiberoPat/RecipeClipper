import XCTest
@testable import RecipeClipper

/// Port of Android's TimeAgoAndUrlInputTest, plus `extractSharedUrl` (new on iOS).
final class TimeAgoAndUrlInputTests: XCTestCase {

    private let now: Int64 = 1_000_000_000_000
    private let minute: Int64 = 60_000
    private var hour: Int64 { 60 * minute }
    private var day: Int64 { 24 * hour }

    // The boundaries are what matter: the wording lives in the UI and isn't tested here.
    func testRecentTimesMapToTheRightBucket() {
        XCTAssertEqual(.justNow, TimeAgo.since(now - 20_000, now: now))
        XCTAssertEqual(.minutes(5), TimeAgo.since(now - 5 * minute, now: now))
        XCTAssertEqual(.minutes(59), TimeAgo.since(now - 59 * minute - 30_000, now: now))
        XCTAssertEqual(.hours(3), TimeAgo.since(now - 3 * hour, now: now))
        XCTAssertEqual(.yesterday, TimeAgo.since(now - 30 * hour, now: now))
        XCTAssertEqual(.days(4), TimeAgo.since(now - 4 * day, now: now))
    }

    func testEachBoundaryFallsOnTheLaterBucket() {
        XCTAssertEqual(.minutes(1), TimeAgo.since(now - minute, now: now))
        XCTAssertEqual(.hours(1), TimeAgo.since(now - hour, now: now))
        XCTAssertEqual(.yesterday, TimeAgo.since(now - day, now: now))
        XCTAssertEqual(.days(2), TimeAgo.since(now - 2 * day, now: now))
    }

    func testATimeInTheFutureIsJustNow() {
        XCTAssertEqual(.justNow, TimeAgo.since(now + hour, now: now))
    }

    func testAWeekOrMoreIsADateCarryingTheOriginalInstant() {
        let then = now - 20 * day
        XCTAssertEqual(.onDate(then), TimeAgo.since(then, now: now))
        // Exactly seven days is already a date, not a day count.
        XCTAssertEqual(.onDate(now - 7 * day), TimeAgo.since(now - 7 * day, now: now))
    }

    func testFullLinksPassThrough() {
        XCTAssertEqual("https://example.com/r", UrlInput.normalize("https://example.com/r"))
        XCTAssertEqual("http://example.com/r", UrlInput.normalize("  http://example.com/r  "))
    }

    func testOnlyTheFirstWordOfPastedTextIsUsed() {
        XCTAssertEqual("https://example.com/r", UrlInput.normalize("https://example.com/r check this out"))
    }

    func testAMissingSchemeIsAdded() {
        XCTAssertEqual("https://seriouseats.com/recipe", UrlInput.normalize("seriouseats.com/recipe"))
    }

    func testThingsThatAreNotLinksAreRejected() {
        XCTAssertNil(UrlInput.normalize(""))
        XCTAssertNil(UrlInput.normalize("   "))
        XCTAssertNil(UrlInput.normalize("chicken adobo"))
        XCTAssertNil(UrlInput.normalize("https://"))
        XCTAssertNil(UrlInput.normalize("."))
    }

    // --- extractSharedUrl (Android's MainActivity.extractUrl) ---

    func testASharedLinkIsFoundInsideSurroundingText() {
        XCTAssertEqual(
            "https://example.com/adobo?x=1",
            UrlInput.extractSharedUrl("Look at this recipe! https://example.com/adobo?x=1 so good")
        )
        XCTAssertEqual("http://example.com/r", UrlInput.extractSharedUrl("http://example.com/r"))
    }

    func testTheFirstOfSeveralSharedLinksWins() {
        XCTAssertEqual("https://a.com/1", UrlInput.extractSharedUrl("https://a.com/1\nhttps://b.com/2"))
    }

    func testSharedTextWithoutALinkGivesNil() {
        XCTAssertNil(UrlInput.extractSharedUrl("chicken adobo"))
        XCTAssertNil(UrlInput.extractSharedUrl(""))
        XCTAssertNil(UrlInput.extractSharedUrl("ftp://example.com/file"))
        XCTAssertNil(UrlInput.extractSharedUrl("https://"))
    }

    func testTheSchemeMatchIsCaseSensitiveLikeAndroids() {
        XCTAssertNil(UrlInput.extractSharedUrl("HTTPS://example.com"))
    }
}
