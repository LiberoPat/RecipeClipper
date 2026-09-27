import Combine
import Foundation
import Observation

/// "Your cooks" (#116; Android's CookedPhotosUiState): `photos` newest cook first; `open` the
/// one shown full screen, with `noteDraft` its note as typed; `deleted` the one just deleted,
/// until its Undo snackbar is settled; `addFailed` until the view has said so. `madeThis`: a
/// photo was just added, or the recipe marked as cooked with none (#173), and its full-screen
/// view has closed, so the recipe was cooked; the view offers the pantry's use-up sheet (#147),
/// then calls `onMadeThisHandled`.
struct CookedPhotosUiState: Equatable {
    var photos: [CookedPhoto] = []
    var open: CookedPhoto?
    var noteDraft = ""
    var adding = false
    var deleted: CookedPhoto?
    var addFailed = false
    var madeThis = false
}

/// Android's CookedPhotosViewModel. The recipe id arrives through `setRecipe`, as with the
/// save-to-list ViewModel, because the import route has no id until the parse finishes.
@MainActor
@Observable
final class CookedPhotosViewModel: Identifiable {
    private(set) var uiState = CookedPhotosUiState()

    @ObservationIgnored private let repository: CookedPhotoRepository
    @ObservationIgnored private let sleep: Sleep
    @ObservationIgnored private var recipeId: Int64?
    @ObservationIgnored private var subscription: AnyCancellable?
    @ObservationIgnored private var noteSave: Task<Void, Never>?
    /// A photo was just added: `madeThis` follows once it closes.
    @ObservationIgnored private var madeThisPending = false
    /// The last write, so a test can wait for it.
    @ObservationIgnored private var lastWrite: Task<Void, Never>?

    static let noteDelay = Duration.milliseconds(500)

    init(repository: CookedPhotoRepository, sleep: @escaping Sleep = Sleeps.real) {
        self.repository = repository
        self.sleep = sleep
    }

    func setRecipe(_ id: Int64) {
        guard id != recipeId else { return }
        recipeId = id
        subscription = repository.observe(recipeId: id)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] photos in
                guard let self else { return }
                self.uiState.photos = photos
                // The open photo follows the database (a new date); its note stays as typed.
                if let open = self.uiState.open, let fresh = photos.first(where: { $0.id == open.id }) {
                    self.uiState.open = fresh
                }
            }
    }

    /// Pictures from the library or the camera: each becomes an entry, cooked today.
    func onAdd(_ pictures: [Data]) {
        guard let recipeId, !pictures.isEmpty else { return }
        uiState.adding = true
        write {
            let added = await self.repository.add(recipeId: recipeId, pictures: pictures)
            if !added.isEmpty { self.madeThisPending = true }
            self.uiState.adding = false
            self.uiState.addFailed = added.count < pictures.count
            // The first new one opens, so its note and date are right there to fill in.
            if let first = added.first {
                self.uiState.open = first
                self.uiState.noteDraft = ""
            }
        }
    }

    /// "Mark as cooked" (#173): today's cooking with no photo. It opens like a new photo, so its
    /// note and date are right there, and closing it says the recipe was cooked, as a photo does.
    func onMarkCooked() {
        guard let recipeId else { return }
        uiState.adding = true
        write {
            let marked = await self.repository.markCooked(recipeId: recipeId)
            self.uiState.adding = false
            guard let marked else { return }
            self.madeThisPending = true
            self.uiState.open = marked
            self.uiState.noteDraft = ""
        }
    }

    func onAddFailedShown() { uiState.addFailed = false }

    func onOpen(_ photo: CookedPhoto) {
        saveNote()
        uiState.open = photo
        uiState.noteDraft = photo.note ?? ""
    }

    /// Closing the photo just added offers the pantry's use-up sheet, after its note, not over it.
    func onClose() {
        saveNote()
        uiState.open = nil
        uiState.noteDraft = ""
        if madeThisPending { uiState.madeThis = true }
        madeThisPending = false
    }

    func onMadeThisHandled() { uiState.madeThis = false }

    /// The note is written once typing pauses, or when the photo closes.
    func onNoteChange(_ text: String) {
        uiState.noteDraft = String(text.prefix(CookedPhoto.maxNote))
        noteSave?.cancel()
        noteSave = Task { [weak self, sleep] in
            do { try await sleep(Self.noteDelay) } catch { return }
            guard !Task.isCancelled else { return }
            self?.saveNote()
        }
    }

    func onDayChange(_ day: Int64) {
        guard let open = uiState.open else { return }
        let note = uiState.noteDraft
        write { await self.repository.edit(id: open.id, day: day, note: note) }
    }

    /// Deletes the open photo at once; `onUndoDelete` brings it back until `onDeleteSettled`.
    func onDelete() {
        noteSave?.cancel()
        // The photo (or mark, #173) just added, deleted at once, was a mistake: no cooking to offer.
        madeThisPending = false
        guard let open = uiState.open else { return }
        uiState.open = nil
        uiState.noteDraft = ""
        write {
            guard let removed = await self.repository.delete(id: open.id) else { return }
            // A still-pending earlier delete stands now.
            if let earlier = self.uiState.deleted { await self.repository.forget([earlier]) }
            self.uiState.deleted = removed
        }
    }

    func onUndoDelete() {
        guard let removed = uiState.deleted else { return }
        uiState.deleted = nil
        write { await self.repository.restore(removed) }
    }

    func onDeleteSettled() {
        guard let removed = uiState.deleted else { return }
        uiState.deleted = nil
        write { await self.repository.forget([removed]) }
    }

    /// Waits for the writes started so far (tests).
    func settleWrites() async { await lastWrite?.value }

    private func saveNote() {
        noteSave?.cancel()
        noteSave = nil
        guard let open = uiState.open else { return }
        let note = CookedPhoto.cleanNote(uiState.noteDraft)
        let current = uiState.photos.first { $0.id == open.id } ?? open
        guard note != current.note else { return }
        write { await self.repository.edit(id: open.id, day: current.day, note: note) }
    }

    /// Writes in order: each waits for the one before.
    private func write(_ body: @escaping @MainActor () async -> Void) {
        let previous = lastWrite
        lastWrite = Task { await previous?.value; await body() }
    }
}
