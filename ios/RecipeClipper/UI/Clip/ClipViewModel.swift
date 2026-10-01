import Foundation
import Observation

/// What the snackbar says. The view picks the words; `.removed` offers Undo.
enum ClipMessage: Equatable {
    /// A page tag took back one add: `count` lines of the field (1 for a name or a photo).
    case removed(ClipField, count: Int)
    /// A session draft for this page was brought back; offers Discard.
    case draftRestored
    case saveFailed
    /// The tap while picking a photo found no picture the app can read; the photo is optional.
    case photoUnreadable
    /// The Unlock from the full-library prompt (#107) is pending or failed.
    case unlock(PurchaseOutcome)
    /// Done or Save with too little to save: what's still missing, a `name` and/or ingredients or
    /// steps (`lines`). Save never does nothing silently.
    case missing(name: Bool, lines: Bool)
    /// The Text view found no post in the page yet (still loading, or Reddit's check).
    case textUnreadable
}

/// Where the cook is with Cloudflare's check (#220), when the clip view opened for it in the
/// import's place (Android's ClipCheck). `waiting`: the page is the check, with a note saying
/// what to do and no clip toolbar; each page it settles on is read, and one with a recipe opens
/// as an ordinary import. `noRecipe`: past the check, the page has no recipe data, so the clip
/// toolbar is offered with a note saying why.
enum ClipCheck: Equatable {
    case waiting
    case noRecipe
}

/// A `ClipMessage` to show once. `serial` tells two identical messages apart.
struct ClipNotice: Equatable {
    let message: ClipMessage
    let serial: Int
}

/// The add just made, for the hint bar (#237): `count` lines put into `field`.
struct ClipAdded: Equatable {
    let field: ClipField
    let count: Int
}

/// The one line over the field buttons (#237): what to do now. The view picks the words.
enum ClipHint: Equatable {
    /// Photo is armed: tap the recipe's photo (or Skip).
    case pickPhoto
    /// The field is armed and nothing is selected yet: tap (or select) it on the page.
    case select(ClipField)
    /// The field is armed and `lines` are selected: one tap adds them.
    case confirm(ClipField, lines: Int)
    /// Text selected with no field armed (a long press): tap the field it goes in.
    case selected(lines: Int)
    /// What was just `added`, if anything, and the field to fill `next` (nil: all there).
    case next(added: ClipAdded?, next: ClipField?)
}

/// Android's ClipUiState. `selection` is the page's current selection split the way it would be
/// assigned. `newMarkId` is the mark the page should take from its current selection.
/// `savedRecipeId` is set once Save lands, so the view can open it. Field first (#237): `armed` is
/// the field the cook tapped, which the page's taps select for; `lastAdded` is the add just made;
/// `clearSelection` counts the times the page's selection was dropped, so the page clears its own
/// when it changes.
struct ClipUiState: Equatable {
    let url: String
    var draft: ClipDraft
    var selection: [String] = []
    var armed: ClipField? = nil
    var lastAdded: ClipAdded? = nil
    var clearSelection = 0
    var newMarkId: String? = nil
    var reviewing = false
    var saving = false
    var notice: ClipNotice? = nil
    var savedRecipeId: Int64? = nil
    /// The clip couldn't be saved: the free library is full and all protected (#107).
    var libraryFull = false
    /// Opened by itself because Reddit wouldn't let the app read the post (#213): the view
    /// says so above the page.
    var readBlocked = false
    /// Opened for the cook to pass Cloudflare's check (#220); nil for every other clip.
    var check: ClipCheck? = nil

    /// The address the page loads: `url`, except a Reddit link on another of Reddit's hosts,
    /// which loads from www.reddit.com (`RedditUrls.clipPageUrl`, #213). The clip is still saved
    /// under `url`.
    var pageUrl: String { RedditUrls.clipPageUrl(url) }

