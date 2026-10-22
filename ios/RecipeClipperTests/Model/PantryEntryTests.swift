import XCTest
@testable import RecipeClipper

/// Android's PantryEntryTest (#302): an amount typed with a pantry item's name splits only when
/// the text starts with one real amount, else it stays whole. The corpus's `Entry` rows pin the
/// rest to the Kotlin.
final class PantryEntryTests: XCTestCase {
    private func split(_ text: String, _ language: String = "en") -> PantryEntry {
        PantryEntry.split(text, words: LanguageWords.forTag(language))
    }

    private func assertSplit(_ text: String, _ quantity: String, _ name: String, _ language: String = "en", line: UInt = #line) {
        XCTAssertEqual(split(text, language), PantryEntry(name: name, quantity: quantity), text, line: line)
    }

    private func assertWhole(_ text: String, _ language: String = "en", line: UInt = #line) {
        XCTAssertEqual(split(text, language), PantryEntry(name: text.kTrimmed, quantity: nil), text, line: line)
    }

    func testACountAUnitAFractionAndAMixedNumberSplit() {
        assertSplit("3 onions", "3", "onions")
        assertSplit("  3 onions ", "3", "onions")
        assertSplit("2 cups rice", "2 cups", "rice")
        assertSplit("1½ lb chicken thighs", "1½ lb", "chicken thighs")
        assertSplit("½ cabbage", "½", "cabbage")
        assertSplit("1/2 cup sugar", "1/2 cup", "sugar")
        assertSplit("1 1/2 cups flour", "1 1/2 cups", "flour")
        assertSplit("500g butter", "500g", "butter")
        assertSplit("2 tbsp. olive oil", "2 tbsp.", "olive oil")
        assertSplit("0.5 kg rice", "0.5 kg", "rice")
    }

    func testTheNameStaysAsTyped() {
        assertSplit("6 large Eggs", "6", "large Eggs")
        assertSplit("3 red onions, small", "3", "red onions, small")
        assertSplit("2 onions (red)", "2", "onions (red)")
    }

    func testAWordJoiningTheUnitToTheNameBelongsToNeither() {
        assertSplit("2 cups of rice", "2 cups", "rice")
        assertSplit("200 g de farine", "200 g", "farine", "fr")
        assertSplit("2 c. à soupe d'huile d'olive", "2 c. à soupe", "huile d'olive", "fr")
        assertSplit("¾ de taza de arroz", "¾ de taza", "arroz", "es")
        assertSplit("200 g di farina", "200 g", "farina", "it")
        assertSplit("2 xícaras de arroz", "2 xícaras", "arroz", "pt")
    }

    func testOtherLanguagesReadTheirOwnUnitsAndDecimals() {
        assertSplit("500 g Mehl", "500 g", "Mehl", "de")
        assertSplit("1,5 kg Kartoffeln", "1,5 kg", "Kartoffeln", "de")
        assertSplit("2 EL Öl", "2 EL", "Öl", "de")
        assertSplit("3 Zwiebeln", "3", "Zwiebeln", "de")
        assertSplit("3 oignons", "3", "oignons", "fr")
    }

    func testWhatIsNotOneAmountStaysWhole() {
        for text in [
            "00 flour", "7up", "5-spice", "five-spice powder", "1/2", "2 kg", "2-3 onions",
            "2 to 3 onions", "2 or 3 onions", "2 eggs plus 3 yolks", "1 cup plus 2 tbsp flour",
            "1 cup butter or 1/2 cup oil", "2 cups rice (400 g)", "1 kg 00 flour", "1 kg flour, 2 kg sugar",
            "2-inch piece ginger", "2% milk", "180 C water", "350°F", "350 °F oven", "1,500 g flour",
            "0 eggs", "onions", "", "   ",
        ] {
            assertWhole(text)
        }
        for text in ["2-3 Zwiebeln", "180 C Wasser", "Zwiebeln 3"] { assertWhole(text, "de") }
        assertWhole("2 à 3 oignons", "fr")
    }

    func testALanguageWithNoTablesOrAmountsAfterTheNameStaysWhole() {
        XCTAssertEqual(PantryEntry.split("3 onions", words: nil), PantryEntry(name: "3 onions", quantity: nil))
        assertWhole("3 玉ねぎ", "ja")
    }
}
