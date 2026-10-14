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

    func testTheSameIngredientInTwoLinesWithDifferentAmountsIsALinkToBothNeverANumber() {
        XCTAssertEqual(annotate("Add the sugar.", ["1 cup sugar", "For the frosting:", "1/2 cup sugar"]), "Add the ⟪sugar|0,2⟫.")
        XCTAssertEqual(annotate("Season with salt.", ["1 tsp salt", "salt and pepper"]), "Season with ⟪salt|0,1⟫.")
        // "flour for dusting" has no amount: the step can't say which use it means (#254).
        XCTAssertEqual(annotate("Stir in the flour.", ["2 cups flour", "flour, for dusting"]), "Stir in the ⟪flour|0,1⟫.")
        let link = StepAmounts.annotate(["Melt the butter."], lines: ["200g butter", "100g butter"], words: .english)[0]
        XCTAssertEqual(link, [.init(text: "Melt the "), .init(text: "butter", lines: [0, 1]), .init(text: ".")])
    }

    func testTwoLinesLendingTheSameAmountAreOneAnswer() {
        XCTAssertEqual(
            annotate("Add the butter.", ["30g / 2 tbsp unsalted butter", "30g / 2 tbsp unsalted butter, cold and cubed"]),
            "Add ⟦30g / 2 tbsp⟧ butter."
        )
    }

    func testAStepNamesALineByItsLastWordsWhenTheRestOnlyDescribeIt() {
        XCTAssertEqual(annotate("Heat the oil.", ["1 tbsp. extra-virgin olive oil"]), "Heat ⟦1 tbsp.⟧ oil.")
        XCTAssertEqual(annotate("Add the stock.", ["2 cups low-sodium beef stock"]), "Add ⟦2 cups⟧ stock.")
        XCTAssertEqual(annotate("Microwave the butter.", ["8 tablespoons of salted butter"]), "Microwave ⟦8 tablespoons of⟧ butter.")
        XCTAssertEqual(annotate("Cream together butter and sugar.", ["1/2 cup butter", "1 cup granulated sugar"]), "Cream together ⟦1/2 cup⟧ butter and ⟦1 cup⟧ sugar.")
        // Another line ending in the same words could be the one; a word that isn't a
        // describing word makes another ingredient.
        XCTAssertEqual(annotate("Add the oil.", ["1 tbsp olive oil", "1 tsp toasted sesame oil"]), "Add the ⟪oil|0,1⟫.")
        for step in ["Spoon the sauce over.", "Add the leaves.", "Beat the butter."] {
            XCTAssertEqual(annotate(step, ["2 tsp Worcestershire sauce", "2 bay leaves", "1/2 cup peanut butter"]), step)
        }
    }

    func testAStepsTitleBeforeADashIsNotItsFirstMention() {
        XCTAssertEqual(annotate("Onions - Add the onions and stir.", ["2 onions, sliced"]), "Onions - Add ⟦2⟧ onions and stir.")
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

    func testSpanishNamesItsLinesPastTheWordsAfterTheNoun() {
        XCTAssertEqual(annotate("Añade el aceite de oliva y la sal.", ["100 ml de aceite de oliva virgen extra", "sal"], "es"), "Añade ⟦100 ml de⟧ aceite de oliva y la sal.")
        XCTAssertEqual(annotate("Incorpora la cebolla picada.", ["1 cebolla mediana"], "es"), "Incorpora ⟦1⟧ cebolla picada.")
        XCTAssertEqual(annotate("Añade el agua caliente.", ["100 ml de agua"], "es"), "Añade ⟦100 ml de⟧ agua caliente.")
        XCTAssertEqual(annotate("Pica el diente de ajo.", ["1 diente de ajo"], "es"), "Pica ⟦1⟧ diente de ajo.")
        // Another kind (rice flour, sesame oil) is another ingredient; two of a kind are a link.
        XCTAssertEqual(annotate("Añade la harina.", ["200 g de harina de arroz"], "es"), "Añade la harina.")
        XCTAssertEqual(annotate("Añade el aceite.", ["2 cucharadas de aceite de oliva", "1 cucharada de aceite de sésamo"], "es"), "Añade el ⟪aceite|0,1⟫.")
        XCTAssertEqual(annotate("Añade los pimientos.", ["1 pimiento verde", "1 pimiento rojo"], "es"), "Añade los ⟪pimientos|0,1⟫.")
        // A dish named after its ingredients, and greasing, stay as written.
        XCTAssertEqual(annotate("Refrigera el arroz con leche.", ["1 taza de arroz blanco"], "es"), "Refrigera el arroz con leche.")
        XCTAssertEqual(annotate("Engrasa el molde con la mantequilla.", ["100 g de mantequilla"], "es"), "Engrasa el molde con la mantequilla.")
    }

    func testFrenchReadsElidedNamesDescribingWordsAfterTheNounAndStates() {
        XCTAssertEqual(annotate("Faites chauffer l'huile d'olive.", ["3 cuillères à soupe d'huile d'olive"], "fr"), "Faites chauffer ⟦3 cuillères à soupe d'⟧huile d'olive.")
        XCTAssertEqual(annotate("Ajoutez le beurre doux coupé en dés.", ["90 g de beurre doux"], "fr"), "Ajoutez ⟦90 g de⟧ beurre doux coupé en dés.")
        XCTAssertEqual(annotate("Versez la farine puis le beurre fondu.", ["120 g de farine de blé", "50 g de beurre"], "fr"), "Versez ⟦120 g de⟧ farine puis ⟦50 g de⟧ beurre fondu.")
        XCTAssertEqual(annotate("Ajoutez le beurre.", ["100 g de beurre doux", "30 g de beurre demi-sel"], "fr"), "Ajoutez le ⟪beurre|0,1⟫.")
        // "au" also makes dish names ("fenouil au citron"): it is no article.
        XCTAssertEqual(annotate("Ajoutez le fenouil au citron.", ["1 fenouil", "1/2 citron"], "fr"), "Ajoutez ⟦1⟧ fenouil au citron.")
        XCTAssertEqual(annotate("Farinez le plan de travail avec la farine.", ["250 g de farine"], "fr"), "Farinez le plan de travail avec la farine.")
    }

    func testGermanCompoundsAnswerTheirLastPartOnlyWhenListed() {
        XCTAssertEqual(annotate("Das Mehl mit dem Zucker mischen.", ["250 g Weizenmehl", "100 g Zucker"], "de"), "⟦250 g⟧ Mehl mit ⟦100 g⟧ Zucker mischen.")
        XCTAssertEqual(annotate("Die Sahne erhitzen und zum Mehl geben.", ["200 ml Schlagsahne", "100 g Mehl"], "de"), "⟦200 ml⟧ Sahne erhitzen und zu ⟦100 g⟧ Mehl geben.")
        XCTAssertEqual(annotate("Den Lauch waschen.", ["1 Stg Lauch"], "de"), "⟦1 Stg⟧ Lauch waschen.")
        XCTAssertEqual(annotate("Die Butter schmelzen.", ["100 g Erdnussbutter"], "de"), "Die Butter schmelzen.")
        XCTAssertEqual(annotate("Die Kerne entfernen.", ["50 g Sonnenblumenkerne"], "de"), "Die Kerne entfernen.")
        XCTAssertEqual(annotate("Das Mehl sieben.", ["200 g Weizenmehl", "50 g Mandelmehl"], "de"), "Das ⟪Mehl|0,1⟫ sieben.")
        XCTAssertEqual(annotate("Die Form mit der Butter einfetten.", ["200 g Butter"], "de"), "Die Form mit der Butter einfetten.")
    }

    func testItalianAndPortugueseReadContractionsThatSayWhereAndTheirVerbs() {
        XCTAssertEqual(annotate("Versate l'olio e il sale.", ["100 ml di olio extravergine di oliva", "sale"], "it"), "Versate ⟦100 ml di⟧ olio e il sale.")
        XCTAssertEqual(annotate("Unite farina, cacao e lievito.", ["200 g di farina", "40 g di cacao amaro", "1 bustina di lievito vanigliato"], "it"), "Unite ⟦200 g di⟧ farina, ⟦40 g di⟧ cacao e ⟦1 bustina di⟧ lievito.")
        XCTAssertEqual(annotate("Versate il latte nella farina.", ["250 ml di latte", "300 g di farina"], "it"), "Versate ⟦250 ml di⟧ latte in ⟦300 g di⟧ farina.")
        XCTAssertEqual(annotate("Imburrate la teglia e infarinatela con la farina.", ["300 g di farina"], "it"), "Imburrate la teglia e infarinatela con la farina.")
        XCTAssertEqual(annotate("Adicione a farinha e misture.", ["2 xícaras (chá) de farinha de trigo"], "pt"), "Adicione ⟦2 xícaras (chá) de⟧ farinha e misture.")
        XCTAssertEqual(annotate("Dissolva a gelatina na água fria.", ["1 colher (chá) de gelatina em pó sem sabor", "3 colheres (sopa) de água fria"], "pt"), "Dissolva a gelatina em ⟦3 colheres (sopa) de⟧ água fria.")
        XCTAssertEqual(annotate("Adicione os ovos.", ["2 unidades de ovos (tamanho médio)"], "pt"), "Adicione ⟦2 unidades de⟧ ovos.")
        XCTAssertEqual(annotate("Unte a forma com manteiga e polvilhe farinha.", ["100 g de manteiga", "2 xícaras de farinha de trigo"], "pt"), "Unte a forma com manteiga e polvilhe farinha.")
    }

    func testNoWordsLeaveEveryStepAsWritten() {
        XCTAssertEqual(StepAmounts.annotate(["Add the carrots."], lines: ["2 carrots"], words: nil), [[StepAmounts.Part(text: "Add the carrots.")]])
    }
}
