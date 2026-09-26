import XCTest
@testable import RecipeClipper

/// The typed decisions (#104), Android's DecisionRuleTest, CountBracketDecisionTest and
/// DecisionCandidatesTest: the rule, and what each answer changes.
final class DecisionsTests: XCTestCase {
    private let de = LanguageWords.forTag("de")!
    private let apples = "3 large apples, peeled and sliced (about 3 cups)"

    private func reply(_ answer: String, _ confidence: String = "high") -> DecisionReply {
        DecisionReply(answer: answer, confidence: confidence)
    }

    private func item(_ name: String, inStock: Bool = true) -> PantryItem {
        PantryItem(id: Int64(name.count), name: name, quantity: nil, language: "en", aisle: .other, inStock: inStock,
                   alwaysHave: false, purchasedDay: nil, expiresDay: nil)
    }

    func testTheRuleNeedsTheSameDefiniteAnswerTwiceWithHighConfidence() {
        XCTAssertEqual(DecisionRule.judge(.countBracket, [reply("total"), reply(" Total ")]), "total")
        XCTAssertEqual(DecisionRule.judge(.sameIngredient, [reply("same"), reply("different")]), "unsure")
        XCTAssertEqual(DecisionRule.judge(.sameIngredient, [reply("same"), reply("same", "medium")]), "unsure")
        XCTAssertEqual(DecisionRule.judge(.sameIngredient, [reply("unsure"), reply("unsure")]), "unsure")
        XCTAssertEqual(DecisionRule.judge(.aisle, [reply("pharmacy"), reply("pharmacy")]), "unsure")
        XCTAssertEqual(DecisionRule.judge(.aisle, [reply("dairy")]), "unsure")
        XCTAssertEqual(DecisionRule.options(.countBracket, 1), ["each", "total", "unsure"])
    }

    func testTheCountBracketAnswerDrivesTheScaler() {
        XCTAssertEqual(IngredientScaler.scale("4 Apfel (ca. 800g)", factor: 2, words: de), "4 Apfel (ca. 800g)")
        XCTAssertEqual(IngredientScaler.scale("4 Apfel (ca. 800g)", factor: 2, words: de, bracket: .total), "8 Apfel (ca. 1600g)")
        XCTAssertEqual(IngredientScaler.scale("4 Apfel (ca. 800g)", factor: 2, words: de, bracket: .each), "8 Apfel (ca. 800g)")
        let decided = Decisions(answers: [.countBracket(apples, language: "en"): "total"])
        XCTAssertEqual(
            IngredientRendering.render([apples], factor: 2, system: .asWritten, convertLiquids: false, decisions: decided),
            ["6 large apples, peeled and sliced (about 6 cups)"]
        )
        let unsure = Decisions(answers: [.countBracket(apples, language: "en"): "unsure"])
        XCTAssertEqual(IngredientRendering.render([apples], factor: 2, system: .asWritten, convertLiquids: false, decisions: unsure), [apples])
    }

    func testOnlyADefiniteSameTurnsBuyIntoHave() {
        let pantry = [item("flour"), item("milk"), item("caster sugar")]
        let same = Decisions(answers: [.sameIngredient("superfine sugar", "caster sugar", language: "en"): "same"])
        XCTAssertFalse(PantryMatch.covered("1 cup superfine sugar", language: "en", pantry: pantry))
        XCTAssertTrue(PantryMatch.covered("1 cup superfine sugar", language: "en", pantry: pantry, decisions: same))
        let owner = Decisions(answers: [
            .sameIngredient("rice flour", "flour", language: "en"): "different",
            .sameIngredient("whole milk", "milk", language: "en"): "unsure",
        ])
        XCTAssertFalse(PantryMatch.covered("1 cup rice flour", language: "en", pantry: pantry, decisions: owner))
        XCTAssertFalse(PantryMatch.covered("1 cup whole milk", language: "en", pantry: pantry, decisions: owner))
        XCTAssertFalse(PantryMatch.covered("1 cup superfine sugar", language: "en", pantry: [item("caster sugar", inStock: false)], decisions: same))
    }

    func testQuestionsAreAskedOnlyWhereTheRulesGiveUp() {
        let pantry = [item("flour"), item("milk", inStock: false), item("butter")]
        XCTAssertEqual(
            DecisionCandidates.samePairs(["rice flour", "whole milk", "unsalted butter"], language: "en", pantry: pantry),
            [.sameIngredient("rice flour", "flour", language: "en")]
        )
        XCTAssertNil(DecisionCandidates.aisle("2 cups whole milk", language: "en"))
        let furikake = DecisionCandidates.aisle("2 tbsp furikake", language: "en")
        XCTAssertEqual(furikake, .aisle("furikake", language: "en"))
        XCTAssertEqual(Decisions(answers: [furikake!: "spices"]).aisle("Furikake", language: "en"), .spices)
        XCTAssertNil(Decisions(answers: [furikake!: "other"]).aisle("furikake", language: "en"))
        let prompt = DecisionPrompts.prompt(.sameIngredient("Flour", "rice  flour", language: "en"), 0)
        XCTAssertTrue(prompt.instructions.contains("\"rice flour\" is not \"flour\""))
        XCTAssertTrue(prompt.instructions.contains("\"whole milk\" is not \"milk\""))
    }
}
