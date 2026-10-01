import Foundation

/// The visit's order (#219): `GroceryCombiner.sections` laid out as if each item were ticked as in
/// `ticks` (an item not in it, as it is), then shown with every item's tick as it is now. A row
/// ticks all its lines, so its lines share one tick and it stays one row.
enum GroceriesOrder {
    static func sections(_ items: [GroceryItem], decisions: Decisions, ticks: [Int64: Bool]) -> [GroceryCombiner.Section] {
        let now = Dictionary(items.map { ($0.id, $0.checked) }, uniquingKeysWith: { a, _ in a })
        func with(_ item: GroceryItem, _ tick: Bool?) -> GroceryItem {
            guard let tick, tick != item.checked else { return item }
            var copy = item
            copy.checked = tick
            return copy
        }
        let asWas = items.map { with($0, ticks[$0.id]) }
        return GroceryCombiner.sections(asWas, decisions: decisions).map { section in
            GroceryCombiner.Section(aisle: section.aisle, rows: section.rows.map { row in
                switch row {
                case .single(let item): return .single(with(item, now[item.id]))
                case .combined(let name, let text, let items): return .combined(name: name, text: text, items: items.map { with($0, now[$0.id]) })
                case .together(let name, let items): return .together(name: name, items: items.map { with($0, now[$0.id]) })
                }
            })
        }
    }
}

/// **Ticks stay put** (#219, #234; Android's `VisitOrder`): within a visit, ticking or unticking
/// never re-sorts the list. The order is worked out with each item's tick as it was when the order
/// was last worked out, and shown with its tick now (`GroceriesOrder`). It's worked out afresh when
/// anything but a tick changes (an item added, removed, cleared, or moved to another aisle, here or
/// elsewhere) and after `reset`, when the screen is left, so ticked rows sink to the bottom of
/// their aisle next time.
struct VisitOrder {
    // Each item's tick when the order was last worked out, and the list then with its ticks left
    // out; nil until worked out, and after the screen is left.
    private var ticks: [Int64: Bool]?
    private var shape: [GroceryItem]?

    /// `items` laid out in the visit's order, working it out afresh when more than a tick changed.
    mutating func sections(_ items: [GroceryItem], decisions: Decisions) -> [GroceryCombiner.Section] {
        let shape = items.map { item -> GroceryItem in
            var unticked = item
            unticked.checked = false
            return unticked
        }
        let ticks: [Int64: Bool]
        if let frozen = self.ticks, shape == self.shape {
            ticks = frozen
        } else {
            ticks = Dictionary(items.map { ($0.id, $0.checked) }, uniquingKeysWith: { a, _ in a })
            self.ticks = ticks
            self.shape = shape
        }
        return GroceriesOrder.sections(items, decisions: decisions, ticks: ticks)
    }

    /// The screen was left: the next layout works the order out afresh.
    mutating func reset() { ticks = nil }
}
