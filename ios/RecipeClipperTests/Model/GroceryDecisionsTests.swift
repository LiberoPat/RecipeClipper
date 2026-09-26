import XCTest
@testable import RecipeClipper

/// Android's GroceryDecisionsTest (#99): given each decision, what the grocery list shows.
final class GroceryDecisionsTests: XCTestCase {

    private func items(_ lines: (String, Aisle)...) -> [GroceryItem] {
        lines.enumerated().map { i, line in
            GroceryItem(id: Int64(i + 1), text: line.0, language: "en", aisle: line.1, checked: false, sortOrder: i)
        }
    }

    private func same(_ a: String, _ b: String) -> DecisionQuestion { .sameGrocery(a, b, language: "en") }
    private func trailing(_ t: String) -> DecisionQuestion { .trailingText(t, language: "en") }

    private func rows(_ items: [GroceryItem], _ answers: [DecisionQuestion: String]) -> [GroceryCombiner.Row] {
        GroceryCombiner.sections(items, decisions: Decisions(answers: answers)).flatMap(\.rows)
    }

    private func combinedText(_ row: GroceryCombiner.Row?) -> String? {
        if case .combined(_, let text, _) = row { return text }
        return nil
    }

    func testWithoutAnswersTheListIsAsToday() {
        let list = items(("2 ears of corn", .other), ("2 corn", .other))
        XCTAssertEqual(GroceryCombiner.sections(list), GroceryCombiner.sections(list, decisions: .none))
        XCTAssertEqual(rows(list, [:]).count, 2)
    }

    func testSameGroupsButCountsWithDifferentWordsStayAsWritten() {
        let list = items(("2 ears of corn", .other), ("2 corn", .other))
        let all = rows(list, [same("ears of corn", "corn"): "same"])
        XCTAssertEqual(all.count, 1)
        guard case .together(let name, _) = all[0] else { return XCTFail("not together") }
        XCTAssertEqual(name, "ears of corn")
        XCTAssertEqual(rows(list, [same("ears of corn", "corn"): "different"]).count, 2)
        XCTAssertEqual(rows(list, [same("ears of corn", "corn"): "unsure"]).count, 2)
    }

    func testSameAddsUpOnlyWhatTheExactRulesAllow() {
        let list = items(("200 g sweetcorn", .other), ("100 g corn", .other))
        XCTAssertEqual(combinedText(rows(list, [same("sweetcorn", "corn"): "same"]).first), "300 g corn")
    }

    func testANoteOrJunkNoLongerBlocksAddingUpButASecondAmountDoes() {
        let list = items(("2 eggs, beaten", .dairy), ("3 eggs", .dairy))
        XCTAssertNil(combinedText(rows(list, [:]).first))
        XCTAssertEqual(combinedText(rows(list, [trailing(", beaten"): "note"]).first), "5 eggs")
        XCTAssertEqual(combinedText(rows(list, [trailing(", beaten"): "junk"]).first), "5 eggs")
        XCTAssertNil(combinedText(rows(list, [trailing(", beaten"): "second_amount"]).first))
        XCTAssertNil(combinedText(rows(list, [trailing(", beaten"): "unsure"]).first))
    }

    func testWhatIsAsked() {
        let list = items(("2 ears of corn", .other), ("2 corn", .other), ("1 cup rice flour", .baking))
        XCTAssertEqual(GroceryDecisions.samePairs(list, decisions: .none), [same("ears of corn", "corn")])
        let apart = items(("3 garlic cloves", .spices), ("2 cloves garlic", .produce))
        XCTAssertTrue(GroceryDecisions.samePairs(apart, decisions: .none).isEmpty)
        let eggs = items(
            ("2 eggs, beaten", .dairy), ("3 eggs", .dairy), ("200 g butter, soft", .dairy),
            ("1 cup milk, soft", .dairy), ("2 eggs (about 100 g)", .dairy)
        )
        // Every line with trailing text, once per text; never text holding a figure.
        XCTAssertEqual(GroceryDecisions.trailingTexts(eggs, decisions: .none), [trailing(", beaten"), trailing(", soft")])
    }

    func testAFreshAnswerFilesALineOutOfOtherBesideItsPartner() {
        let list = items(("3 garlic cloves", .other), ("2 cloves garlic", .produce), ("2 eggs (dfsafs -", .other))
        let q1 = same("garlic cloves", "garlic"), q2 = trailing("(dfsafs -")
        let d = Decisions(answers: [q1: "same", q2: "junk"])
        XCTAssertEqual(GroceryDecisions.filing(list, fresh: [q1, q2], decisions: d), [.produce: [1], .dairy: [3]])
        XCTAssertTrue(GroceryDecisions.filing(list, fresh: [], decisions: d).isEmpty)
    }

