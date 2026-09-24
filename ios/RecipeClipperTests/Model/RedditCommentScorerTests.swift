import XCTest
@testable import RecipeClipper

/// The same cases and expectations as Android's `RedditCommentScorerTest`.
final class RedditCommentScorerTests: XCTestCase {

    private func score(_ text: String) -> Int { RedditCommentScorer.score(text) }

    private let transcription = """
        Transcription:

        **Ingredients**
        - 1 cup dates
        - 1 cup flour

        **Directions**
        1. Mix.
        2. Bake at 350°F for 1 hour.
        """

    private let recipeNoMention = "Ingredients\n1 cup dates\n1 cup flour\nDirections\nMix.\nBake."

    func testAFullTranscriptionScoresEverySignal() {
        // Ingredients 3 + Directions 3 + "Transcription" 2 + 7 lines 1
        XCTAssertEqual(score(transcription), 9)
    }

    func testEachSignalOnItsOwn() {
        XCTAssertEqual(score(recipeNoMention), 7)
        XCTAssertEqual(score("Ingredients:\n1 cup dates\n1 cup water\nwalnuts"), 4)
        XCTAssertEqual(score("Directions\nMix.\nBake.\nCool."), 4)
        XCTAssertEqual(score("I transcribed it below.\nIt took a while.\nHer writing is tiny.\nEnjoy."), 3)
        XCTAssertEqual(score("Such a lovely card.\nMy nan had one.\nSame tin too.\nThanks for sharing."), 1)
    }

    func testShortRemarksScoreNothingEvenWhenTheyMentionTranscribing() {
        XCTAssertEqual(score("Looks delicious!"), 0)
        XCTAssertEqual(score("Could someone transcribe this? I can't read it."), 0)
        XCTAssertEqual(score(""), 0)
        XCTAssertEqual(score("   \n\n  "), 0)
    }

    func testDeletedAndRemovedCommentsScoreNothing() {
        XCTAssertEqual(score("[deleted]"), 0)
        XCTAssertEqual(score("[removed]"), 0)
    }

    func testAnImplausiblyLongCommentLosesAPoint() {
        let long = "Ingredients\n" + (1...80).map { "\($0) g thing" }.joined(separator: "\n") +
            "\nMethod\n" + (1...80).map { "Stir \($0) times." }.joined(separator: "\n")
        XCTAssertEqual(score(long), 5)
    }

    func testPickTakesTheBestCommentThatSplits() {
        let partial = "Ingredients:\n1 cup dates\n1 cup water\nwalnuts"
        XCTAssertEqual(RedditCommentScorer.pick(["Lovely!", partial, recipeNoMention, transcription]), transcription)
    }

    func testTiesGoToTheEarlierComment() {
        let other = "Ingredients\n2 cups dates\n2 cups flour\nDirections\nMix well.\nBake."
        XCTAssertEqual(RedditCommentScorer.pick([recipeNoMention, other]), recipeNoMention)
        XCTAssertEqual(RedditCommentScorer.pick([other, recipeNoMention]), other)
    }

    func testAHighScoreWithoutACleanSplitIsPassedOverNeverGuessedAt() {
        let noSteps = "Transcription:\nIngredients\n1 cup dates\n1 cup flour\nMix and bake at 350."
        XCTAssertEqual(RedditCommentScorer.pick([noSteps, recipeNoMention]), recipeNoMention)
        XCTAssertNil(RedditCommentScorer.pick([noSteps]))
    }

    func testNoCommentsOrNoneThatAreRecipesPicksNothing() {
        XCTAssertNil(RedditCommentScorer.pick([]))
        XCTAssertNil(RedditCommentScorer.pick(["Recipe?", "[deleted]", "Could someone transcribe this?"]))
    }
}
