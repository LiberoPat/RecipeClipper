import XCTest
@testable import RecipeClipper

/// Chef mode's gate (#100, #129). The differential corpus's `Short` rows pin it to the Kotlin;
/// these name the rules. Several rows are replies from the #105/#129 evaluation
/// (tools/eval/gold/chef.jsonl).
final class ShortStepCheckTests: XCTestCase {
    private let en = LanguageWords.english

    private func ok(_ original: String, _ short: String, words: LanguageWords? = nil, lines: [String] = [],
                    file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(ShortStepCheck.accept(original, short, words: words ?? en, ingredients: lines),
                       ShortStepCheck.tidy(short), file: file, line: line)
    }

    private func no(_ original: String, _ short: String?, words: LanguageWords? = nil, lines: [String] = [],
                    file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertNil(ShortStepCheck.accept(original, short, words: words ?? en, ingredients: lines), file: file, line: line)
    }

    func testAShorterStepWithTheSameNumbersAndWordsPasses() {
        ok("Preheat the oven to 350°F (180°C) and grease a 9x13-inch baking pan.", "Preheat oven to 350°F (180°C); grease a 9x13-inch baking pan.")
        ok("Bake for 25 to 30 minutes, until the top is golden.", "Bake 25–30 min until golden.")
        ok("Stir in ½ teaspoon of salt until it dissolves.", "Stir in ½ tsp salt until dissolved.")
    }

    func testANumberThatIsNotInTheStepFails() {
        no("Bake for 20 minutes, until the top is golden.", "Bake 25 min.")
    }

    func testAChangedOrDroppedTimeOrTemperatureFails() {
        no("Microwave for 30 seconds, then stir well.", "Microwave 30 min, stir.")
        no("Simmer for 20 minutes, stirring often so it doesn't catch.", "Simmer, stirring often.")
        no("Roast at 200°C for 1 hour, turning halfway through.", "Roast at 200°F, 1 hr.")
    }

    func testADroppedActionEquipmentOrQualifierFails() {
        no("Preheat the oven to 375°F. Lightly grease (or line with parchment) two baking sheets.", "Preheat the oven to 375°F.")
        no("Beat in the egg, again beating until smooth. Scrape the bottom and sides of the bowl with a spatula.", "Beat in the egg, then scrape down the bowl.")
        no("Set aside for 30 mins to rest if you have time, or start cooking straight away.", "Set aside for 30 mins to rest, then start cooking.")
        no("Remove from the oven and let cool in the pan for a few minutes. Then remove and cool on a rack.", "Remove from oven and let cool in pan. Then remove and cool on rack.")
    }

    func testADroppedIngredientFailsWhenTheRecipeNamesIt() {
        let lines = ["2 cloves garlic, minced", "1 onion, diced"]
        no("Add the garlic and onion and cook until soft.", "Add garlic; cook until soft.", lines: lines)
        ok("Add the garlic and onion and cook until soft.", "Add garlic and onion; cook until soft.", lines: lines)
        ok("Add the garlic and onion and cook until soft.", "Add garlic; cook until soft.")
    }

    func testAnAddedWordFails() {
        no("Whisk the eggs with the sugar until pale and thick.", "Whisk eggs, sugar and vanilla until pale.")
        no("In the meantime wrap tofu in a clean, absorbent towel and set something heavy on top to press out the liquid.", "Press tofu for an hour.")
    }

    func testEndingsAbbreviationsAndUnitsCountAsTheSameWord() {
        ok("Store cookies, well wrapped, at room temperature for up to 5 days; freeze for longer storage.", "Store cookies, well wrapped, at room temp for up to 5 days. Freeze for longer.")
        ok("Once the tofu is done baking, add directly to the sauce and marinate for 5 minutes, stirring occasionally.", "Once the tofu is done baking, add directly to the sauce and marinate for 5 mins, stir.")
        ok("Let the dough rest, then add the rest of the flour and knead until smooth.", "Let the dough rest, then knead in flour until smooth.")
    }

    func testOtherLanguages() {
        let de = LanguageWords.forTag("de")!
        ok("Nach und nach 1,5 l Brühe zugießen und dabei ständig rühren.", "1,5 l Brühe nach und nach zugießen, ständig rühren.", words: de)
        no("Nach und nach 1,5 l Brühe zugießen und dabei ständig rühren.", "1,5 l Brühe nach und nach zugießen.", words: de)
        let ja = LanguageWords.forTag("ja")!
        ok("鍋に入れて、中火で５分煮る。ときどき混ぜる。", "鍋に入れ、中火で5分煮てときどき混ぜる。", words: ja)
        no("鍋に入れて、中火で５分煮る。ときどき混ぜる。", "中火で5分煮る。", words: ja)
    }

    func testItMustBeShorterAndThereMustBeWords() {
        XCTAssertNil(ShortStepCheck.accept("Stir well.", "Stir it well.", words: en))
        XCTAssertNil(ShortStepCheck.accept("Stir well until smooth.", "   ", words: en))
        XCTAssertNil(ShortStepCheck.accept("Stir well until smooth.", nil, words: en))
        XCTAssertNil(ShortStepCheck.accept("Stir well until smooth.", "Stir.", words: nil))
    }

    func testNumbersAreReadAsWritten() {
        XCTAssertEqual(ShortStepCheck.numbers("1,5 kg, 1 1/2 cups, 1 ½, ½, 10–12"), ["1,5", "1 1/2", "1½", "½", "10", "12"])
    }
}
