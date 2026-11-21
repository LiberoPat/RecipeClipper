import Foundation

/// Pro (#413) at one moment (Android's `ProState`): the one place a screen learns whether the Week
/// planning extras are open. `gated` is the `proUnlock` flag (off, nothing is gated: the app as
/// before #413). `isPro` is the one-time unlock of #107, bought on this Apple Account or counted as
/// bought in Developer settings: a cook who unlocked "unlimited recipes" is Pro, since it is the same
/// product and the same cached entitlement. `price` is the store's own formatted price, nil until it
/// has answered (never a figure written here); `pending` a purchase waiting on Ask to Buy.
struct ProState: Equatable, Sendable {
    var gated = false
    var isPro = false
    var pending = false
    var price: String?

    /// The Week planning extras may be used: nothing is gated, or the cook has Pro.
    var extras: Bool { !gated || isPro }

    /// An extra shows its Pro badge and opens the Pro screen instead of acting.
    var locked: Bool { !extras }
}

/// Reads `ProState` (#413) for ViewModels and the reminders, and runs the purchase and restore,
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

/// The evening and Tonight reminders under Pro (#397, #413; Android's `ProReminders`). Locked, a
/// reminder the cook switched on themselves keeps running (nothing already set up is taken away)
/// and can still be turned off or retimed; only switching one on needs Pro. The evening reminder's
/// default (on, never stored) is a Pro default: a cook without Pro who never touched the switch gets
/// no reminder.
enum ProReminders {
    /// Whether a reminder stored as `on` runs: `chosen` is whether the cook ever set its switch.
    static func runs(on: Bool, chosen: Bool, pro: ProState) -> Bool { on && (pro.extras || chosen) }
}
