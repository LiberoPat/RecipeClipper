import Foundation
import Observation

/// One flag's row: its key and flags.json description (developer text, English by design).
struct FlagRow: Equatable {
    let flag: Flag
    let description: String
    let issue: Int?
    let on: Bool
    let changed: Bool
}

struct DeveloperSettingsUiState: Equatable {
    var flags: [FlagRow] = []
    var anyChanged: Bool { flags.contains { $0.changed } }
}

/// The hidden Developer settings (#87): a switch per flag and a reset, over `FeatureFlags`.
@MainActor
@Observable
final class DeveloperSettingsViewModel {
    private(set) var uiState = DeveloperSettingsUiState()
    @ObservationIgnored private let flags: FeatureFlags

    init(flags: FeatureFlags) {
        self.flags = flags
        refresh()
    }

    func onFlagChange(_ flag: Flag, _ on: Bool) {
        flags.set(flag, on)
        refresh()
    }

    func onReset() {
        flags.reset()
        refresh()
    }

    private func refresh() {
        uiState = DeveloperSettingsUiState(flags: Flag.allCases.map { flag in
            let definition = flags.definition(flag)
            return FlagRow(
                flag: flag,
                description: definition?.description ?? "",
                issue: definition?.issue,
                on: flags.isOn(flag),
                changed: flags.isOverridden(flag)
            )
        })
    }
}
