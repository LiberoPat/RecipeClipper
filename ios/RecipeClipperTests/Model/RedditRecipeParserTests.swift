import XCTest
@testable import RecipeClipper

/// The same cases and expectations as Android's `RedditRecipeParserTest`.
final class RedditRecipeParserTests: XCTestCase {

    private let url = "https://www.reddit.com/r/recipes/comments/1abc01/weeknight_lemon_chicken_orzo/"

    private func recipe(_ json: String, file: StaticString = #filePath, line: UInt = #line) -> Recipe? {
        guard case .success(let recipe) = RedditRecipeParser.parse(json, sourceUrl: url) else {
            XCTFail("Expected a recipe", file: file, line: line)
            return nil
        }
        return recipe
    }

    func testASelfPostsBodyIsTheRecipe() {
        XCTAssertEqual(recipe(RedditFixtures.selfPost), Recipe(
            name: "Weeknight Lemon Chicken Orzo",
            image: "https://preview.redd.it/orzo.jpeg?width=1080&format=pjpg&s=abc",
            ingredients: ["1 lb chicken thighs", "1 cup orzo", "2 1/2 cups chicken broth", "1 lemon, zested and juiced"],
            instructions: [
                "Brown the chicken in a deep pan, 6 minutes a side.",
                "Add the orzo and broth and simmer for 12 minutes.",
                "Stir in the lemon and serve.",
            ],
            prepTime: "10m",
            cookTime: "25m",
            totalTime: nil,
            yield: "Serves 4",
            sourceUrl: url,
            sourceType: .reddit
        ))
    }

    func testAPhotoPostTakesItsRecipeFromTheTranscriptionInTheComments() throws {
        let r = try XCTUnwrap(recipe(RedditFixtures.cardWithTranscription))
        XCTAssertEqual(r.name, "Grandma's date nut bread, found in her tin")
        XCTAssertEqual(r.image, "https://preview.redd.it/card01.jpeg?auto=webp&s=def")
        XCTAssertEqual(r.ingredients, [
            "1 cup chopped dates", "1 tsp baking soda", "1 cup boiling water", "1 3/4 cups flour", "1/2 cup chopped walnuts",
        ])
        XCTAssertEqual(r.instructions, [
            "Pour the boiling water over the dates and soda and let cool.",
            "Stir in the flour and nuts.",
            "Bake in a greased loaf pan at 350°F for 1 hour.",
        ])
        XCTAssertEqual(r.sourceType, .reddit)
        XCTAssertEqual(r.sourceUrl, url)
    }

    func testNoTranscriptionAnywhereIsAnHonestOutcomeWithThePostsPhoto() {
        XCTAssertEqual(
            RedditRecipeParser.parse(RedditFixtures.photoOnly, sourceUrl: url),
            .error(.noTranscription(title: "[Homemade] Sunday lasagna", imageUrl: "https://preview.redd.it/m1.jpg?width=800&s=1"))
        )
    }

    func testACrosspostBorrowsTheOriginalsBodyAndPhoto() throws {
        let r = try XCTUnwrap(recipe(RedditFixtures.crosspost))
        XCTAssertEqual(r.name, "Crossposting this great soup")
        XCTAssertEqual(r.image, "https://preview.redd.it/soup.jpeg?a=1&s=x")
        XCTAssertEqual(r.ingredients, ["2 lb tomatoes", "1 onion"])
        XCTAssertEqual(r.instructions, ["Roast everything.", "Blend."])
    }

    func testThePostBodyWinsOverAComment() {
        let json = RedditFixtures.cardWithTranscription.replacingOccurrences(
            of: #""selftext":"""#, with: #""selftext":"Ingredients\n1 cup figs\nMethod\nEat.""#
        )
        XCTAssertEqual(recipe(json)?.ingredients, ["1 cup figs"])
    }

    func testARemovedBodyFallsThroughToTheComments() {
        let json = RedditFixtures.cardWithTranscription.replacingOccurrences(
            of: #""selftext":"""#, with: #""selftext":"[removed]""#
        )
        XCTAssertEqual(recipe(json)?.ingredients.first, "1 cup chopped dates")
    }

    func testALinkStraightToAnImageIsThePhotoWhenThereIsNoPreview() {
        let json = #"""
            [{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"title":"Pie",
            "selftext":"","url":"https://i.imgur.com/pie.png"}}]}},{"kind":"Listing","data":{"children":[]}}]
            """#
        XCTAssertEqual(RedditRecipeParser.parse(json, sourceUrl: url),
                       .error(.noTranscription(title: "Pie", imageUrl: "https://i.imgur.com/pie.png")))
        let noImage = json.replacingOccurrences(of: "https://i.imgur.com/pie.png", with: "https://example.com/pie-recipe")
        XCTAssertEqual(RedditRecipeParser.parse(noImage, sourceUrl: url), .error(.noTranscription(title: "Pie", imageUrl: nil)))
    }

    func testAnythingThatIsntAPostListingHasNoRecipe() {
        let none = ParseResult.error(.noRecipeFound)
        XCTAssertEqual(RedditRecipeParser.parse("<html>whoa there, pardner</html>", sourceUrl: url), none)
        XCTAssertEqual(RedditRecipeParser.parse(#"{"kind":"Listing","data":{"children":[]}}"#, sourceUrl: url), none)
        XCTAssertEqual(RedditRecipeParser.parse("[]", sourceUrl: url), none)
        XCTAssertEqual(RedditRecipeParser.parse(
            #"[{"kind":"Listing","data":{"children":[{"kind":"t5","data":{"title":"x"}}]}}]"#, sourceUrl: url), none)
        XCTAssertEqual(RedditRecipeParser.parse(
            #"[{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"title":"  "}}]}}]"#, sourceUrl: url), none)
        XCTAssertEqual(RedditRecipeParser.parse(String(repeating: "[", count: 100_000), sourceUrl: url), none)
    }

    func testCommentsAreWalkedDepthFirstInRedditsOrderSkippingMoreStubs() throws {
        let listings = try XCTUnwrap(JsonLdRecipeParser.parseJson(RedditFixtures.cardWithTranscription) as? [Any])
        let bodies = RedditRecipeParser.comments(listings[1] as? [String: Any])
        XCTAssertEqual(bodies.count, 5)
        XCTAssertEqual(bodies[1], "Her handwriting is beautiful.")
        XCTAssertTrue(bodies[3].hasPrefix("Transcription:"))
        XCTAssertTrue(bodies[4].hasPrefix("Ingredients:"))
    }

    func testAnAbsurdlyDeepReplyChainStopsAtTheDepthGuard() throws {
        var node = #"{"kind":"Listing","data":{"children":[]}}"#
        for i in 0..<80 { // 5 JSON levels each: under JSONSerialization's 512
            node = #"{"kind":"Listing","data":{"children":[{"kind":"t1","data":{"body":"reply \#(i)","replies":\#(node)}}]}}"#
        }
        let parsed = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(node.utf8)) as? [String: Any])
        XCTAssertEqual(RedditRecipeParser.comments(parsed).count, 51)
    }

    func testNoTranscriptionIsAnOutcomeNeverRetriedNeverReloadedOnReconnect() {
        let error = ParseError.noTranscription(title: "Pie", imageUrl: nil)
        XCTAssertFalse(error.shouldAutoRetry)
        XCTAssertFalse(error.reloadsOnReconnect)
        XCTAssertEqual(Strings.message(for: error), Strings.errorNoTranscription)
    }
}
