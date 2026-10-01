import XCTest
@testable import RecipeClipper

/// The clip view hears its page's main frame only (#235): an ad's or another site's iframe can't
/// post a selection, a tag or a photo into the clip. Android's ClipBridgeTest, the listener's half
/// (the token is Android's fallback for an old WebView).
final class ClipPageEventTests: XCTestCase {

    private let selection = #"{"type":"selection","text":"2 cups flour"}"#

    func testTheMainFramesMessagesAreRead() {
        XCTAssertEqual(ClipPageEvent.accept(selection, isMainFrame: true), .selection("2 cups flour"))
        XCTAssertEqual(ClipPageEvent.accept(#"{"type":"tag","field":"STEPS"}"#, isMainFrame: true), .tagTapped(.steps))
        XCTAssertEqual(ClipPageEvent.accept(#"{"type":"noImage"}"#, isMainFrame: true), .noImage)
    }

    func testAMessageFromAnyOtherFrameIsDropped() {
        XCTAssertNil(ClipPageEvent.accept(selection, isMainFrame: false))
        XCTAssertNil(ClipPageEvent.accept(#"{"type":"image","src":"https://ads.example/a.jpg"}"#, isMainFrame: false))
        XCTAssertNil(ClipPageEvent.accept(#"{"type":"tag","field":"NAME"}"#, isMainFrame: false))
        XCTAssertNil(ClipPageEvent.accept(#"{"type":"noImage"}"#, isMainFrame: false))
    }

    func testAMessageThatIsntOneOfThePagesIsDropped() {
        XCTAssertNil(ClipPageEvent.accept(42, isMainFrame: true))
        XCTAssertNil(ClipPageEvent.accept("not json", isMainFrame: true))
        XCTAssertNil(ClipPageEvent.accept(#"{"type":"other"}"#, isMainFrame: true))
        XCTAssertNil(ClipPageEvent.accept(#"{"type":"tag","field":"OVEN"}"#, isMainFrame: true))
    }

    func testATappedImagesAddressGoesToTheViewModelAsItIsToBeJudgedThere() {
        XCTAssertEqual(
            ClipPageEvent.accept(#"{"type":"image","src":"file:///data/x.db"}"#, isMainFrame: true),
            .imageTapped("file:///data/x.db")
        )
        XCTAssertEqual(ClipPageEvent.accept(#"{"type":"image"}"#, isMainFrame: true), .imageTapped(""))
    }
}
