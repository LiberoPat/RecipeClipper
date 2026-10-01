import XCTest
@testable import RecipeClipper

/// `GroceryQuestions` on its own (#104, #234; Android's GroceryQuestionsTest): the model's
/// questions about the list, each asked once.
@MainActor
final class GroceryQuestionsTests: XCTestCase {
    private let repository = FakeGroceryRepository()
    private let furikake = DecisionQuestion.aisle("furikake", language: "en")

    private func questions(_ decisions: FakeDecisionRepository) -> GroceryQuestions {
        let questions = GroceryQuestions(decisions: decisions, repository: repository)
        questions.latestItems = { [repository] in repository.items.value }
        return questions
    }

    func testAnItemInOtherIsFiledWhereTheModelDecidedAndAskedAboutOnce() async {
        await repository.add([NewGroceryLine(text: "2 tbsp furikake", language: "en")])
        let decisions = FakeDecisionRepository([furikake: "spices"])
        let questions = questions(decisions)

        questions.ask(repository.items.value, .none)
        await settleMain()
        questions.ask(repository.items.value, .none)
        await settleMain()

        XCTAssertEqual(repository.items.value.first?.aisle, .spices)
        XCTAssertEqual(decisions.asked.filter { $0 == furikake }.count, 1)
    }

    func testATickedItemIsNotAskedAbout() async {
        await repository.add([NewGroceryLine(text: "2 tbsp furikake", language: "en")])
        await repository.setChecked(repository.items.value.map(\.id), checked: true)
        let decisions = FakeDecisionRepository([furikake: "spices"])

        questions(decisions).ask(repository.items.value, .none)
        await settleMain()

        XCTAssertTrue(decisions.asked.isEmpty)
        XCTAssertEqual(repository.items.value.first?.aisle, .other)
    }

    func testAnItemWithNoAnswerStaysInOther() async {
        await repository.add([NewGroceryLine(text: "1 jar gochugaru flakes", language: "en")])

        questions(FakeDecisionRepository()).ask(repository.items.value, .none)
        await settleMain()

        XCTAssertEqual(repository.items.value.first?.aisle, .other)
    }
}
