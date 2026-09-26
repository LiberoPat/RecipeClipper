import Foundation

/// The store's product id of the one-time unlock (#107), the same on both stores.
let unlimitedRecipesProductId = "unlimited_recipes"

/// What the store says about the unlock (Android's `UnlockState`). `price` is the store's own
/// formatted price, nil until it has answered. `pending` is a purchase waiting on Ask to Buy.
struct UnlockState: Equatable, Sendable {
    var unlocked = false
    var pending = false
    var price: String?
}

/// How a purchase or a restore ended.
enum PurchaseOutcome: Equatable, Sendable {
    case unlocked, pending, cancelled
    /// A restore that found no purchase on this Apple Account.
    case nothingToRestore
    /// The store couldn't be reached, the product isn't set up, or the store refused.
    case failed

    /// Worth a word to the user: a wait or a failure.
    var needsNotice: Bool { self == .pending || self == .failed || self == .nothingToRestore }
}

/// The one-time, non-consumable unlock (#107). A seam: `StoreKitEntitlements` is the real one
/// (StoreKit 2), tests use `FakeEntitlements`. ViewModels see only this, never StoreKit.
/// Implementations are `@Observable`, so a view reading `state` follows it.
@MainActor
protocol Entitlements: AnyObject {
    var state: UnlockState { get }
    /// Shows the store's purchase sheet and waits for its answer.
    func purchase() async -> PurchaseOutcome
    /// Asks the store again for this account's purchases.
    func restore() async -> PurchaseOutcome
}

/// No store at all: locked, and every purchase fails. The default where none is given.
@MainActor
final class UnavailableEntitlements: Entitlements {
    let state = UnlockState()
    /// Nonisolated, so it can be a default argument.
    nonisolated init() {}
    func purchase() async -> PurchaseOutcome { .failed }
    func restore() async -> PurchaseOutcome { .failed }
}

/// Where the repository reads the limit that applies (#107), in any process. The app computes
/// it (`LibraryPolicy`) and mirrors it into the App Group suite, where the share extension,
/// which never sees the flags or StoreKit, reads it too.
protocol LibraryLimitSource: Sendable {
    func current() -> LibraryLimit
}

/// A limit that never changes: tests, and the old history cap by default.
struct FixedLibraryLimit: LibraryLimitSource {
    var limit: LibraryLimit = .history(keep: LibraryLimit.historyRecipes)
    func current() -> LibraryLimit { limit }
}

/// The mirrored limit in a `UserDefaults` suite, under `library_limit`: "free", "unlimited",
/// or anything else (nothing yet) for the history cap.
struct DefaultsLibraryLimit: LibraryLimitSource, @unchecked Sendable {
    static let key = "library_limit"
    let defaults: UserDefaults

    func current() -> LibraryLimit {
        switch defaults.string(forKey: Self.key) {
        case "free": .free(max: LibraryLimit.freeRecipes)
        case "unlimited": .unlimited
        default: .history(keep: LibraryLimit.historyRecipes)
        }
    }

    func store(_ limit: LibraryLimit) {
        let value: String = switch limit {
        case .free: "free"
        case .unlimited: "unlimited"
        case .history: "history"
        }
        defaults.set(value, forKey: Self.key)
    }
}