    /// A Reddit post (#213): the clip offers the Text view beside the page.
    var offersText: Bool { RedditUrls.isReddit(url) }
    /// The Text view asked for: the view layer reads the page and hands it to `onPageText`.
    var readingText = false
    /// The post as plain text, read from the page the last time the Text view opened.
    var pageText: RedditPageText? = nil
    /// The Text view is showing, over the page, which stays loaded under it.
    var showingText = false

    /// Photo is armed: the page's next tap picks the photo.
    var pickingPhoto: Bool { armed == .photo }

    /// The armed field a page selection goes to, when it is one of the text fields.
    var armedText: ClipField? { armed == .photo ? nil : armed }

    var hint: ClipHint {
        if pickingPhoto { return .pickPhoto }
        if let armed, !selection.isEmpty { return .confirm(armed, lines: armed == .name ? 1 : selection.count) }
        if !selection.isEmpty { return .selected(lines: selection.count) }
        if let lastAdded { return .next(added: lastAdded, next: draft.nextField) }
        if let armed { return .select(armed) }
        return .next(added: nil, next: draft.nextField)
    }
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
    @ObservationIgnored private let entitlements: Entitlements
    // The page's selection as given, before splitting: a name joins it rather than splitting it.
    @ObservationIgnored private var selectionText = ""
    // The drafts before each change made on the page (an add, a tag's removal), newest last, for
    // Undo. A hand edit in Review empties it: Undo never steps over typing.
    @ObservationIgnored private var undo: [ClipDraft] = []
    /// How many changes Undo can take back.
    private static let undoDepth = 50
    @ObservationIgnored private var serial = 0
    // Reading the page the check settled on (#220); a newer page replaces it.
    @ObservationIgnored private var checkTask: Task<Void, Never>?
    // A recipe read past the check that a full library didn't keep (#107), for the Unlock.
    @ObservationIgnored private var unkept: Recipe?

    init(
        url: String, repository: RecipeRepository, drafts: ClipDraftStore,
        entitlements: Entitlements = UnavailableEntitlements(), readBlocked: Bool = false, check: Bool = false
    ) {
        self.entitlements = entitlements
        let cleaned = UrlCleaner.clean(url)
        self.url = cleaned
        self.repository = repository
        self.drafts = drafts
        let restored = drafts.get(cleaned)
        uiState = ClipUiState(
            url: cleaned, draft: restored ?? ClipDraft(sourceUrl: cleaned), readBlocked: readBlocked,
            check: check ? .waiting : nil
        )
        if restored != nil { uiState.notice = notice(.draftRestored) }
    }

    // MARK: Events from the page

    func onSelectionChanged(_ text: String) {
        // Selecting new text (a long press) moves on from the photo: the other fields never wait on it.
        let selectedAnew = text != selectionText
        selectionText = text
        let lines = ClipSelection.lines(text)
        var state = uiState
        state.selection = lines
        if !lines.isEmpty {
            // A new selection is never the one the last assignment was taken from.
            state.newMarkId = nil
            state.lastAdded = nil
            if state.pickingPhoto && selectedAnew { state.armed = nil }
        }
        uiState = state
    }

    /// Tapping an add's tag on the page takes that add back, with Undo.
    func onTagTapped(_ markId: String) {
        if uiState.pickingPhoto { uiState.armed = nil }
        uiState.lastAdded = nil
        let draft = uiState.draft
        guard let mark = draft.mark(markId) else { return }
        push(draft)
        setDraft(draft.removeMark(markId), newMarkId: nil, message: .removed(mark.field, count: mark.lines.count))
    }

    /// While picking a photo, the next image tapped on the page becomes the photo: a web image
    /// only (`WebImageUrl`, #235). Any other address (`file:`, `data:`, relative, …) is no
    /// picture, as `onNoImageTapped` says.
    func onImageTapped(_ src: String) {
        guard uiState.pickingPhoto else { return }
        guard let photo = WebImageUrl.of(src) else { return onNoImageTapped() }
        uiState.armed = nil
        assign(.photo, photo)
    }

