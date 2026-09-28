import XCTest
@testable import RecipeClipper

/// Mirrors the Kotlin IngredientNameTest.
final class IngredientNameTests: XCTestCase {

    // drops the amount, unit and preparation
    func testDropsTheAmountUnitAndPreparation() {
        XCTAssertEqual(IngredientName.of("1 cup all-purpose flour"), "all purpose flour")
        XCTAssertEqual(IngredientName.of("3 Tbsp. unsalted butter, melted"), "unsalted butter")
        XCTAssertEqual(IngredientName.of("2 large eggs, beaten"), "eggs")
        XCTAssertEqual(IngredientName.of("3/4 cup packed light brown sugar"), "light brown sugar")
        XCTAssertEqual(IngredientName.of("2-3 tablespoons fresh lemon juice"), "fresh lemon juice")
        XCTAssertEqual(IngredientName.of("2 medium onions, finely chopped"), "onions")
    }

    // drops an old-style unit, c, T or t
    func testDropsAnOldStyleUnitCTOrT() {
        XCTAssertEqual(IngredientName.of("1/2 c. heavy cream"), "heavy cream")
        XCTAssertEqual(IngredientName.of("2 C flour"), "flour")
        XCTAssertEqual(IngredientName.of("2 T. butter"), "butter")
        XCTAssertEqual(IngredientName.of("1 t salt"), "salt")
    }

    // drops alternate measures, package sizes and compound amounts
    func testDropsAlternateMeasuresPackageSizesAndCompoundAmounts() {
        XCTAssertEqual(IngredientName.of("1 1/2 cups (190 g) all-purpose flour"), "all purpose flour")
        XCTAssertEqual(IngredientName.of("1 cup/120 grams bread flour"), "bread flour")
        XCTAssertEqual(IngredientName.of("1 cup plus 2 tbsp (140 g) flour"), "flour")
        XCTAssertEqual(IngredientName.of("1 (14 oz) can diced tomatoes"), "diced tomatoes")
        XCTAssertEqual(IngredientName.of("1 can (14 oz) coconut milk"), "coconut milk")
        XCTAssertEqual(IngredientName.of("1 (8-ounce) package cream cheese, softened"), "cream cheese")
    }

    // drops sizes and containers, and the trailing clauses
    func testDropsSizesAndContainersAndTheTrailingClauses() {
        XCTAssertEqual(IngredientName.of("3 cloves garlic, minced"), "garlic")
        XCTAssertEqual(IngredientName.of("1 pinch of salt"), "salt")
        XCTAssertEqual(IngredientName.of("a pinch of nutmeg"), "nutmeg")
        XCTAssertEqual(IngredientName.of("1 heaping cup flour"), "flour")
        XCTAssertEqual(IngredientName.of("1 quart milk"), "milk")
        XCTAssertEqual(IngredientName.of("4 boneless, skinless chicken breasts"), "chicken breasts")
        XCTAssertEqual(IngredientName.of("1-inch piece fresh ginger, peeled and grated"), "fresh ginger")
        XCTAssertEqual(IngredientName.of("1 tablespoon olive oil, plus more for drizzling"), "olive oil")
        XCTAssertEqual(IngredientName.of("Kosher salt, to taste"), "kosher salt")
        XCTAssertEqual(IngredientName.of("Fresh basil leaves, for serving"), "fresh basil leaves")
        XCTAssertEqual(IngredientName.of("Vegetable oil, for frying"), "vegetable oil")
    }

    // a conjunction inside a table alias is part of the name
    func testAConjunctionInsideATableAliasIsPartOfTheName() {
        XCTAssertEqual(IngredientName.of("1 cup half-and-half"), "half and half")
    }

    // what isn't one ingredient has no name
    func testWhatIsntOneIngredientHasNoName() {
        XCTAssertNil(IngredientName.of(""))
        XCTAssertNil(IngredientName.of("   "))
        XCTAssertNil(IngredientName.of("For the frosting:"))
        XCTAssertNil(IngredientName.of("Salt and pepper, to taste"))
        XCTAssertNil(IngredientName.of("1/2 cup butter or margarine"))
        XCTAssertNil(IngredientName.of("Juice of 1 lemon"))
    }

