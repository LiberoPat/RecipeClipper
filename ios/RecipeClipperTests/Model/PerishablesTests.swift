import XCTest
@testable import RecipeClipper

/// Android's `PerishablesTest` (#396): the perishables table, each group, the skips, the languages.
final class PerishablesTests: XCTestCase {
    private let en = LanguageWords.english

    private func group(_ line: String, _ words: LanguageWords? = .english) -> String? {
        Perishables.find(line, words: words)?.group.key
    }

    func testEachGroupMatchesItsIngredientsByTheEndOfTheName() {
        XCTAssertEqual(group("2 salmon fillets (about 6 oz each), skin on"), "fish")
        XCTAssertEqual(group("1 lb large shrimp, peeled and deveined"), "fish")
        XCTAssertEqual(group("4 cod fillets"), "fish")
        XCTAssertEqual(group("12 mussels, scrubbed"), "fish")
        XCTAssertEqual(group("1 lb ground beef"), "mince")
        XCTAssertEqual(group("500 g pork mince"), "mince")
        XCTAssertEqual(group("1 lb Italian sausage"), "mince")
        XCTAssertEqual(group("2 boneless skinless chicken breasts"), "poultry")
        XCTAssertEqual(group("6 chicken thighs"), "poultry")
        XCTAssertEqual(group("1 whole chicken (about 4 lb)"), "poultry")
        XCTAssertEqual(group("2 ribeye steaks"), "meatCuts")
        XCTAssertEqual(group("1 lb pork tenderloin"), "meatCuts")
        XCTAssertEqual(group("8 oz button mushrooms, sliced"), "mushrooms")
        XCTAssertEqual(group("200 g shiitake"), "mushrooms")
        XCTAssertEqual(group("2 ripe avocados"), "avocado")
        XCTAssertEqual(group("1/2 cup fresh basil leaves"), "herbs")
        XCTAssertEqual(group("1 bunch cilantro, chopped"), "herbs")
        XCTAssertEqual(group("2 tbsp chopped fresh parsley"), "herbs")
        XCTAssertEqual(group("5 oz baby spinach"), "salad")
        XCTAssertEqual(group("1 head romaine lettuce"), "salad")
        XCTAssertEqual(group("2 handfuls rocket"), "salad")
        XCTAssertEqual(group("1 pint strawberries, hulled"), "berries")
        XCTAssertEqual(group("1 cup blueberries"), "berries")
        XCTAssertEqual(group("1 cup raspberries"), "softBerries")
        XCTAssertEqual(group("2 cups mixed berries"), "softBerries")
    }

    func testAGroupsKeepsHintIsItsRange() {
        XCTAssertEqual(Perishables.find("2 salmon fillets", words: en)?.group.keeps, "1-2")
        XCTAssertEqual(Perishables.find("1 bunch basil", words: en)?.group.keeps, "3-5")
        XCTAssertEqual(Perishables.find("8 oz mushrooms", words: en)?.group.keeps, "4-6")
        XCTAssertEqual(Perishables.find("2 avocados", words: en)?.group.keeps, "2-3")
        XCTAssertEqual(PerishableGroup(key: "x", minDays: 3, maxDays: 3).keeps, "3")
    }

    func testTheNameIsTheIngredientsOwn() {
        XCTAssertEqual(Perishables.find("2 salmon fillets (about 6 oz each), skin on", words: en)?.name, "salmon fillets")
        XCTAssertEqual(Perishables.find("8 oz button mushrooms, sliced", words: en)?.name, "button mushrooms")
    }

    func testASkipEntryWinsByBeingLongerAndANameThatOnlyEndsInAnAliasIsNotMatched() {
        for line in [
            "4 oz smoked salmon", "1 can (6 oz) canned salmon", "2 tsp dried basil", "1 tbsp dried parsley",
            "1 cup frozen strawberries", "2 cups frozen blueberries", "1 cup frozen mixed berries",
            "1 oz dried porcini mushrooms", "1 cup dried shiitake", "10 oz frozen chopped spinach", "1 lb frozen shrimp",
            "2 cups shredded chicken", "1 rotisserie chicken", "2 cups cooked chicken", "1 cup cauliflower steak",
            "1/4 cup goji berries", "1/4 cup juniper berries",
            "2 tbsp fish sauce", "1 cup fish stock", "1 cup mushroom stock", "1 cup chicken stock",
            "1/2 cup strawberry jam", "2 tsp dried herbs", "1 can cream of mushroom soup", "1 tsp basil pesto",
            "1/2 cup avocado oil", "1 cup cod liver oil", "1 can tuna",
        ] {
            XCTAssertNil(group(line), line)
        }
        XCTAssertEqual(group("2 tuna steaks"), "fish")
    }

    func testALineTheAppCannotNameOrAnUnlistedIngredientIsNotPerishable() {
        for line in ["Salmon and potatoes:", "salt and pepper", "juice of 1 lemon", "2 cups flour", "3 carrots", ""] {
            XCTAssertNil(group(line), line)
        }
        XCTAssertNil(Perishables.groupOf("", words: en))
    }

