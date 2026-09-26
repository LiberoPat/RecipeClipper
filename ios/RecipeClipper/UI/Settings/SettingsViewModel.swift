import Combine
import Foundation
import Observation

/// Mirrors AppPreferences — the app's global defaults, not any one recipe's.
struct SettingsUiState: Equatable {
    var unitSystem: UnitSystem = .asWritten
    var convertLiquids = false
    var temperatureUnit: TemperatureUnit = .asWritten
    var darkWhileCooking = false
    var backup: BackupStatus = .idle
    /// A morning notification when pantry items are about to expire (#52).
    var expiryReminders = false
    /// Turning reminders on was refused (notifications not allowed): the switch stays off and
    /// says why, until it is turned on successfully.
    var expiryRemindersDenied = false
    /// e.g. "1.0 (1)", shown at the foot; tapping it `SettingsViewModel.developerTaps` times opens
    /// Developer settings (#87).
    var appVersion = ""
    /// A purchase or restore is under way (#107), so the buttons can't be doubled.
    var unlockBusy = false
    /// A purchase or restore that didn't simply unlock; shown until the next one starts.
    var unlockNotice: PurchaseOutcome?
}

/// Android's `UnlockRow`: the "Unlimited recipes" row's state (#107).
struct UnlockRow: Equatable {
    var unlocked: Bool
    var pending = false
    var price: String?
    var busy = false
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
/// app-wide defaults. State is seeded synchronously so the first frame is right, then kept in
/// step with `preferences.settings`; each setter also updates it at once, alongside the
/// write, rather than waiting for the publisher to echo it back.
@MainActor
@Observable
final class SettingsViewModel {
    private(set) var uiState: SettingsUiState
    @ObservationIgnored private let preferences: AppPreferences
    @ObservationIgnored private let backups: BackupRepository
    @ObservationIgnored private let files: BackupFiles
    @ObservationIgnored private var settingsSubscription: AnyCancellable?
    @ObservationIgnored private let appVersion: String
    @ObservationIgnored private var versionTaps = 0
    @ObservationIgnored private let flags: FeatureFlags?
    @ObservationIgnored private let notificationPermission: NotificationPermission
    @ObservationIgnored private let entitlements: Entitlements

    static let developerTaps = 7

    init(
        preferences: AppPreferences, backups: BackupRepository, files: BackupFiles, appVersion: String = "",
        flags: FeatureFlags? = nil,
        notificationPermission: NotificationPermission = FixedNotificationPermission(granted: true),
        entitlements: Entitlements = UnavailableEntitlements()
    ) {
        self.flags = flags
        self.entitlements = entitlements
        self.notificationPermission = notificationPermission
        self.preferences = preferences
        self.backups = backups
        self.files = files
        self.appVersion = appVersion
        uiState = Self.uiState(preferences.current)
        uiState.appVersion = appVersion
        settingsSubscription = preferences.settings
            .receive(on: DispatchQueue.main)
            .sink { [weak self] settings in
                guard let self else { return }
                // The backup status is this screen's own: a preference change keeps it.
                var next = Self.uiState(settings)
                next.backup = self.uiState.backup
                next.expiryRemindersDenied = self.uiState.expiryRemindersDenied
                next.appVersion = self.appVersion
                next.unlockBusy = self.uiState.unlockBusy
                next.unlockNotice = self.uiState.unlockNotice
                self.uiState = next
            }
    }

    private static func uiState(_ settings: AppSettings) -> SettingsUiState {
        SettingsUiState(
            unitSystem: settings.unitSystem,
            convertLiquids: settings.convertLiquids,
            temperatureUnit: settings.temperatureUnit,
            darkWhileCooking: settings.darkWhileCooking,
            expiryReminders: settings.expiryReminders
        )
    }

    /// The Pantry section (#52): only with the `mealPlan` flag on, since the pantry is behind
    /// it. Read through the observable flags, so it follows Developer settings.
    var showsPantry: Bool { flags?.isOn(.mealPlan) ?? false }

    /// The "Unlimited recipes" row (#107): nil while the `freeTier` flag is off. Unlocked counts
    /// Developer settings' override too. Read through the observable flags and store.
    var unlockRow: UnlockRow? {
        guard let flags, flags.isOn(.freeTier) else { return nil }
        let store = entitlements.state
        return UnlockRow(
            unlocked: store.unlocked || flags.unlockedOverride, pending: store.pending, price: store.price,
            busy: uiState.unlockBusy
        )
    }

    /// "Unlock" (#107): the store's purchase sheet.
    func onUnlock() { runUnlock { [entitlements] in await entitlements.purchase() } }

    /// "Restore purchase" (#107): asks the store for this account's purchase again.
    func onRestore() { runUnlock { [entitlements] in await entitlements.restore() } }

    private func runUnlock(_ action: @escaping @MainActor () async -> PurchaseOutcome) {
        guard !uiState.unlockBusy else { return }
        uiState.unlockNotice = nil
        uiState.unlockBusy = true
        Task { [weak self] in
            let outcome = await action()
            guard let self else { return }
            uiState.unlockBusy = false
            if outcome.needsNotice { uiState.unlockNotice = outcome }
        }
    }

    /// The expiry reminders switch (#52). On asks for notification permission first (only here,
    /// never on launch); refused, the switch stays off and the row says why. Returns the work so
    /// a test can await it.
    @discardableResult
    func onExpiryRemindersChange(_ on: Bool) -> Task<Void, Never>? {
        guard on else {
            preferences.expiryReminders = false
            uiState.expiryReminders = false
            return nil
        }
        return Task {
            let granted = await notificationPermission.request()
            preferences.expiryReminders = granted
            uiState.expiryReminders = granted
            uiState.expiryRemindersDenied = !granted
        }
    }

    /// The hidden way into Developer settings (#87), in release builds too (the owner's call):
    /// true on the `developerTaps`th tap, when the screen opens it, and the count starts over.
    func onVersionTapped() -> Bool {
        versionTaps += 1
        guard versionTaps >= Self.developerTaps else { return false }
        versionTaps = 0
        return true
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

    /// "Also convert liquids" only means something for Ounces: Metric always gives liquids in ml.
    var showsConvertLiquids: Bool {
        uiState.unitSystem == .ounces
    }
}
