import XCTest
@testable import RecipeClipper

/// Port of Android's SampleRecipeTest: the tour's sample recipe (#151), from
/// `shared/sample/recipe.json`.
final class SampleRecipeTests: XCTestCase {

    func testItIsWrittenInEveryUILanguageAndEnglishStandsInForTheRest() {
        XCTAssertEqual(Set(SampleRecipe.languages), ["en", "es", "fr", "de", "it", "pt"])
        XCTAssertEqual(SampleRecipe.forLanguage("pt-BR").language, "pt")
        XCTAssertEqual(SampleRecipe.forLanguage("de").language, "de")
        XCTAssertEqual(SampleRecipe.forLanguage("ja").language, "en")
        XCTAssertEqual(SampleRecipe.forLanguage(nil).language, "en")
    }

    func testItIsSavedLikeATypedInRecipeUnderItsOwnLink() {
        let sample = SampleRecipe.forLanguage("en")
        XCTAssertEqual(sample.sourceUrl, "manual:sample")
        XCTAssertTrue(SampleRecipe.isSample(sample.sourceUrl))
        XCTAssertTrue(ManualRecipe.isManual(sample.sourceUrl))
        XCTAssertEqual(sample.origin, .manual)
        XCTAssertFalse(sample.canUpdateFromSource, "never fetched: no Update from source")
        XCTAssertNil(sample.image)
    }

    /// What the tour offers it for: scaling, units and cook mode's timers, in each language.
    func testEveryLanguagesSampleServes4ScalesAndHasATimerInItsFirstStep() throws {
        for language in SampleRecipe.languages {
            let sample = SampleRecipe.forLanguage(language)
            let words = try XCTUnwrap(LanguageWords.forTag(language), language)
            XCTAssertEqual(sample.ingredients.count, 10, language)
            XCTAssertEqual(sample.instructions.count, 6, language)
            XCTAssertEqual(Servings.parse(sample.yield, words: words), 4, language)
            XCTAssertEqual(StepTimers.parse(sample.instructions[0], words: words), 8 * 60, language)
            let doubled = IngredientRendering.render(sample.ingredients, factor: 2, system: .asWritten, convertLiquids: false, words: words)
            XCTAssertNotEqual(doubled[0], sample.ingredients[0], "\(language): the first line scales")
        }
    }

    /// #179: the file's ISO times ("PT10M") read like a parsed recipe's, in its language's words.
    func testItsTimesAreFormattedLikeAParsedRecipesInEveryLanguage() {
        let sample = SampleRecipe.forLanguage("en")
        XCTAssertEqual([sample.prepTime, sample.cookTime, sample.totalTime], ["10m", "25m", "35m"])
        for language in SampleRecipe.languages {
            let recipe = SampleRecipe.forLanguage(language)
            let times = [recipe.prepTime, recipe.cookTime, recipe.totalTime]
            let symbol = language == "en" ? "m" : "min"
            XCTAssertEqual(times, ["10\(symbol)", "25\(symbol)", "35\(symbol)"], language)
            // What lets a sample saved before #179 be fixed at every launch.
            XCTAssertEqual(times.map { SampleRecipe.formatTime($0, language: language) }, times, "\(language): formatting again changes nothing")
        }
    }

    func testTheEnglishSampleConvertsToMetric() {
        let sample = SampleRecipe.forLanguage("en")
        let metric = IngredientRendering.render(sample.ingredients, factor: 1, system: .metric, convertLiquids: false)
        XCTAssertEqual(metric[0], "30 ml olive oil")
        XCTAssertEqual(metric[7], "115 g baby spinach")
    }
}
