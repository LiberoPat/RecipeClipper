import Foundation
import Observation

/// Which `LibraryLimit` applies (#107): the `freeTier` flag, then the unlock, bought or the
/// Developer settings override. Android's `DefaultLibraryPolicy`.
///
/// The repository reads the limit from `mirror` (the App Group suite), not from here, because
/// the share extension saves recipes in its own process and never sees the flags or StoreKit.
/// So this keeps the mirror current: at start, and on every change it observes.
@MainActor
@Observable
final class LibraryPolicy {
    @ObservationIgnored private let flags: FeatureFlags
    @ObservationIgnored private let entitlements: Entitlements
    @ObservationIgnored private let mirror: DefaultsLibraryLimit?

    init(flags: FeatureFlags, entitlements: Entitlements, mirror: DefaultsLibraryLimit? = nil) {
        self.flags = flags
        self.entitlements = entitlements
        self.mirror = mirror
    }

    var limit: LibraryLimit {
        LibraryLimit.of(
            freeTier: flags.isOn(.freeTier),
            unlocked: entitlements.state.unlocked || flags.unlockedOverride
        )
    }

    /// Writes the limit to the mirror now and after every change, for as long as the app runs.
    func startMirroring() {
        guard let mirror else { return }
        let current = withObservationTracking { limit } onChange: { [weak self] in
            Task { @MainActor in self?.startMirroring() }
        }
        mirror.store(current)
    }
}
