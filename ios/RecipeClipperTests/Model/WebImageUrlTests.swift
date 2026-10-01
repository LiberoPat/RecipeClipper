import XCTest
@testable import RecipeClipper

/// A photo's address from a page is a web image or nothing (#235). Android's WebImageUrlTest,
/// case for case; the differential corpus's Img rows pin the two together.
final class WebImageUrlTests: XCTestCase {

    private func of(_ address: String?) -> String? { WebImageUrl.of(address) }

    func testAnHttpsImageIsKeptAsItIs() {
        let image = "https://img.example/a/cookies.jpg?w=1200&h=800#top"
        XCTAssertEqual(of(image), image)
        XCTAssertEqual(of("https://img.example:8443/a.jpg"), "https://img.example:8443/a.jpg")
        XCTAssertEqual(of("https://cdn.example?id=4"), "https://cdn.example?id=4")
    }

    func testHttpIsUpgradedToHttpsAsALinkIs() {
        XCTAssertEqual(of("http://img.example/a.jpg"), "https://img.example/a.jpg")
        XCTAssertEqual(of("HTTP://Img.Example/A.jpg"), "https://Img.Example/A.jpg")
        XCTAssertEqual(of("HTTPS://img.example/a.jpg"), "https://img.example/a.jpg")
    }

    func testSpacesAroundTheAddressAreDropped() {
        XCTAssertEqual(of("  https://img.example/a.jpg\n"), "https://img.example/a.jpg")
    }

    func testAnAddressOnTheDeviceIsNoImage() {
        XCTAssertNil(of("file:///data/data/com.example.recipeclipper/databases/recipe_clipper.db"))
        XCTAssertNil(of("content://media/external/images/media/12"))
        XCTAssertNil(of("android.resource://com.example.recipeclipper/drawable/x"))
        XCTAssertNil(of("/data/user/0/com.example.recipeclipper/files/photo.jpg"))
    }

    func testAnAddressThatIsntAWebLinkIsNoImage() {
        XCTAssertNil(of("data:image/gif;base64,R0lGODlhAQABAAAAACw="))
        XCTAssertNil(of("javascript:alert('https://img.example/a.jpg')"))
        XCTAssertNil(of("blob:https://img.example/1234"))
        XCTAssertNil(of("ftp://img.example/a.jpg"))
        XCTAssertNil(of("intent://img.example/#Intent;end"))
    }

    func testARelativeOrBlankAddressIsNoImage() {
        XCTAssertNil(of("/img/cookies.jpg"))
        XCTAssertNil(of("img/cookies.jpg"))
        XCTAssertNil(of("//cdn.example/cookies.jpg"))
        XCTAssertNil(of(""))
        XCTAssertNil(of("   "))
        XCTAssertNil(of(nil))
    }

    func testAWebAddressWithNoHostIsNoImage() {
        XCTAssertNil(of("https://"))
        XCTAssertNil(of("https:///img/a.jpg"))
        XCTAssertNil(of("http://?x=1"))
        XCTAssertNil(of("https://user@:443/a.jpg"))
        XCTAssertNil(of("https:/img.example/a.jpg"))
    }

    func testAUserNameBeforeTheHostIsNotTheHost() {
        XCTAssertEqual(of("https://user:pw@img.example/a.jpg"), "https://user:pw@img.example/a.jpg")
    }
}
