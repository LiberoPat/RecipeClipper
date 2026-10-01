import Foundation

/// The reading view's own writes (#10, #234; Android's `NotesAndTicks`): ingredient ticks, written
/// as they change so closing the app mid-cook doesn't lose them, and the user's note, written once
/// typing pauses for `saveDelay` (a sentence is one write rather than one per letter) or when the
/// screen is left: a note still waiting when this goes (with its ViewModel) is written then.
@MainActor
final class NotesAndTicks {
    private let repository: RecipeRepository
    private let sleep: Sleep
    private let saveDelay: Duration
    // The note as typed but not yet written (with its recipe), and the debounced write.
    private var pendingNotes: (id: Int64, text: String)?
    private var notesTask: Task<Void, Never>?

    init(repository: RecipeRepository, sleep: @escaping Sleep, saveDelay: Duration) {
        self.repository = repository
        self.sleep = sleep
        self.saveDelay = saveDelay
    }

    deinit {
        notesTask?.cancel()
        // A note typed just before leaving is still written: one short write, owned by no one.
        if let pending = pendingNotes {
            Task { [repository] in await repository.setNotes(id: pending.id, notes: pending.text) }
        }
    }

    /// Recipe `id`'s ticked ingredient lines are now `checked`.
    func ticked(id: Int64, checked: Set<Int>) {
        Task { [repository] in await repository.setChecked(id: id, checked: checked) }
    }

    /// Recipe `id`'s note is now `text`: written once typing has paused.
    func noteChanged(id: Int64, text: String) {
        pendingNotes = (id, text)
        notesTask?.cancel()
        // Weak across the sleep, so a popped screen still lets this (and its deinit) go.
        notesTask = Task { [weak self, sleep, saveDelay] in
            do { try await sleep(saveDelay) } catch { return }
            await self?.flushNotes()
        }
    }

    private func flushNotes() async {
        guard let pending = pendingNotes else { return }
        pendingNotes = nil
        await repository.setNotes(id: pending.id, notes: pending.text)
    }
}
