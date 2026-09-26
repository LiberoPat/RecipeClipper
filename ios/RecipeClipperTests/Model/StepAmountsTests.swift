import XCTest
@testable import RecipeClipper

/// Mirrors the Kotlin StepAmountsTest. Steps modelled on real recipe pages; each amount in ⟦ ⟧.
final class StepAmountsTests: XCTestCase {

    private func annotate(_ step: String, _ lines: [String], _ lang: String = "en") -> String {
        StepAmounts.marked(StepAmounts.annotate([step], lines: lines, words: LanguageWords.forTag(lang))[0])
    }

    func testTheArticleGivesWayToTheLinesAmount() {
        XCTAssertEqual(annotate("Add the carrots and cook 5 minutes.", ["2 carrots, peeled and diced"]), "Add ⟦2⟧ carrots and cook 5 minutes.")
        XCTAssertEqual(annotate("Stir in the flour.", ["250 g all-purpose flour"]), "Stir in ⟦250 g⟧ flour.")
        XCTAssertEqual(annotate("Beat the eggs well.", ["2 large eggs"]), "Beat ⟦2 large⟧ eggs well.")
        XCTAssertEqual(annotate("Add the melted butter.", ["115 g unsalted butter"]), "Add ⟦115 g⟧ melted butter.")
    }

    func testAMentionWithNoArticleTakesTheAmountAfterAVerbACommaOrAJoiningWord() {
        XCTAssertEqual(
            annotate("Whisk flour, baking powder and salt together.", ["2 cups flour", "1 tsp baking powder", "1/2 tsp salt"]),
            "Whisk ⟦2 cups⟧ flour, ⟦1 tsp⟧ baking powder and ⟦1/2 tsp⟧ salt together."
        )
    }

    func testTheAmountFollowsTheServingsAndUnitsTheReadingViewShows() {
        let lines = IngredientRendering.render(["1 cup all-purpose flour"], factor: 2.0, system: .metric, convertLiquids: false)
        XCTAssertEqual(annotate("Stir in the flour.", lines), "Stir in ⟦240 g⟧ flour.")
    }

    func testAnOldStyleUnitLendsItsAmountAndConvertsWithTheLine() {
        XCTAssertEqual(annotate("Melt the butter.", ["2 T. butter"]), "Melt ⟦2 T.⟧ butter.")
        let lines = IngredientRendering.render(["1/2 c. heavy cream"], factor: 2.0, system: .metric, convertLiquids: false)
        XCTAssertEqual(annotate("Whisk in the heavy cream.", lines), "Whisk in ⟦240 ml⟧ heavy cream.")
    }

    func testTheSameIngredientInTwoLinesStaysAsWritten() {
        XCTAssertEqual(annotate("Add the sugar.", ["1 cup sugar", "For the frosting:", "1/2 cup sugar"]), "Add the sugar.")
        XCTAssertEqual(annotate("Season with salt.", ["1 tsp salt", "salt and pepper"]), "Season with salt.")
    }

    func testAStepThatAlreadySaysHowMuchStaysAsWritten() {
        let lines = ["2 cups flour", "1 cup sugar", "115 g butter"]
        for step in [
            "Add 1 cup of the flour.", "Melt half the butter.", "Add the remaining sugar.",
            "Add the rest of the flour.", "Add 2 tablespoons butter.", "Add a little sugar.",
        ] {
            XCTAssertEqual(annotate(step, lines), step)
        }
    }

    func testALongerNameCompoundOrProseAroundTheWordStaysAsWritten() {
        for step in ["Dust with rice flour.", "Fold in the flour mixture.", "Add the lemon juice.", "Flour the counter."] {
            XCTAssertEqual(annotate(step, ["2 cups flour", "1 lemon"]), step)
        }
        XCTAssertEqual(
            annotate("Add the brown sugar and the sugar.", ["1 cup brown sugar", "1 cup sugar"]),
            "Add ⟦1 cup⟧ brown sugar and ⟦1 cup⟧ sugar."
        )
    }

    func testALineWithNoAmountItCanScaleOrUsedInPartsLendsNone() {
        XCTAssertEqual(annotate("Chop the onions.", ["2 onions (about 300 g)"]), "Chop the onions.")
        XCTAssertEqual(annotate("Season with salt.", ["salt, to taste"]), "Season with salt.")
        XCTAssertEqual(annotate("Add the flour.", ["2 cups flour, divided"]), "Add the flour.")
        XCTAssertEqual(annotate("Add the salt.", ["1 tsp salt, plus more to taste"]), "Add the salt.")
    }

    func testOnlyTheFirstMentionOfALineInAStep() {
        XCTAssertEqual(
            annotate("Melt the butter, then brush the pan with butter.", ["4 tbsp butter"]),
            "Melt ⟦4 tbsp⟧ butter, then brush the pan with butter."
        )
    }

    func testOtherLanguagesUseTheirOwnArticlesAndWords() {
        XCTAssertEqual(annotate("Ajoutez la farine et les œufs.", ["200 g de farine", "3 œufs"], "fr"), "Ajoutez ⟦200 g de⟧ farine et ⟦3⟧ œufs.")
        XCTAssertEqual(annotate("Die Butter schmelzen.", ["200 g Butter"], "de"), "⟦200 g⟧ Butter schmelzen.")
        XCTAssertEqual(annotate("Añade la harina poco a poco.", ["250 g de harina"], "es"), "Añade ⟦250 g de⟧ harina poco a poco.")
        XCTAssertEqual(annotate("Aggiungete le carote.", ["2 carote"], "it"), "Aggiungete ⟦2⟧ carote.")
        XCTAssertEqual(annotate("Junte os ovos e misture.", ["2 ovos"], "pt"), "Junte ⟦2⟧ ovos e misture.")
        XCTAssertEqual(annotate("Ajoutez le reste de la farine.", ["200 g de farine"], "fr"), "Ajoutez le reste de la farine.")
        XCTAssertEqual(annotate("醤油を加える。", ["醤油 大さじ1"], "ja"), "醤油を加える。")
    }

    func testNoWordsLeaveEveryStepAsWritten() {
        XCTAssertEqual(StepAmounts.annotate(["Add the carrots."], lines: ["2 carrots"], words: nil), [[StepAmounts.Part(text: "Add the carrots.")]])
    }
}
