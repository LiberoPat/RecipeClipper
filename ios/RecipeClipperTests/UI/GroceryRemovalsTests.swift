import XCTest
@testable import RecipeClipper

/// `GroceryRemovals` on its own (#146, #219, #234; Android's GroceryRemovalsTest): removals and
/// their one undo.
@MainActor
final class GroceryRemovalsTests: XCTestCase {
    private let groceries = FakeGroceryRepository()
    private let butter = PantryItem(
        id: 1, name: "Butter", quantity: nil, language: "en", aisle: .dairy, inStock: false, alwaysHave: false,
        purchasedDay: 1, expiresDay: nil
    )
    private lazy var pantry = FakePantryRepository([butter])
    private lazy var removals = GroceryRemovals(repository: groceries, pantry: pantry)

    func testEachRemovalIsNumberedAndASecondOneSettlesTheFirst() async throws {
        await groceries.add([NewGroceryLine(text: "2 onions", language: "en"), NewGroceryLine(text: "1 leek", language: "en")])
        let onions = groceries.items.value[0].id
        let leek = groceries.items.value[1].id

        let deletedOnions = await groceries.delete([onions])
        let first = removals.removed(try XCTUnwrap(deletedOnions), "2 onions")
        let deletedLeek = await groceries.delete([leek])
        let second = removals.removed(try XCTUnwrap(deletedLeek), nil, all: true)

        XCTAssertEqual(first, RemovedGroceries(id: 1, label: "2 onions"))
        XCTAssertEqual(second, RemovedGroceries(id: 2, label: nil, all: true))
        let undo = try XCTUnwrap(removals.takeUndo())
        await removals.restore(undo)
        XCTAssertEqual(groceries.items.value.map(\.text), ["1 leek"])
        XCTAssertNil(removals.takeUndo())
    }

    func testDoneShoppingRestocksAddsAndClearsAndOneUndoPutsItAllBack() async throws {
        await groceries.add([NewGroceryLine(text: "250 g butter", language: "en"), NewGroceryLine(text: "2 cups flour", language: "en")])
        await groceries.setChecked(groceries.items.value.map(\.id), checked: true)
        let before = groceries.items.value.map(\.text)
        let items = [
            PutAwayItem(key: "pantry-1", name: "Butter", language: "en", aisle: .dairy, trackedId: 1),
            PutAwayItem(key: "new-en-flour", name: "flour", language: "en", aisle: .baking, trackedId: nil)
        ]

        let removed = await removals.putAway(items, today: 5)

        XCTAssertEqual(removed, RemovedGroceries(id: 1, label: nil, putAway: true))
        XCTAssertTrue(groceries.items.value.isEmpty)
        XCTAssertEqual(pantry.items.value.map(\.name), ["Butter", "flour"])
        XCTAssertEqual(pantry.items.value.map(\.inStock), [true, true])

        let undo = try XCTUnwrap(removals.takeUndo())
        await removals.restore(undo)
        XCTAssertEqual(groceries.items.value.map(\.text), before)
        XCTAssertEqual(pantry.items.value, [butter])
    }

    func testPuttingNothingAwayWithNothingTickedChangesNothingAndKeepsTheLastUndo() async throws {
        await groceries.add([NewGroceryLine(text: "2 onions", language: "en")])
        let deleted = await groceries.delete(groceries.items.value.map(\.id))
        _ = removals.removed(try XCTUnwrap(deleted), "2 onions")

        let removed = await removals.putAway([], today: 5)

        XCTAssertNil(removed)
        XCTAssertNotNil(removals.takeUndo())
    }

    func testARemovalThatStandsHasNothingToUndo() async throws {
        await groceries.add([NewGroceryLine(text: "2 onions", language: "en")])
        let deleted = await groceries.delete(groceries.items.value.map(\.id))
        _ = removals.removed(try XCTUnwrap(deleted), "2 onions")

        removals.settle()

        XCTAssertNil(removals.takeUndo())
        XCTAssertTrue(groceries.items.value.isEmpty)
    }
}
