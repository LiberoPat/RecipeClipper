import Combine
import Foundation
import Observation

/// "Your cooks" (#116; Android's CookedPhotosUiState): `photos` newest cook first; `open` the
/// one shown full screen, with `noteDraft` its note as typed; `deleted` the one just deleted,
/// until its Undo snackbar is settled; `addFailed` until the view has said so.
struct CookedPhotosUiState: Equatable {
    var photos: [CookedPhoto] = []
    var open: CookedPhoto?
    var noteDraft = ""
    var adding = false
    var deleted: CookedPhoto?
    var addFailed = false
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

    /// Pictures from PhotosPicker or the camera: each becomes an entry, cooked today.
    func onAdd(_ pictures: [Data]) {
        guard let recipeId, !pictures.isEmpty else { return }
        uiState.adding = true
        write {
            let added = await self.repository.add(recipeId: recipeId, pictures: pictures)
            self.uiState.adding = false
            self.uiState.addFailed = added.count < pictures.count
            // The first new one opens, so its note and date are right there to fill in.
            if let first = added.first {
                self.uiState.open = first
                self.uiState.noteDraft = ""
            }
        }
    }

    func onAddFailedShown() { uiState.addFailed = false }

    func onOpen(_ photo: CookedPhoto) {
        saveNote()
        uiState.open = photo
        uiState.noteDraft = photo.note ?? ""
    }

    func onClose() {
        saveNote()
        uiState.open = nil
        uiState.noteDraft = ""
    }

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
