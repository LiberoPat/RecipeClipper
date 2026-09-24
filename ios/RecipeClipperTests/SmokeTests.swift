import XCTest
@testable import RecipeClipper
final class SmokeTests: XCTestCase { func testBuilds() { XCTAssertEqual(historyLimit, 50) } }