    // MARK: Junk with no separator: the model names the ingredient, the rest is trailing text

    private func name(_ line: String) -> DecisionQuestion { .ingredientName(line, language: "en") }
    private let en = LanguageWords.forTag("en")!

    func testAsksForTheNameOnlyWhereExtraWordsFollowAKnownOne() {
        XCTAssertEqual(GroceryDecisions.nameQuestion("2 onions dfsafs", words: en), name("2 onions dfsafs"))
        XCTAssertNil(GroceryDecisions.nameQuestion("2 onions", words: en))
        XCTAssertNil(GroceryDecisions.nameQuestion("2 onions, dfsafs", words: en))
        XCTAssertNil(GroceryDecisions.nameQuestion("1 cup rice flour", words: en))
        XCTAssertNil(GroceryDecisions.nameQuestion("2 玉ねぎ dfsafs", words: LanguageWords.forTag("ja")!))
    }

    func testAcceptsANameOnlyVerbatimOnWordBoundaries() {
        XCTAssertEqual(GroceryDecisions.nameSplit("2 onions dfsafs", name: "Onions", words: en), .init(core: "2 onions", trailing: "dfsafs"))
        XCTAssertNil(GroceryDecisions.nameSplit("2 onions dfsafs", name: "onion", words: en))
        XCTAssertNil(GroceryDecisions.nameSplit("2 onions dfsafs", name: "shallots", words: en))
        XCTAssertNil(GroceryDecisions.nameSplit("2 onions dfsafs", name: "2 onions", words: en))
        XCTAssertNil(GroceryDecisions.nameSplit("2 onions dfsafs", name: "onions dfsafs", words: en))
        XCTAssertNil(GroceryDecisions.nameSplit("2 onions dfs 3", name: "onions", words: en))
    }

    func testJunkAfterANamedIngredientIsHiddenInGroceries() {
        let list = items(("2 onions dfsafs", .produce), ("3 onions", .produce))
        let d: [DecisionQuestion: String] = [name("2 onions dfsafs"): "onions", trailing("dfsafs"): "junk"]
        XCTAssertEqual(combinedText(rows(list, d).first), "5 onions")
        XCTAssertEqual(GroceryCombiner.lines(rows(list, d)[0]), ["2 onions", "3 onions"])
        guard case .single(let alone) = rows(items(("2 onions dfsafs", .produce)), d).first else { return XCTFail("not single") }
        XCTAssertEqual(alone.text, "2 onions")
        // Unsure, or only a name: the line stays exactly as today.
        let unsure: [DecisionQuestion: String] = [name("2 onions dfsafs"): "onions", trailing("dfsafs"): "unsure"]
        XCTAssertEqual(GroceryCombiner.sections(list, decisions: Decisions(answers: unsure)), GroceryCombiner.sections(list))
        let noName = Decisions(answers: [name("2 onions dfsafs"): "unsure"])
        XCTAssertEqual(GroceryCombiner.sections(list, decisions: noName), GroceryCombiner.sections(list))
    }

    func testANoteStillShowsAndJunkAfterASeparatorIsHiddenToo() {
        let list = items(("2 eggs, beaten", .dairy), ("3 eggs (dfsafs -", .dairy))
        let d: [DecisionQuestion: String] = [trailing(", beaten"): "note", trailing("(dfsafs -"): "junk"]
        XCTAssertEqual(GroceryCombiner.lines(rows(list, d)[0]), ["2 eggs, beaten", "3 eggs"])
        let one = GroceryCombiner.sections(items(("3 eggs (dfsafs -", .dairy)), decisions: Decisions(answers: d))
        XCTAssertEqual(GroceryShareText.format(one, title: "List", aisleName: \.rawValue), "List\n\ndairy\n- 3 eggs")
    }

    func testTheRestOfANamedLineIsAskedAboutOnceTheNameLands() {
        let list = items(("2 onions dfsafs", .produce))
        XCTAssertTrue(GroceryDecisions.trailingTexts(list, decisions: .none).isEmpty)
        XCTAssertEqual(GroceryDecisions.ingredientNames(list), [name("2 onions dfsafs")])
        let named = Decisions(answers: [name("2 onions dfsafs"): "onions"])
        XCTAssertEqual(GroceryDecisions.trailingTexts(list, decisions: named), [trailing("dfsafs")])
    }
}
