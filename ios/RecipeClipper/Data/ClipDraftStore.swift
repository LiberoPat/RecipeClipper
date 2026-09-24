import Foundation

/// Unsaved "Clip it yourself" work, kept for the session so leaving and reopening the same page
/// picks up where the user stopped (#37, owner decision 9). Keyed by the cleaned URL. In memory
/// only, never written to disk: a draft is discarded after Save or an explicit Discard, and lost
/// with the process. One per app, owned by `AppContainer` (Android's `@Singleton`).
@MainActor
final class ClipDraftStore {
    private var drafts: [String: ClipDraft] = [:]

    func get(_ url: String) -> ClipDraft? { drafts[url] }

    /// Keeps `draft` under its URL, or forgets the URL when there is nothing in it.
    func put(_ draft: ClipDraft) {
        drafts[draft.sourceUrl] = draft.isEmpty ? nil : draft
    }

    func remove(_ url: String) { drafts[url] = nil }
}
