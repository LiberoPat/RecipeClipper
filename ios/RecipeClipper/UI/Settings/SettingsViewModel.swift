import Foundation
import Observation

/// Mirrors AppPreferences — the app's global defaults, not any one recipe's.
struct SettingsUiState: Equatable {
    var unitSystem: UnitSystem = .asWritten
    var convertLiquids = false
    var temperatureUnit: TemperatureUnit = .asWritten
    var darkWhileCooking = false
    var backup: BackupStatus = .idle
}

/// The "Your recipes" section: export and import (#26). One at a time; the screen shows the
/// last outcome under the two rows until the next action.
enum BackupStatus: Equatable {
    case idle
    case exporting
    case importing
    /// The file is written: the screen opens the share sheet on it, then calls `onExportShared`.
    case readyToShare(URL)
    case imported(ImportSummary)
    case failed(BackupError)

    var isBusy: Bool {
        switch self {
        case .exporting, .importing, .readyToShare: return true
        default: return false
        }
    }
}

/// Injects AppPreferences directly rather than going through a repository: these are
/// app-wide defaults. Preferences are plain vars, so state is seeded once here and updated
/// alongside each write.
@MainActor
@Observable
final class SettingsViewModel {
    private(set) var uiState: SettingsUiState
    @ObservationIgnored private let preferences: AppPreferences
    @ObservationIgnored private let backups: BackupRepository
    @ObservationIgnored private let files: BackupFiles

    init(preferences: AppPreferences, backups: BackupRepository, files: BackupFiles) {
        self.preferences = preferences
        self.backups = backups
        self.files = files
        uiState = SettingsUiState(
            unitSystem: preferences.unitSystem,
            convertLiquids: preferences.convertLiquids,
            temperatureUnit: preferences.temperatureUnit,
            darkWhileCooking: preferences.darkWhileCooking
        )
    }

    func onUnitSystemChange(_ system: UnitSystem) {
        preferences.unitSystem = system
        uiState.unitSystem = system
    }

    func onConvertLiquidsChange(_ enabled: Bool) {
        preferences.convertLiquids = enabled
        uiState.convertLiquids = enabled
    }

    func onTemperatureUnitChange(_ unit: TemperatureUnit) {
        preferences.temperatureUnit = unit
        uiState.temperatureUnit = unit
    }

    func onDarkWhileCookingChange(_ enabled: Bool) {
        preferences.darkWhileCooking = enabled
        uiState.darkWhileCooking = enabled
    }

    /// Export: read everything out, write the file, then hand it to the screen to share.
    /// Returns the work so a test can await it; the screen ignores it.
    @discardableResult
    func onExport() -> Task<Void, Never>? {
        guard !uiState.backup.isBusy else { return nil }
        uiState.backup = .exporting
        return Task {
            switch await backups.export() {
            case .failure(let error):
                uiState.backup = .failed(error)
            case .success(let exported):
                if let url = await files.writeExport(json: exported.json, exportedAt: exported.exportedAt) {
                    uiState.backup = .readyToShare(url)
                } else {
                    uiState.backup = .failed(.exportFailed)
                }
            }
        }
    }

    /// The share sheet has been dismissed (or couldn't open): the export is done.
    func onExportShared() {
        if case .readyToShare = uiState.backup { uiState.backup = .idle }
    }

    /// Import from the file the user picked in the file importer.
    @discardableResult
    func onImportPicked(_ url: URL) -> Task<Void, Never>? {
        guard !uiState.backup.isBusy else { return nil }
        uiState.backup = .importing
        return Task {
            switch await files.readText(url) {
            case .failure(let error):
                uiState.backup = .failed(error)
            case .success(let text):
                switch await backups.importBackup(text) {
                case .success(let summary): uiState.backup = .imported(summary)
                case .failure(let error): uiState.backup = .failed(error)
                }
            }
        }
    }

    /// The file importer failed before a file was chosen (a provider error, not a cancel).
    func onImportPickFailed() {
        guard !uiState.backup.isBusy else { return }
        uiState.backup = .failed(.readFailed)
    }

    /// "Also convert liquids" only means something for Grams and Ounces.
    var showsConvertLiquids: Bool {
        uiState.unitSystem == .grams || uiState.unitSystem == .ounces
    }
}
