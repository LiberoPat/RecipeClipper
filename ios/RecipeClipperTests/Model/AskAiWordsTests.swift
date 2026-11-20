import XCTest
@testable import RecipeClipper

/// The prompt's English words in the String Catalog are the ones `AskAiPrompt.Words.english` (and the
/// tests) are written with: the two cannot drift apart. Android's `AskAiWordsTest`.
final class AskAiWordsTests: XCTestCase {

    func testTheEnglishCatalogIsTheEnglishWords() throws {
        let words = Strings.askAiWords
        let english = AskAiPrompt.Words.english
        try XCTSkipUnless(words.language == english.language, "the test run is not in English")
        for day in 1...7 { XCTAssertEqual(words.weekday(day), english.weekday(day)) }
        XCTAssertEqual(words.wantToCook("x"), english.wantToCook("x"))
        XCTAssertEqual(words.changeThis("x"), english.changeThis("x"))
        XCTAssertEqual(words.recipeBelow, english.recipeBelow)
        XCTAssertEqual(words.whatCanIMake, english.whatCanIMake)
        XCTAssertEqual(words.useUpFirst("x"), english.useUpFirst("x"))
        XCTAssertEqual(words.inPantry("x"), english.inPantry("x"))
        XCTAssertEqual(words.planIntro, english.planIntro)
        XCTAssertEqual(words.favourites("x"), english.favourites("x"))
        XCTAssertEqual(words.cookedLately("x"), english.cookedLately("x"))
        XCTAssertEqual(words.alsoWish("x"), english.alsoWish("x"))
        XCTAssertEqual(words.replyOne, english.replyOne)
        XCTAssertEqual(words.replyPlan, english.replyPlan)
        XCTAssertEqual(words.replyChanged, english.replyChanged)
        XCTAssertEqual(words.recipeFields, english.recipeFields)
        XCTAssertEqual(words.unitsAsWritten, english.unitsAsWritten)
        XCTAssertEqual(words.unitsMetric, english.unitsMetric)
        XCTAssertEqual(words.unitsOunces, english.unitsOunces)
        XCTAssertEqual(words.temperatureCelsius, english.temperatureCelsius)
        XCTAssertEqual(words.temperatureFahrenheit, english.temperatureFahrenheit)
        XCTAssertEqual(words.writeIn("x"), english.writeIn("x"))
        XCTAssertEqual(words.noteOutside, english.noteOutside)
        XCTAssertEqual(words.planMealsIntro("Lunch, Dinner"), english.planMealsIntro("Lunch, Dinner"))
        XCTAssertEqual(words.savedRecipesIntro, english.savedRecipesIntro)
        XCTAssertEqual(words.useSavedOnly, english.useSavedOnly)
        XCTAssertEqual(words.useSavedMix(3), english.useSavedMix(3))
        XCTAssertEqual(words.replyPlanMeals, english.replyPlanMeals)
    }

    func testTheCutOffMealsSentenceNamesThePairsAndKeepsTheJsonKeys() {
        let words = Strings.cutOffWords
        let text = words.planMeals("2026-11-20 (Dinner)")
        XCTAssertTrue(text.contains("2026-11-20 (Dinner)"))
        XCTAssertTrue(text.contains("\"day\" and \"meal\""))
    }
}
