import XCTest
@testable import RecipeClipper

/// Tasty Recipes' and Mediavine Create's ingredient headings (#119), on the real pages Android's
/// `CardHeadingsTest` reads. Edge cases are pinned to the Kotlin by the differential corpus's
/// `Headings` rows.
final class CardHeadingsTests: XCTestCase {

    private func ingredients(_ page: String) throws -> [String] {
        let html = try pageFixture(page)
        guard case .success(let recipe) = BlogRecipeSource.parse(html: html, url: "https://example.com/r") else {
            XCTFail("no recipe in \(page)"); return []
        }
        return recipe.ingredients
    }

    func testTastyRecipesBoldParagraphsBecomeHeadingsAndJsonLdLinesStay() throws {
        let lines = try ingredients("tasty-pinchofyum-blackout-chocolate-cake")
        XCTAssertEqual(Array(lines.prefix(2)), ["For the chocolate cake:", "3 cups flour"])
        XCTAssertEqual(Array(lines[12..<14]), ["1 tablespoon vanilla extract", "For the frosting:"])
        // The card curls this dash ("3–4 cups"); JSON-LD's line is kept.
        XCTAssertEqual(lines.last, "3-4 cups of chocolate chips")
        XCTAssertEqual(lines.count, 22)
    }

    func testAnUnnamedFirstGroupHasNoHeadingAndABoldNameGetsAColon() throws {
        let lines = try ingredients("tasty-pinchofyum-peanut-butter-pie")
        XCTAssertEqual(lines[0], "1 cup peanut butter")
        XCTAssertEqual(Array(lines[4..<6]), ["Oreo Crust:", "1 14-ounce package Oreos"])
        XCTAssertEqual(lines.count, 7)
    }

    func testAPlainParagraphEndingInAColonIsAHeading() throws {
        let lines = try ingredients("tasty-joythebaker-lemon-bars")
        XCTAssertEqual(lines[0], "For the Crust:")
        XCTAssertEqual(lines[7], "For the Topping:")
        XCTAssertEqual(lines.count, 16)
    }

    func testMediavineCreateGroupNamesBecomeHeadings() throws {
        let pie = try ingredients("mv-create-tidymom-apple-pie-bars")
        XCTAssertEqual(pie[0], "FOR APPLE FILLING:")
        XCTAssertTrue(pie.contains("CINNAMON & SUGAR TOPPING:"))
        let brownies = try ingredients("mv-create-tidymom-brownie-cookie-sandwiches")
        XCTAssertEqual(brownies[0], "Brownie Cookies:")
        XCTAssertEqual(brownies[2], "Chocolate Chip Cookie Dough Frosting:")
    }

    func testMediavineCreateOlderMarkupReadsTheSame() throws {
        let lines = try ingredients("mv-create-keytomylime-chicken-kabobs")
        XCTAssertEqual(Array(lines.prefix(2)), ["Chicken Marinade:", "1/3 cup olive oil"])
        XCTAssertEqual(lines[7], "Chicken and Vegetables:")
        XCTAssertEqual(lines.count, 12)
    }
}