    // matches only the same ingredient, with plain modifiers
    func testMatchesOnlyTheSameIngredientWithPlainModifiers() {
        XCTAssertTrue(IngredientName.matches("unsalted butter", "butter"))
        XCTAssertTrue(IngredientName.matches("Butter", "unsalted butter"))
        XCTAssertTrue(IngredientName.matches("flour", "flour"))
        XCTAssertTrue(IngredientName.matches("all-purpose flour", "flour"))
        XCTAssertTrue(IngredientName.matches("extra virgin olive oil", "olive oil"))
        XCTAssertTrue(IngredientName.matches("large eggs", "eggs"))
        XCTAssertFalse(IngredientName.matches("butter beans", "butter"))
        XCTAssertFalse(IngredientName.matches("flour tortillas", "flour"))
        XCTAssertFalse(IngredientName.matches("", "butter"))
        XCTAssertFalse(IngredientName.matches("buttermilk", "milk"))
    }

    // a compound name never matches its shorter head noun, either way
    func testACompoundNameNeverMatchesItsShorterHeadNounEitherWay() throws {
        let pairs = [
            ("rice flour", "flour"), ("almond flour", "flour"), ("peanut butter", "butter"),
            ("apple butter", "butter"), ("condensed milk", "milk"), ("coconut milk", "milk"),
            ("brown sugar", "sugar"), ("whole milk", "milk"), ("salted butter", "unsalted butter"),
        ]
        for (compound, head) in pairs {
            XCTAssertFalse(IngredientName.matches(compound, head), "\(compound) / \(head)")
            XCTAssertFalse(IngredientName.matches(head, compound), "\(head) / \(compound)")
        }
        let de = try XCTUnwrap(LanguageWords.forTag("de"))
        XCTAssertTrue(IngredientName.matches("ungesalzene Butter", "Butter", words: de))
        XCTAssertFalse(IngredientName.matches("Erdnuss Butter", "Butter", words: de))
        let fr = try XCTUnwrap(LanguageWords.forTag("fr"))
        XCTAssertFalse(IngredientName.matches("farine de riz", "riz", words: fr))
        let ja = try XCTUnwrap(LanguageWords.forTag("ja"))
        XCTAssertTrue(IngredientName.matches("無塩バター", "バター", words: ja))
        XCTAssertFalse(IngredientName.matches("ピーナッツバター", "バター", words: ja))
    }

    // MARK: Listed singular/plural pairs (#191)

    // a listed pair is one name in either number, and only its number
    func testAListedPairIsOneNameInEitherNumberAndOnlyItsNumber() {
        XCTAssertTrue(IngredientName.matches("onions", "onion"))
        XCTAssertTrue(IngredientName.matches("Onion", "onions"))
        XCTAssertTrue(IngredientName.matches("red onion", "red onions"))
        XCTAssertTrue(IngredientName.matches("yellow onions", "yellow onion"))
        XCTAssertTrue(IngredientName.matches("large eggs", "egg"))
        XCTAssertTrue(IngredientName.matches("bay leaves", "bay leaf"))
        XCTAssertTrue(IngredientName.matches("tomatoes", "tomato"))
        XCTAssertTrue(IngredientName.matches("potatoes", "potato"))
        XCTAssertTrue(IngredientName.matches("berries", "berry"))
        XCTAssertTrue(IngredientName.matches("garlic cloves", "garlic clove"))
        // The owner (2026-09-27): a colour is a different onion, in either number.
        let different = [
            ("red onion", "onion"), ("red onions", "onion"), ("red onion", "onions"),
            ("red onion", "yellow onion"), ("yellow onions", "white onion"), ("yellow onion", "white onions"),
            ("onion powder", "onion"), ("onion powder", "onions"), ("rice flour", "flour"),
            ("peas", "pea shoots"), ("pea", "pea shoots"),
        ]
        for (a, b) in different {
            XCTAssertFalse(IngredientName.matches(a, b), "\(a) / \(b)")
            XCTAssertFalse(IngredientName.matches(b, a), "\(b) / \(a)")
        }
    }

