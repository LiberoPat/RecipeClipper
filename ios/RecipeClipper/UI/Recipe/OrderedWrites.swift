import Foundation

/// The recipe screen's one write queue (#10, #234; Android's `OrderedWrites`): cook progress and
/// the chosen servings, each waiting for the one before, so two quick taps can never land out of
/// order and leave the older state saved. A write holds what it writes to (the repository), not
/// the ViewModel, so one queued just before the screen is popped still lands.
@MainActor
final class OrderedWrites {
    // The last queued write.
    private var last: Task<Void, Never>?

    /// Queues `write` behind every write made before it.
    func enqueue(_ write: @escaping () async -> Void) {
        let previous = last
        last = Task {
            await previous?.value
            await write()
        }
    }

    /// Waits for every queued write. For tests.
    func settle() async { await last?.value }
}