    /// While Photo is armed, the tap found no picture with an address the app can read (not an
    /// image, or one drawn some other way). Picking ends and the screen says so: no photo is
    /// better than a guessed one, and the photo is optional.
    func onNoImageTapped() {
        guard uiState.pickingPhoto else { return }
        var state = uiState
        state.armed = nil
        state.notice = notice(.photoUnreadable)
        uiState = state
    }

    /// The page settled while waiting on Cloudflare's check (#220), as `html`: read through the
    /// repository. A recipe opens as an import would (`savedRecipeId`); the check still showing
    /// keeps waiting; a page past it with no recipe offers the clip.
    func onPageLoaded(_ html: String) {
        guard uiState.check == .waiting else { return }
        checkTask?.cancel()
        checkTask = Task { [weak self, repository, url] in
            let result = await repository.importPage(url, html: html)
            guard let self, !Task.isCancelled, uiState.check == .waiting else { return }
            switch result {
            case .success(let recipe):
                drafts.remove(url)
                uiState.savedRecipeId = recipe.id
            case .notKept(let recipe):
                unkept = recipe
                uiState.libraryFull = true
            case .error(.humanCheck):
                break
            case .error(.saveFailed):
                uiState.notice = notice(.saveFailed)
            case .error:
                uiState.check = .noRecipe
            }
        }
    }

    /// The running page read, for a caller (the tests) that needs to wait for it.
    var currentCheck: Task<Void, Never>? { checkTask }

    // MARK: Events from the toolbar: field first (#237)

    /// A field button: arms `field`, so the page's taps select for it (or, for the photo, the next
    /// tap picks it); the armed field again disarms it, and another switches. A selection already
    /// made stays for the field switched to; disarming drops it. The photo is picked on the page,
    /// so arming it also leaves the Text view.
    func onFieldButton(_ field: ClipField) {
        if uiState.armed == field {
            if !uiState.selection.isEmpty { clearSelection() }
            uiState.armed = nil
            uiState.lastAdded = nil
        } else if field == .photo {
            if !uiState.selection.isEmpty { clearSelection() }
            var state = uiState
            state.armed = field
            state.lastAdded = nil
            state.showingText = false
            uiState = state
        } else {
            var state = uiState
            state.armed = field
            state.lastAdded = nil
            uiState = state
        }
    }

    /// The hint bar's confirm ("Add 12 lines to Ingredients"): the selection goes into the armed
    /// field. A name replaces and disarms; ingredients and steps add, and stay armed for the next
    /// block.
    func onConfirm() {
        guard let field = uiState.armedText, !uiState.selection.isEmpty else { return }
        assign(field, selectionText)
        if field == .name { uiState.armed = nil }
    }

    /// The hint bar's Clear: drops the selection, keeping the field armed.
    func onClearSelection() { clearSelection() }

    // MARK: The Text view (#213)

    /// Asks for the Text view: the view layer reads the page as it stands (with whatever comments
    /// it has loaded) and calls `onPageText`. The selection belongs to the view it was made in, so
    /// it goes; an armed text field stays armed.
    func onShowText() {
        guard uiState.offersText else { return }
        clearSelection()
        var state = uiState
        state.readingText = true
        state.armed = state.armedText
        uiState = state
    }

    /// The page's markup, read for the Text view. A page with no post in it yet says so.
    func onPageText(_ html: String) {
        guard uiState.readingText else { return }
        let text = RedditPageText.parse(html)
        var state = uiState
        state.readingText = false
        if text.isEmpty {
            state.notice = notice(.textUnreadable)
        } else {
            state.showingText = true
            // The same text leaves the view's page (and its marks) as they are.
            if text != state.pageText { state.pageText = text }
        }
        uiState = state
    }

    /// Back to the page from the Text view: the draft and the armed field carry over.
    func onShowPage() {
        clearSelection()
        uiState.showingText = false
        uiState.readingText = false
    }

    private func clearSelection() {
        selectionText = ""
        var state = uiState
        state.selection = []
        state.newMarkId = nil
        state.clearSelection += 1
        uiState = state
    }

