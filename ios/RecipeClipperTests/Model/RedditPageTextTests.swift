import XCTest
@testable import RecipeClipper

/// Mirrors Android's RedditPageTextTest and RedditTextPageTest, on the same fixtures
/// (`shared/fixtures/reddit/page-text`): what `RCReddit.text()` returned in the clip view in
/// September 2026, with the usernames replaced and the icons cut.
final class RedditPageTextTests: XCTestCase {

    private final class Anchor {}

    private func fixture(_ name: String) throws -> String {
        let url = try XCTUnwrap(
            Bundle(for: Anchor.self).url(forResource: name, withExtension: "html", subdirectory: "fixtures/reddit/page-text")
        )
        return try String(contentsOf: url, encoding: .utf8)
    }

    func testATextPostsBodyIsReadABlockALineAllOfItThoughThePageCollapsesIt() throws {
        let text = RedditPageText.parse(try fixture("self-post"))
        XCTAssertEqual(text.title, "Braised Chicken with White Beans and Zucchini")
        XCTAssertEqual(
            text.body.first,
            "Recipe here originally: https://www.triedandtruerecipe.com/braised-chicken-with-white-beans-and-zucchini/"
        )
        XCTAssertEqual(text.body[1], "2 pounds bone-in, skin-on chicken thighs; about 5 to 6 thighs total")
        XCTAssertTrue(text.body.contains("For serving:"))
        XCTAssertEqual(
            text.body.last,
            "Note 1: I use 1 teaspoon kosher salt per pound of chicken. If your chicken weighs a "
                + "little less or a little more than 2 pounds, adjust the salt accordingly."
        )
        // Only the comments the page loaded: 4 of the post's 13.
        XCTAssertEqual(text.comments.map(\.author), ["salt_fat_acid_fan", "oven_mitt_22", "braise_me", "zest_quest"])
        XCTAssertEqual(
            text.comments[1].lines,
            ["This looks great!", "I'm making it this weekend since I have all the ingredients at home, thank you for sharing the recipe."]
        )
    }

    func testAPhotoPostHasNoBodyAndTheRecipeInThePostersCommentKeepsItsLines() throws {
        let text = RedditPageText.parse(try fixture("gallery-op-comment"))
        XCTAssertEqual(text.title, "Beef Bourguignon")
        XCTAssertEqual(text.body, [])
        let op = try XCTUnwrap(text.comments.first)
        XCTAssertEqual(op.author, "stew_and_bread")
        XCTAssertEqual(op.depth, 0)
        XCTAssertEqual(op.lines[0], "Ingredients (serves 4):")
        XCTAssertEqual(op.lines[1], "800g beef chuck, diced (any good stewing cut works)")
        // A search link Reddit wraps round a phrase stays inside its line.
        XCTAssertEqual(op.lines[2], "500ml Burgundy red wine (two thirds of a bottle)")
        // Replies follow their comment, one deeper, each with only its own text.
        XCTAssertEqual(text.comments.map(\.depth), [0, 0, 1, 0, 1, 0, 1])
        XCTAssertFalse(text.comments.dropFirst().contains { $0.lines.contains { $0.hasPrefix("Ingredients (serves 4)") } })
    }

    func testListsHeadingsPreformattedTextAndLineBreaksEachGiveTheirOwnLines() {
        let html = """
            <shreddit-post post-title="  Pie  "><div slot="text-body"><div property="schema:articleBody">
              <h2>Crust</h2>
              <ul><li><p>1 cup <a href="#">flour</a></p></li><li>1/2 cup butter<ul><li>cold</li></ul></li></ul>
              <p>Mix.<br>Chill.</p>
              <pre><code>Bake 30 min
            Cool</code></pre>
            </div></div></shreddit-post>
            """
        let text = RedditPageText.parse(html)
        XCTAssertEqual(text.title, "Pie")
        XCTAssertEqual(text.body, ["Crust", "1 cup flour", "1/2 cup butter", "cold", "Mix.", "Chill.", "Bake 30 min", "Cool"])
    }

    func testAPageWithNoPostInItReadsAsEmpty() {
        XCTAssertTrue(RedditPageText.parse("<html><body><p>Checking your browser</p></body></html>").isEmpty)
    }

    func testWithNoPostTitleTheTitleHeadingIsTheTitle() {
        XCTAssertEqual(RedditPageText.parse(#"<shreddit-post><h1 slot="title"> Soup </h1></shreddit-post>"#).title, "Soup")
    }

    // MARK: The Text view's page (RedditTextPage)

    private func page(_ text: RedditPageText) -> String {
        RedditTextPage.html(text, commentsHeading: "Comments", loadedNote: "Only loaded ones.", author: { "u/\($0)" })
    }

    func testThePostIsAPageOfParagraphsTheTitleFirst() {
        let html = page(RedditPageText(title: "Pie", body: ["3 apples", "Bake."], comments: []))
        XCTAssertTrue(html.contains("<h1>Pie</h1><p>3 apples</p><p>Bake.</p>"))
        XCTAssertFalse(html.contains("Comments"))
    }

    func testCommentsFollowUnderAHeadingAndANoteTheirLabelsUnselectableRepliesIndented() {
        let html = page(RedditPageText(title: "Pie", body: [], comments: [
            RedditPageComment(author: "baker", depth: 0, lines: ["Use 4."]),
            RedditPageComment(author: "op", depth: 1, lines: ["Thanks!"]),
        ]))
        XCTAssertTrue(html.contains(#"<h2 class="label">Comments</h2><p class="label note">Only loaded ones.</p>"#))
        XCTAssertTrue(html.contains(#"<section style="margin-left:0px"><p class="label author">u/baker</p><p>Use 4.</p></section>"#))
        XCTAssertTrue(html.contains(#"<section style="margin-left:12px"><p class="label author">u/op</p><p>Thanks!</p></section>"#))
        XCTAssertTrue(html.contains(".label{-webkit-user-select:none;user-select:none}"))
    }

    func testWhatThePostSaysIsTextNeverMarkup() {
        let html = page(RedditPageText(title: "<b>Pie</b> & \"more\"", body: ["<script>alert(1)</script>"], comments: []))
        XCTAssertTrue(html.contains("<h1>&lt;b&gt;Pie&lt;/b&gt; &amp; &quot;more&quot;</h1>"))
        XCTAssertFalse(html.contains("<script>"))
    }
}
