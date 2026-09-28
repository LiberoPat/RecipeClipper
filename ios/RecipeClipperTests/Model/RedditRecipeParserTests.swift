import XCTest
@testable import RecipeClipper

/// The same cases and expectations as Android's `RedditRecipeParserTest`.
final class RedditRecipeParserTests: XCTestCase {

    private let url = "https://www.reddit.com/r/recipes/comments/1f4b2cd/weeknight_lemon_chicken_orzo/"

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
            image: "https://preview.redd.it/orzo7kq2.jpeg?width=1080&format=pjpg&auto=webp&s=1a2b3c",
            ingredients: ["1 lb chicken thighs", "1 cup orzo", "2 1/2 cups chicken broth", "1 lemon, zested and juiced"],
            instructions: [
                "Brown the chicken in a deep pan, 6 minutes a side.",
                "Add the orzo and broth and simmer for 12 minutes.",
                "Stir in the lemon & serve.",
            ],
            prepTime: "10m",
            cookTime: "25m",
            totalTime: nil,
            yield: nil,
            sourceUrl: url,
            sourceType: .reddit,
            language: "en"
        ))
    }

    func testAPhotoPostTakesTheRecipeFromThePostersOwnComment() throws {
        // AutoModerator's template splits too, and another reader's recipe comes first: the
        // poster's comment wins over both. The blank "&#x200B;" and the blog link are dropped.
        let r = try XCTUnwrap(recipe(RedditFixtures.imageWithOpRecipe))
        XCTAssertEqual(r.name, "Sticky Honey Garlic Chicken Thighs")
        XCTAssertEqual(r.image, "https://preview.redd.it/k2m8x7vq1abd1.jpeg?auto=webp&s=5c1e0f1a2b3c4d5e6f")
        XCTAssertEqual(r.ingredients, [
            "2 lb (900 g) boneless chicken thighs", "1 tsp salt", "1 tbsp oil",
            "For the sauce:", "1/3 cup honey", "1/4 cup soy sauce", "6 cloves garlic, minced",
        ])
        XCTAssertEqual(r.instructions, [
            "Pat the chicken dry and season with the salt.",
            "Sear in the oil over medium-high heat, 5 minutes a side.",
            "Whisk the sauce, pour it in and simmer until sticky, about 8 minutes.",
        ])
        XCTAssertEqual(r.yield, "Serves 4")
        XCTAssertEqual(r.sourceType, .reddit)
    }

    func testAGallerysRecipeCardTakesItsRecipeFromATranscriptionUnderAutoModerator() throws {
        let r = try XCTUnwrap(recipe(RedditFixtures.cardWithTranscription))
        XCTAssertEqual(r.name, "Grandma's date nut bread, found in her recipe tin")
        XCTAssertEqual(r.image, "https://preview.redd.it/a1card0front.jpg?width=3024&format=pjpg&auto=webp&s=1a")
        XCTAssertEqual(r.ingredients, [
            "1 cup chopped dates", "1 tsp baking soda", "1 cup boiling water", "1 3/4 cups flour", "1/2 cup chopped walnuts",
        ])
        XCTAssertEqual(r.instructions, [
            "Pour the boiling water over the dates and soda and let cool.",
            "Stir in the flour and nuts.",
            "Bake in a greased loaf pan at 350°F for 1 hour.",
        ])
    }

    func testAReplyWhoseIngredientsHaveNoHeaderIsStillARecipe() throws {
        let r = try XCTUnwrap(recipe(RedditFixtures.replyWithoutHeaders))
        XCTAssertEqual(r.name, "[Request] Spinach dip for two people")
        XCTAssertNil(r.image)
        XCTAssertEqual(r.ingredients, [
            "2.5 oz. frozen spinach, thawed and squeezed dry", "1/4 cup minced onion", "1/2 teaspoon minced garlic",
            "1/8 cup sour cream", "1/8 cup mayonnaise", "Salt – 1/8 tsp",
        ])
        XCTAssertEqual(r.instructions, ["Stir everything together in a small bowl and chill for an hour before serving."])
    }

    func testOnlyChatterIsAnHonestOutcomeWithThePostsPhoto() {
        XCTAssertEqual(
            RedditRecipeParser.parse(RedditFixtures.photoOnly, sourceUrl: url),
            .error(.noTranscription(
                title: "[Homemade] Sunday lasagna",
                imageUrl: "https://preview.redd.it/lasagna4x9.jpeg?auto=webp&s=4d5e6f"
            ))
        )
        XCTAssertNil(RedditRecipeParser.read(RedditFixtures.photoOnly, sourceUrl: url).crosspostOf)
    }

    func testACrosspostWithNoRecipeOfItsOwnNamesTheOriginalForTheSource() {
        let reading = RedditRecipeParser.read(RedditFixtures.crosspost, sourceUrl: url)
        XCTAssertEqual(reading.crosspostOf, "1f3k9xq")
        XCTAssertEqual(reading.result, .error(.noTranscription(
            title: "Saw this on r/recipes and had to share",
            imageUrl: "https://preview.redd.it/k2m8x7vq1abd1.jpeg?auto=webp&s=5c1e0f1a2b3c4d5e6f"
        )))
    }

    func testACrosspostBorrowsTheOriginalsBodyAndPhoto() throws {
        let json = #"""
            [{"kind":"Listing","data":{"children":[{"kind":"t3","data":{
            "subreddit":"Cooking","title":"Crossposting this great soup","selftext":"","crosspost_parent":"t3_1abc04",
            "crosspost_parent_list":[{"subreddit":"soup","title":"Tomato soup","is_self":true,
              "selftext":"## Ingredients\n\n* 2 lb tomatoes\n* 1 onion\n\n## Method\n\n* Roast everything.\n* Blend.",
              "preview":{"images":[{"source":{"url":"https://preview.redd.it/soup.jpeg?a=1&s=x"}}]}}]
            }}]}},{"kind":"Listing","data":{"children":[]}}]
            """#
        let r = try XCTUnwrap(recipe(json))
        XCTAssertEqual(r.name, "Crossposting this great soup")
        XCTAssertEqual(r.image, "https://preview.redd.it/soup.jpeg?a=1&s=x")
        XCTAssertEqual(r.ingredients, ["2 lb tomatoes", "1 onion"])
        XCTAssertEqual(r.instructions, ["Roast everything.", "Blend."])
    }

    func testThePostBodyWinsOverAComment() {
        let json = RedditFixtures.cardWithTranscription.replacingOccurrences(
            of: #""selftext": """#, with: #""selftext": "Ingredients\n1 cup figs\nMethod\nEat.""#
        )
        XCTAssertEqual(recipe(json)?.ingredients, ["1 cup figs"])
    }

    func testARemovedBodyFallsThroughToTheComments() {
        let json = RedditFixtures.cardWithTranscription.replacingOccurrences(
            of: #""selftext": """#, with: #""selftext": "[removed]""#
        )
        XCTAssertEqual(recipe(json)?.ingredients.first, "1 cup chopped dates")
    }

    func testALinkStraightToAnImageIsThePhotoWhenThereIsNoPreviewAndAmpIsUndone() {
        let json = #"""
            [{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"title":"Pie",
            "selftext":"","url":"https://i.imgur.com/pie.png"}}]}},{"kind":"Listing","data":{"children":[]}}]
            """#
        XCTAssertEqual(RedditRecipeParser.parse(json, sourceUrl: url),
                       .error(.noTranscription(title: "Pie", imageUrl: "https://i.imgur.com/pie.png")))
        let noImage = json.replacingOccurrences(of: "https://i.imgur.com/pie.png", with: "https://example.com/pie-recipe")
        XCTAssertEqual(RedditRecipeParser.parse(noImage, sourceUrl: url), .error(.noTranscription(title: "Pie", imageUrl: nil)))
        // Without raw_json Reddit escapes the preview's "&".
        let escaped = json.replacingOccurrences(
            of: #""selftext""#,
            with: #""preview":{"images":[{"source":{"url":"https://preview.redd.it/p.jpg?a=1&amp;s=2"}}]},"selftext""#
        )
        XCTAssertEqual(RedditRecipeParser.parse(escaped, sourceUrl: url),
                       .error(.noTranscription(title: "Pie", imageUrl: "https://preview.redd.it/p.jpg?a=1&s=2")))
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

    func testCommentsAreWalkedDepthFirstInRedditsOrderWithoutMoreStubsOrAutoModerator() throws {
        let image = try XCTUnwrap(JsonLdRecipeParser.parseJson(RedditFixtures.imageWithOpRecipe) as? [Any])
        let comments = RedditRecipeParser.comments(image[1] as? [String: Any])
        XCTAssertEqual(comments.map(\.bySubmitter), [false, true, true])
        XCTAssertTrue(comments[0].body.hasPrefix("Looks great."))
        XCTAssertEqual(comments[1].body, "Ha, that works too on a busy night!")
        XCTAssertTrue(comments[2].body.hasPrefix("Thanks for looking!"))

        // AutoModerator's own comment is skipped, the replies under it are not.
        let card = try XCTUnwrap(JsonLdRecipeParser.parseJson(RedditFixtures.cardWithTranscription) as? [Any])
        let cardComments = RedditRecipeParser.comments(card[1] as? [String: Any])
        XCTAssertEqual(cardComments.count, 3)
        XCTAssertTrue(cardComments[0].body.hasPrefix("Transcription:"))
        XCTAssertEqual(cardComments.map(\.bySubmitter), [false, true, false])
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
