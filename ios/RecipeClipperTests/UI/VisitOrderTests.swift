import XCTest
@testable import RecipeClipper

/// `VisitOrder` on its own (#219, #234; Android's VisitOrderTest): ticks stay put within a visit.
final class VisitOrderTests: XCTestCase {

    private func item(_ id: Int64, _ text: String, checked: Bool = false) -> GroceryItem {
        GroceryItem(id: id, text: text, language: "en", aisle: .produce, checked: checked, sortOrder: Int(id))
    }

    private func texts(_ order: inout VisitOrder, _ items: [GroceryItem]) -> [String] {
        order.sections(items, decisions: .none).flatMap { $0.rows.flatMap { $0.items.map { "\($0.text) \($0.checked)" } } }
    }

    private lazy var onions = item(1, "2 onions")
    private lazy var carrots = item(2, "3 carrots")

    private func ticked(_ item: GroceryItem) -> GroceryItem {
        var copy = item
        copy.checked = true
        return copy
    }

    func testATickShowsAtOnceButKeepsItsRowWhereItWas() {
        var order = VisitOrder()
        _ = texts(&order, [onions, carrots])

        XCTAssertEqual(texts(&order, [ticked(onions), carrots]), ["2 onions true", "3 carrots false"])
    }

    func testAfterTheScreenIsLeftTickedRowsSinkToTheBottomOfTheirAisle() {
        var order = VisitOrder()
        _ = texts(&order, [onions, carrots])
        _ = texts(&order, [ticked(onions), carrots])

        order.reset()

        XCTAssertEqual(texts(&order, [ticked(onions), carrots]), ["3 carrots false", "2 onions true"])
    }

    func testAnythingButATickWorksTheOrderOutAfresh() {
        var order = VisitOrder()
        _ = texts(&order, [onions, carrots])
        _ = texts(&order, [ticked(onions), carrots])

        let added = texts(&order, [ticked(onions), carrots, item(3, "1 leek")])

        XCTAssertEqual(added, ["3 carrots false", "1 leek false", "2 onions true"])
    }
}