    func testAListedSingularAndPluralAreOneWord() {
        XCTAssertEqual(Perishables.groupOf("raspberries", words: en)?.key, "softBerries")
        XCTAssertEqual(Perishables.groupOf("raspberry", words: en)?.key, "softBerries")
        XCTAssertEqual(Perishables.groupOf("mussels", words: en)?.key, "fish")
        XCTAssertNil(Perishables.groupOf("frozen raspberries", words: en))
        XCTAssertNil(Perishables.groupOf("frozen raspberry", words: en))
    }

    func testTheMostDelicatePerishableOfARecipeIsTheShortestKeeping() {
        let worst = Perishables.mostDelicate(["1 bunch basil", "2 salmon fillets", "8 oz mushrooms", "2 cups flour"], words: en)
        XCTAssertEqual(worst?.group.key, "fish")
        XCTAssertEqual(worst?.name, "salmon fillets")
        XCTAssertEqual(Perishables.mostDelicate(["2 cups flour", "1 bunch basil", "1 cup blueberries"], words: en)?.group.key, "herbs")
        XCTAssertNil(Perishables.mostDelicate(["2 cups flour", "1 tsp dried basil"], words: en))
        XCTAssertNil(Perishables.mostDelicate([], words: en))
    }

    func testTheRecipesLanguagePicksTheTableAndALanguageWithNoneMatchesNothing() {
        let de = LanguageWords.forTag("de")
        XCTAssertEqual(group("2 Lachsfilets", de), "fish")
        XCTAssertEqual(group("200 g Lachs", de), "fish")
        XCTAssertNil(group("100 g geräucherter Lachs", de))
        XCTAssertEqual(group("500 g Hackfleisch", de), "mince")
        XCTAssertEqual(group("1 Bund Petersilie", de), "herbs")
        XCTAssertNil(group("1 TL getrocknetes Basilikum", de))
        XCTAssertNil(group("200 g tiefgekühlte Erdbeeren", de))
        XCTAssertNil(group("2 salmon fillets", de))
        XCTAssertNil(group("200 g Lachs", en))

        let fr = LanguageWords.forTag("fr")
        XCTAssertEqual(group("2 filets de saumon", fr), "fish")
        XCTAssertNil(group("2 tranches de saumon fumé", fr))
        XCTAssertNil(group("1 l de bouillon de poulet", fr))
        XCTAssertNil(group("1 pot de confiture de fraises", fr))
        XCTAssertEqual(group("250 g de fraises", fr), "berries")

        let es = LanguageWords.forTag("es")
        XCTAssertEqual(group("2 pechugas de pollo", es), "poultry")
        XCTAssertNil(group("1 l de caldo de pollo", es))
        XCTAssertEqual(group("200 g de champiñones", es), "mushrooms")
        XCTAssertNil(group("1 lata de crema de champiñones", es))

        let it = LanguageWords.forTag("it")
        XCTAssertEqual(group("1 mazzetto di basilico", it), "herbs")
        XCTAssertNil(group("2 cucchiai di pesto di basilico", it))

        let pt = LanguageWords.forTag("pt")
        XCTAssertEqual(group("2 filés de salmão", pt), "fish")
        XCTAssertNil(group("1 xícara de caldo de frango", pt))

        let ja = LanguageWords.forTag("ja")
        XCTAssertEqual(group("鮭 2切れ", ja), "fish")
        XCTAssertNil(group("塩鮭 2切れ", ja))
        XCTAssertEqual(group("豚ひき肉 200g", ja), "mince")

        XCTAssertNil(Perishables.find("2 salmon fillets", words: nil))
        XCTAssertNil(Perishables.groupOf("salmon", words: nil))
    }

    func testEveryShippedLanguageHasTheTableWithTheSameGroupsAndDaysAsEnglish() {
        let english = Dictionary(uniqueKeysWithValues: Perishables.groups(en).map { ($0.key, $0) })
        for language in LanguageWords.shipped {
            let words = LanguageWords.forTag(language)!
            let groups = Perishables.groups(words)
            XCTAssertFalse(groups.isEmpty, language)
            for group in groups {
                XCTAssertEqual(english[group.key], group, "\(language) \(group.key) days")
            }
            for key in ["fish", "mince", "poultry", "mushrooms", "avocado", "herbs", "salad", "berries", "softBerries"] {
                XCTAssertTrue(groups.contains { $0.key == key }, "\(language) lists \(key)")
            }
        }
    }

    func testLaterIsAtOrPastTheLowEndOfEveryGroupsRange() {
        XCTAssertEqual(Perishables.laterDays, 4)
        for language in LanguageWords.shipped {
            for group in Perishables.groups(LanguageWords.forTag(language)!) {
                XCTAssertLessThanOrEqual(group.minDays, Perishables.laterDays, "\(language) \(group.key)")
                XCTAssertTrue((1...group.maxDays).contains(group.minDays), "\(language) \(group.key) is a range")
            }
        }
    }
}
