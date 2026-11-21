import Foundation

/// Selecting several recipes (#377): whether the screen is selecting, and the ids ticked.
/// ViewModel state, so it survives the view being rebuilt. `chosen` is nil while not selecting.
struct RecipeSelection: Equatable {
    private(set) var chosen: Set<Int64>?

    var isSelecting: Bool { chosen != nil }

    /// Starts selecting, with `id` ticked when a row's menu began it.
    mutating func start(_ id: Int64? = nil) {
        chosen = id.map { [$0] } ?? []
    }

    mutating func toggle(_ id: Int64) {
        guard var ticked = chosen else { return }
        if ticked.contains(id) { ticked.remove(id) } else { ticked.insert(id) }
        chosen = ticked
    }

    mutating func exit() { chosen = nil }

    /// What an action acts on: the ticked recipes that are listed now. A search that hides a
    /// ticked recipe keeps it ticked, but nothing hidden is deleted or moved.
    func effective(in listed: [RecipeSummary]?) -> Set<Int64> {
        guard let chosen, let listed else { return [] }
        return chosen.intersection(listed.map(\.id))
    }
}
