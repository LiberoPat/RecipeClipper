import Foundation

/// Removing from the grocery list, with undo (#146, #219, #234; Android's `GroceryRemovals`). One
/// undo at a time: a second removal settles the first. Each removal gives what the snackbar says
/// (`RemovedGroceries`); its Undo puts back the list's items and, after "Done shopping", the
/// pantry as it was.
@MainActor
final class GroceryRemovals {
    /// What the snackbar's Undo puts back: the list's items and, after "Done shopping", the pantry.
    struct Undo {
        let groceries: DeletedGroceries?
        var restocked: PantrySnapshot?
        var added: [Int64] = []
    }

    private let repository: GroceryRepository
    private let pantry: PantryRepository
    private var undo: Undo?
    private var removals = 0

    init(repository: GroceryRepository, pantry: PantryRepository) {
        self.repository = repository
        self.pantry = pantry
    }

    /// `deleted` left the list: one row's `label`, the checked items (`label` nil), or the whole
    /// list (`all`).
    func removed(_ deleted: DeletedGroceries, _ label: String?, all: Bool = false) -> RemovedGroceries {
        undo = Undo(groceries: deleted)
        removals += 1
        return RemovedGroceries(id: removals, label: label, all: all)
    }

    /// Restocks or adds `items`, bought on `today`, then clears every ticked line: one undo for it
    /// all. Nil when nothing changed, which leaves the last undo as it was.
    func putAway(_ items: [PutAwayItem], today: Int64) async -> RemovedGroceries? {
        let restock = items.compactMap(\.trackedId)
        var restocked: PantrySnapshot?
        if !restock.isEmpty {
            restocked = await pantry.snapshot(restock)
            await pantry.restock(restock, day: today)
        }
        var added: [Int64] = []
        for item in items where item.trackedId == nil {
            let new = NewPantryItem(name: item.name, language: item.language, aisle: item.aisle, purchasedDay: today)
            if let id = await pantry.add(new) { added.append(id) }
        }
        let cleared = await repository.clearChecked()
        if cleared == nil && restocked == nil && added.isEmpty { return nil }
        undo = Undo(groceries: cleared, restocked: restocked, added: added)
        removals += 1
        return RemovedGroceries(id: removals, label: nil, putAway: !items.isEmpty)
    }

    /// The snackbar's Undo: the last removal's undo, taken, for `restore`; nil when there's none.
    func takeUndo() -> Undo? {
        let last = undo
        undo = nil
        return last
    }

    /// Puts back what `last` took: the items and, after "Done shopping", the pantry as it was.
    func restore(_ last: Undo) async {
        if let groceries = last.groceries { await repository.restore(groceries) }
        if let restocked = last.restocked { await pantry.restore(restocked) }
        for id in last.added { _ = await pantry.delete(id) }
    }

    /// The snackbar timed out: the removal stands.
    func settle() { undo = nil }
}