    // nothing is inferred from a word that isn't listed
    func testNothingIsInferredFromAWordThatIsntListed() {
        for word in ["glass", "hummus", "asparagus", "couscous", "molasses"] {
            XCTAssertEqual(IngredientName.key(word, words: .english), word)
        }
        XCTAssertEqual(IngredientName.key("peas", words: .english), "pea")
        XCTAssertFalse(IngredientName.matches("glass", "gla"))
        XCTAssertFalse(IngredientName.matches("hummus", "hummu"))
        XCTAssertFalse(IngredientName.matches("asparagus", "asparagu"))
        XCTAssertFalse(IngredientName.matches("couscous", "couscou"))
        XCTAssertFalse(IngredientName.matches("molasses", "molasse"))
        XCTAssertFalse(IngredientName.matches("peppers", "pepper")) // the spice and the vegetable: not listed
    }

    // the key is trimmed, lowercase, and each listed plural singular
    func testTheKeyIsTrimmedLowercaseAndEachListedPluralSingular() throws {
        XCTAssertEqual(IngredientName.key("  Red Onions ", words: .english), "red onion")
        XCTAssertEqual(IngredientName.key("onions, sliced", words: .english), "onion, sliced")
        XCTAssertEqual(IngredientName.key(" Onions ", words: nil), "onions")
        XCTAssertTrue(IngredientName.same("Onions", "onion", words: .english))
        XCTAssertFalse(IngredientName.same("2 onions", "onion", words: .english))
        let fr = try XCTUnwrap(LanguageWords.forTag("fr"))
        XCTAssertEqual(IngredientName.key("pommes de terre", words: fr), "pomme de terre")
        XCTAssertEqual(IngredientName.key("oignons rouges", words: fr), "oignon rouge")
        let ja = try XCTUnwrap(LanguageWords.forTag("ja"))
        XCTAssertEqual(IngredientName.key("玉ねぎ", words: ja), "玉ねぎ")
    }

    // each language lists its own pairs
    func testEachLanguageListsItsOwnPairs() {
        func matches(_ a: String, _ b: String, _ tag: String) -> Bool { IngredientName.matches(a, b, words: LanguageWords.forTag(tag)!) }
        XCTAssertTrue(matches("Zwiebeln", "Zwiebel", "de"))
        XCTAssertTrue(matches("Eier", "Ei", "de"))
        XCTAssertTrue(matches("rote Zwiebeln", "rote Zwiebel", "de"))
        XCTAssertFalse(matches("rote Zwiebeln", "Zwiebel", "de"))
        XCTAssertTrue(matches("cebollas", "cebolla", "es"))
        XCTAssertTrue(matches("cebollas rojas", "cebolla roja", "es"))
        XCTAssertFalse(matches("cebollas rojas", "cebolla", "es"))
        XCTAssertTrue(matches("oignons", "oignon", "fr"))
        XCTAssertTrue(matches("uova", "uovo", "it"))
        XCTAssertTrue(matches("ovos", "ovo", "pt"))
        XCTAssertFalse(matches("onions", "onion", "de")) // English words aren't German ones
    }

    // a count's words are worded for the count by the pair
    func testACountsWordsAreWordedForTheCountByThePair() {
        XCTAssertEqual(IngredientName.counted("onion", count: 3, words: .english), "onions")
        XCTAssertEqual(IngredientName.counted("onions", count: 1, words: .english), "onion")
        XCTAssertEqual(IngredientName.counted("onion", count: 0.5, words: .english), "onion")
        XCTAssertEqual(IngredientName.counted("large egg, beaten", count: 2, words: .english), "large eggs, beaten")
        XCTAssertEqual(IngredientName.counted("Onion", count: 2, words: .english), "Onions")
        XCTAssertEqual(IngredientName.counted("glass", count: 2, words: .english), "glass")
        XCTAssertEqual(IngredientName.counted("Zwiebel", count: 3, words: LanguageWords.forTag("de")!), "Zwiebeln")
        XCTAssertEqual(IngredientName.counted("oignon rouge", count: 2, words: LanguageWords.forTag("fr")!), "oignons rouges")
    }

    // render scales, then converts with the line's own separator
    func testRenderScalesThenConvertsWithTheLinesOwnSeparator() {
        XCTAssertEqual(
            IngredientRendering.render(["1 cup flour", "1,25 kg potatoes"], factor: 2, system: .metric, convertLiquids: false),
            ["240 g flour", "2,5 kg potatoes"]
        )
        XCTAssertEqual(
            IngredientRendering.render(["1 cup flour"], factor: 1, system: .asWritten, convertLiquids: false), ["1 cup flour"]
        )
    }
}
