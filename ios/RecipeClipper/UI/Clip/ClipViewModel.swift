import Foundation
import Observation

/// What the snackbar says. The view picks the words; `.assigned` and `.cleared` offer Undo.
enum ClipMessage: Equatable {
    case assigned(ClipField, count: Int)
    case cleared(ClipField)
    /// A session draft for this page was brought back; offers Discard.
    case draftRestored
    case saveFailed
}

/// A `ClipMessage` to show once. `serial` tells two identical messages apart.
struct ClipNotice: Equatable {
    let message: ClipMessage
    let serial: Int
}

/// Android's ClipUiState. `selection` is the page's current selection split the way it would be
/// assigned. `newMarkId` is the mark the page should take from its current selection.
/// `savedRecipeId` is set once Save lands, so the view can open it.
struct ClipUiState: Equatable {
    let url: String
    var draft: ClipDraft
    var selection: [String] = []
    var pickingPhoto = false
    var newMarkId: String? = nil
    var reviewing = false
    var saving = false
    var notice: ClipNotice? = nil
    var savedRecipeId: Int64? = nil
}

/// "Clip it yourself" (#37), Android's ClipViewModel. The page itself lives in the view layer;
/// this only hears what happened on it (a selection, a tag or image tapped) and holds the
/// `ClipDraft`, kept in `ClipDraftStore` as it changes so Cancel keeps it for the session.
@MainActor
@Observable
final class ClipViewModel {
    private(set) var uiState: ClipUiState

    @ObservationIgnored private let url: String
    @ObservationIgnored private let repository: RecipeRepository
    @ObservationIgnored private let drafts: ClipDraftStore
    // The page's selection as given, before splitting: a name joins it rather than splitting it.
    @ObservationIgnored private var selectionText = ""
    // The draft before the last assignment or clear, for the snackbar's Undo.
    @ObservationIgnored private var undoTo: ClipDraft?
    @ObservationIgnored private var serial = 0

    init(url: String, repository: RecipeRepository, drafts: ClipDraftStore) {
        let cleaned = UrlCleaner.clean(url)
        self.url = cleaned
        self.repository = repository
        self.drafts = drafts
        let restored = drafts.get(cleaned)
        uiState = ClipUiState(url: cleaned, draft: restored ?? ClipDraft(sourceUrl: cleaned))
        if restored != nil { uiState.notice = notice(.draftRestored) }
    }

    // MARK: Events from the page

    func onSelectionChanged(_ text: String) {
        selectionText = text
        let lines = ClipSelection.lines(text)
        uiState.selection = lines
        // A new selection is never the one the last assignment was taken from.
        if !lines.isEmpty { uiState.newMarkId = nil }
    }

    /// Tapping a field's tag on the page clears that field, with Undo.
    func onTagTapped(_ field: ClipField) {
        let draft = uiState.draft
        guard draft.count(field) > 0 else { return }
        undoTo = draft
        setDraft(draft.clear(field), newMarkId: nil, message: .cleared(field))
    }

    /// While picking a photo, the next image tapped on the page becomes the photo.
    func onImageTapped(_ src: String) {
        guard uiState.pickingPhoto else { return }
        uiState.pickingPhoto = false
        assign(.photo, src)
    }

    // MARK: Events from the toolbar

    /// Puts the current selection into `field`, replacing what it held.
    func onAssign(_ field: ClipField) {
        guard field != .photo else { return }
        assign(field, selectionText)
    }

    func onPhotoButton() { uiState.pickingPhoto.toggle() }

    func onUndo() {
        guard let previous = undoTo else { return }
        undoTo = nil
        setDraft(previous, newMarkId: nil)
    }

    /// Throws the session draft away and starts over on the same page.
    func onDiscardDraft() {
        undoTo = nil
        drafts.remove(url)
        setDraft(ClipDraft(sourceUrl: url), newMarkId: nil)
    }

    func onNoticeShown(_ serial: Int) {
        if uiState.notice?.serial == serial { uiState.notice = nil }
    }

    // MARK: Review

    func onReview() {
        guard uiState.draft.canFinish else { return }
        uiState.reviewing = true
        uiState.pickingPhoto = false
    }

    func onBackToPage() { uiState.reviewing = false }

    func onNameChange(_ text: String) { edit { $0.name = text } }
    func onServesChange(_ text: String) { edit { $0.serves = text } }
    func onTotalTimeChange(_ text: String) { edit { $0.totalTime = text } }
    func onLineChange(_ field: ClipField, _ index: Int, _ text: String) { edit { $0 = $0.editLine(field, index, text) } }
    func onLineRemove(_ field: ClipField, _ index: Int) { edit { $0 = $0.removeLine(field, index) } }
    func onLineAdd(_ field: ClipField) { edit { $0 = $0.addLine(field) } }
    func onRemovePhoto() { edit { $0 = $0.clear(.photo) } }

    func onSave() {
        guard !uiState.saving, let recipe = uiState.draft.toRecipe() else { return }
        uiState.saving = true
        Task { [weak self, repository] in
            let result = await repository.saveClip(recipe)
            guard let self else { return }
            uiState.saving = false
            switch result {
            case .success(let saved):
                drafts.remove(url)
                uiState.savedRecipeId = saved.id
            case .error:
                uiState.notice = notice(.saveFailed)
            }
        }
    }

    // MARK: Internals

    private func assign(_ field: ClipField, _ text: String) {
        let draft = uiState.draft
        let markId = draft.pendingMarkId
        let assigned = draft.assign(field, text)
        guard assigned != draft else { return }
        undoTo = draft
        selectionText = ""
        uiState.selection = []
        setDraft(
            assigned,
            newMarkId: field == .photo ? nil : markId,
            message: .assigned(field, count: assigned.count(field))
        )
    }

    /// A hand edit in Review. Not undoable from the snackbar, so it ends any pending Undo.
    private func edit(_ change: (inout ClipDraft) -> Void) {
        undoTo = nil
        var draft = uiState.draft
        change(&draft)
        setDraft(draft, newMarkId: uiState.newMarkId)
    }

    private func setDraft(_ draft: ClipDraft, newMarkId: String?, message: ClipMessage? = nil) {
        drafts.put(draft)
        var state = uiState
        state.draft = draft
        state.newMarkId = newMarkId
        if let message { state.notice = notice(message) }
        // Discarding everything leaves nothing to review.
        state.reviewing = state.reviewing && !draft.isEmpty
        uiState = state
    }

    private func notice(_ message: ClipMessage) -> ClipNotice {
        serial += 1
        return ClipNotice(message: message, serial: serial)
    }
}
