import Foundation

/// One thing Pro adds: a title and a line for the Pro screen's list (Android's `ProExtra`).
struct ProExtra: Equatable, Sendable {
    let title: String
    let body: String
}

/// What Pro adds, the one list per platform (Android's `ProExtras`). **Empty** (owner, 2026-11-23): the
/// Week extras of #413 are all free, and Pro is kept for future AI features. While it is empty nothing
/// is gated, and Settings has no Pro row and no screen opens the Pro page; the first entry brings them back.
enum ProExtras {
    static let all: [ProExtra] = []
}

/// Pro (#413) at one moment (Android's `ProState`): the one place a screen learns whether the Pro
/// extras are open (`available` says which there are, none today). `gated` is the `proUnlock` flag (off, nothing is gated: the app as
/// before #413). `isPro` is the one-time unlock of #107, bought on this Apple Account or counted as
/// bought in Developer settings: a cook who unlocked "unlimited recipes" is Pro, since it is the same
/// product and the same cached entitlement. `price` is the store's own formatted price, nil until it
/// has answered (never a figure written here); `pending` a purchase waiting on Ask to Buy.
struct ProState: Equatable, Sendable {
    var gated = false
    var isPro = false
    var pending = false
    var price: String?
    /// What Pro adds (`ProExtras.all`); the Pro page and its Settings row exist only while this isn't empty.
    var available: [ProExtra] = ProExtras.all

    /// Whether Settings lists the Pro page: the flag is on and Pro adds something.
    var showsPage: Bool { gated && !available.isEmpty }

    /// The Pro extras may be used: nothing is gated, or the cook has Pro.
    var extras: Bool { !gated || isPro }

    /// An extra shows its Pro badge and opens the Pro screen instead of acting.
    var locked: Bool { !extras }
}

/// Reads `ProState` (#413) for ViewModels, and runs the purchase and restore,
/// which are `Entitlements`' (StoreKit, #107). Implementations read observable sources, so a view
/// or a ViewModel's computed state reading `state` follows it; nothing reads a build constant.
/// `UngatedProAccess` is the default where none is given (tests that don't care): every extra open.
@MainActor
protocol ProAccess: AnyObject {
    var state: ProState { get }
    func purchase() async -> PurchaseOutcome
    func restore() async -> PurchaseOutcome
}

/// Nothing gated, as before #413. Nonisolated init, so it can be a default argument.
@MainActor
final class UngatedProAccess: ProAccess {
    let state = ProState()
    nonisolated init() {}
    func purchase() async -> PurchaseOutcome { .failed }
    func restore() async -> PurchaseOutcome { .failed }
}
