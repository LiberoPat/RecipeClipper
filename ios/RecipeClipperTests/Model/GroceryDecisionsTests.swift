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
        let eggs = items(("2 eggs, beaten", .dairy), ("3 eggs", .dairy), ("200 g butter, soft", .dairy))
        XCTAssertEqual(GroceryDecisions.trailingTexts(eggs, decisions: .none), [trailing(", beaten")])
    }

    func testAFreshAnswerFilesALineOutOfOtherBesideItsPartner() {
        let list = items(("3 garlic cloves", .other), ("2 cloves garlic", .produce), ("2 eggs (dfsafs -", .other))
        let q1 = same("garlic cloves", "garlic"), q2 = trailing("(dfsafs -")
        let d = Decisions(answers: [q1: "same", q2: "junk"])
        XCTAssertEqual(GroceryDecisions.filing(list, fresh: [q1, q2], decisions: d), [.produce: [1], .dairy: [3]])
        XCTAssertTrue(GroceryDecisions.filing(list, fresh: [], decisions: d).isEmpty)
    }
}