    /// Leaves the photo step without a (new) photo: it's optional.
    func onSkipPhoto() { uiState.armed = nil }

    /// Takes back the last change from the page (an add, or a tag's removal).
    func onUndo() {
        guard let previous = undo.popLast() else { return }
        uiState.lastAdded = nil
        setDraft(previous, newMarkId: nil)
    }

    /// Throws the session draft away and starts over on the same page.
    func onDiscardDraft() {
        undo.removeAll()
        drafts.remove(url)
        uiState.lastAdded = nil
        setDraft(ClipDraft(sourceUrl: url), newMarkId: nil)
    }

    func onNoticeShown(_ serial: Int) {
        if uiState.notice?.serial == serial { uiState.notice = nil }
    }

    // MARK: Review

    /// Done: Review, when there's something to review. Never a silent no: with no name but some
    /// lines, Review opens with a note to type the name there (a Reddit title is hard to select);
    /// with no lines, the page stays and the note says what to select.
    func onReview() {
        var state = uiState
        if let missing = missing(state.draft) {
            state.notice = notice(missing)
            if case .missing(_, lines: false) = missing {
                state.reviewing = true
                state.armed = state.armedText
            }
        } else {
            state.reviewing = true
            state.armed = state.armedText
        }
        uiState = state
    }

    /// What the draft still needs before it can be saved, or nil when it can be.
    private func missing(_ draft: ClipDraft) -> ClipMessage? {
        draft.canFinish ? nil : .missing(name: draft.name.kTrimmed.isEmpty, lines: !draft.hasLines)
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
        guard !uiState.saving else { return }
        guard let recipe = uiState.draft.toRecipe() else {
            if let missing = missing(uiState.draft) { uiState.notice = notice(missing) }
            return
        }
        uiState.saving = true
        Task { [weak self, repository] in
            let result = await repository.saveClip(recipe)
            guard let self else { return }
            uiState.saving = false
            switch result {
            case .success(let saved):
                drafts.remove(url)
                uiState.savedRecipeId = saved.id
            case .notKept:
                uiState.libraryFull = true
            case .error:
                uiState.notice = notice(.saveFailed)
            }
        }
    }

    /// Unlock from the full-library prompt (#107), then save the clip as it stands.
    func onUnlock() {
        uiState.libraryFull = false
        Task { [weak self, entitlements] in
            let outcome = await entitlements.purchase()
            guard let self else { return }
            if outcome == .unlocked {
                // The recipe read past Cloudflare's check (#220), else the clip as it stands.
                guard let read = unkept else { return onSave() }
                if case .success(let kept) = await repository.keep(read) {
                    unkept = nil
                    uiState.savedRecipeId = kept.id
                } else {
                    uiState.notice = notice(.saveFailed)
                }
            } else if outcome.needsNotice {
                uiState.notice = notice(.unlock(outcome))
            }
        }
    }

    func onLibraryFullDismiss() { uiState.libraryFull = false }

    // MARK: Internals

    private func push(_ draft: ClipDraft) {
        undo.append(draft)
        if undo.count > Self.undoDepth { undo.removeFirst() }
    }

    /// The add itself. Its words go in the hint bar (`lastAdded`), with Undo there, rather than
    /// a snackbar. The page drops its selection when it records the new mark.
    private func assign(_ field: ClipField, _ text: String) {
        let draft = uiState.draft
        let markId = draft.pendingMarkId
        let assigned = draft.assign(field, text)
        guard assigned != draft else { return }
        push(draft)
        selectionText = ""
        var state = uiState
        state.selection = []
        state.lastAdded = ClipAdded(field: field, count: assigned.mark(markId)?.lines.count ?? 1)
        uiState = state
        setDraft(assigned, newMarkId: field == .photo ? nil : markId)
    }

    /// A hand edit in Review. Not undoable, so it ends Undo for what came before.
    private func edit(_ change: (inout ClipDraft) -> Void) {
        undo.removeAll()
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
