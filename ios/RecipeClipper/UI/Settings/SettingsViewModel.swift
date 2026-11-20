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
    /// The evening-before reminder of tomorrow's meals (#397), and its time (minutes since midnight).
    var eveningReminder = true
    var eveningReminderMinute = MealReminders.eveningMinute
    /// The optional afternoon Tonight reminder, and its time.
    var tonightReminder = false
    var tonightReminderMinute = MealReminders.tonightMinute
    /// What the screen last found: whether the phone lets the app post notifications at all.
    var notificationsAllowed = true
    /// Turning a Week reminder on was refused: the switch stays off and says why, until one is turned on.
    var weekRemindersDenied = false
    /// The Week page says notifications are off: refused just now, or on with the phone not allowing them.
    var weekRemindersBlocked: Bool { weekRemindersDenied || (!notificationsAllowed && (eveningReminder || tonightReminder)) }
    /// Ingredient amounts inside steps (#101).
    var amountsInSteps = false
    /// e.g. "1.0 (1)", shown at the foot; tapping it `SettingsViewModel.developerTaps` times opens
    /// Developer settings (#87).
    var appVersion = ""
    /// Chef mode as saved: short steps written on the device (#100).
    var chefMode = false
    /// What this phone can do, once asked; nil until then. The switch works only when available.
    var chefSupport: ChefSupport?
    /// A purchase or restore is under way (#107), so the buttons can't be doubled.
    var unlockBusy = false
    /// A purchase or restore that didn't simply unlock; shown until the next one starts.
    var unlockNotice: PurchaseOutcome?
    /// The automatic backup copy's rows (#150); nil without one (tests that don't care).
    var autoBackup: AutoBackupRow?
    /// "Put recipes in lists by their categories" (#387): one switch for all three importers; on by default; this visit's choice, not a saved setting.
    var paprikaFiling = true
}

/// The automatic backup copy's rows in Your recipes (#150; Android's `AutoBackupRow`): the
/// switch, "Last backed up", "Back up now", and a nudge when the last copy is over 30 days old
/// and nothing is copying. `lastBackupAt` is nil before the first copy.
struct AutoBackupRow: Equatable {
    var enabled: Bool
    var destination: BackupDestination
    var lastBackupAt: Int64?
    var lastFailed: Bool
    var nudge: Bool
    var running = false

    /// "Back up now" needs somewhere to write.
    var canBackUpNow: Bool { destination == .ready && !running }

    /// One of the page's warning lines shows, so the index's value is in the error colour too (#286).
    var needsAttention: Bool { destination == .unavailable || nudge || (lastFailed && destination == .ready) }

    static func of(_ state: AutoBackupState, now: Int64, running: Bool) -> AutoBackupRow {
        AutoBackupRow(
            enabled: state.record.enabled,
            destination: state.destination,
            lastBackupAt: state.record.lastBackupAt,
            lastFailed: state.record.lastFailed,
            nudge: AutoBackupPolicy.needsNudge(state.record, state.destination, now: now),
            running: running
        )
    }
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
    /// Import from Paprika (#382) is reading the file; shares the one-at-a-time rule.
    case importingPaprika
    /// Import from Recipe Keeper (#390): the same route and summary as Paprika's, its own words.
    case importingRecipeKeeper
    /// The file is written: the screen opens the share sheet on it, then calls `onExportShared`.
    case readyToShare(URL)
    case imported(ImportSummary)
    case failed(BackupError)
    case importedPaprika(PaprikaSummary)
    case paprikaFailed(PaprikaError)
    case importedRecipeKeeper(PaprikaSummary)
    case recipeKeeperFailed(PaprikaError)

    /// Import from Mealie (#391) is reading the file; shares the one-at-a-time rule.
    case importingMealie
    case importedMealie(MealieSummary)
    case mealieFailed(MealieError)


    var isBusy: Bool {
        switch self {
        case .exporting, .importing, .importingPaprika, .importingRecipeKeeper, .importingMealie, .readyToShare: return true
        default: return false
        }
    }

    /// An import's outcome as the status line shows it (Settings' Import, Home's Restore).
    init(_ result: Result<ImportSummary, BackupError>) {
        switch result {
        case .success(let summary): self = .imported(summary)
        case .failure(let error): self = .failed(error)
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
    @ObservationIgnored private let shortSteps: ShortStepRepository?
    @ObservationIgnored private var askedChefSupport = false
    @ObservationIgnored private let entitlements: Entitlements
    @ObservationIgnored private let autoBackup: AutoBackup?
    @ObservationIgnored private let paprika: PaprikaImportRepository?
    @ObservationIgnored private let mealie: MealieImportRepository?
    @ObservationIgnored private let clock: Clock
    @ObservationIgnored private var autoBackupSubscription: AnyCancellable?

    static let developerTaps = 7

    init(
        preferences: AppPreferences, backups: BackupRepository, files: BackupFiles, appVersion: String = "",
        flags: FeatureFlags? = nil,
        notificationPermission: NotificationPermission = FixedNotificationPermission(granted: true),
        shortSteps: ShortStepRepository? = nil,
        entitlements: Entitlements = UnavailableEntitlements(),
        autoBackup: AutoBackup? = nil,
        clock: Clock = SystemClock(),
        paprika: PaprikaImportRepository? = nil,
        mealie: MealieImportRepository? = nil
    ) {
        self.paprika = paprika
        self.mealie = mealie
        self.autoBackup = autoBackup
        self.clock = clock
        self.flags = flags
        self.shortSteps = shortSteps
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
                next.notificationsAllowed = self.uiState.notificationsAllowed
                next.weekRemindersDenied = self.uiState.weekRemindersDenied
                next.appVersion = self.appVersion
                next.chefSupport = self.uiState.chefSupport
                next.unlockBusy = self.uiState.unlockBusy
                next.unlockNotice = self.uiState.unlockNotice
                next.autoBackup = self.uiState.autoBackup
                next.paprikaFiling = self.uiState.paprikaFiling
                self.uiState = next
            }
        if let autoBackup {
            autoBackupSubscription = autoBackup.state
                .sink { [weak self] state in
                    guard let self else { return }
                    uiState.autoBackup = .of(state, now: self.clock.now(), running: uiState.autoBackup?.running ?? false)
                }
            // iCloud may have come or gone while the app was closed.
            autoBackup.refreshDestination()
        }
    }

    /// The automatic copy's switch (#150).
    func onAutoBackupChange(_ enabled: Bool) {
        autoBackup?.setEnabled(enabled)
    }

    /// "Back up now" (#150): a copy at once, even with the automatic copy off. Returns the work
    /// so a test can await it.
    @discardableResult
    func onBackUpNow() -> Task<Void, Never>? {
        guard let autoBackup, uiState.autoBackup?.running == false else { return nil }
        uiState.autoBackup?.running = true
        return Task {
            await autoBackup.run(force: true)
            uiState.autoBackup?.running = false
        }
    }

    private static func uiState(_ settings: AppSettings) -> SettingsUiState {
        SettingsUiState(
            unitSystem: settings.unitSystem,
            convertLiquids: settings.convertLiquids,
            temperatureUnit: settings.temperatureUnit,
            darkWhileCooking: settings.darkWhileCooking,
            expiryReminders: settings.expiryReminders,
            eveningReminder: settings.eveningReminder,
            eveningReminderMinute: settings.eveningReminderMinute,
            tonightReminder: settings.tonightReminder,
            tonightReminderMinute: settings.tonightReminderMinute,
            amountsInSteps: settings.amountsInSteps,
            chefMode: settings.chefMode
        )
    }

    /// The index's rows (#286), in order: Unlimited recipes only with the `freeTier` flag.
    var sections: [SettingsSection] {
        SettingsSection.allCases.filter { ($0 != .unlimited || unlockRow != nil) && ($0 != .week || showsWeekReminders) }
    }

    /// The Week section (#397): only with the `eveningReminders` flag on.
    var showsWeekReminders: Bool { flags?.isOn(.eveningReminders) ?? false }

    /// A Week reminder's switch (#397), like the expiry reminders': on asks for notification
    /// permission first (only here, never on launch); refused, the switch stays off and the page
    /// says why. Returns the work so a test can await it.
    @discardableResult
    func onWeekReminderChange(_ kind: MealReminderKind, _ on: Bool) -> Task<Void, Never>? {
        guard on else {
            setWeekReminder(kind, false)
            return nil
        }
        return Task {
            let granted = await notificationPermission.request()
            setWeekReminder(kind, granted)
            uiState.notificationsAllowed = granted
            uiState.weekRemindersDenied = !granted
        }
    }

    private func setWeekReminder(_ kind: MealReminderKind, _ on: Bool) {
        switch kind {
        case .evening:
            preferences.eveningReminder = on
            uiState.eveningReminder = on
        case .tonight:
            preferences.tonightReminder = on
            uiState.tonightReminder = on
        }
    }

    /// The time of a Week reminder, as minutes since local midnight.
    func onWeekReminderTime(_ kind: MealReminderKind, _ minute: Int) {
        let at = min(max(minute, 0), 24 * 60 - 1)
        switch kind {
        case .evening:
            preferences.eveningReminderMinute = at
            uiState.eveningReminderMinute = at
        case .tonight:
            preferences.tonightReminderMinute = at
            uiState.tonightReminderMinute = at
        }
    }

    /// Looks again whether notifications are allowed, each time the Week page comes to the front:
    /// the cook can change it in the system settings and come back. Returns the work for a test.
    @discardableResult
    func onWeekPageShown() -> Task<Void, Never> {
        Task {
            let allowed = await notificationPermission.allowed()
            uiState.notificationsAllowed = allowed
            if allowed { uiState.weekRemindersDenied = false }
        }
    }

    /// The "Import from Paprika" row (#382): only with the `paprikaImport` flag on, and an importer.
    var showsPaprikaImport: Bool { paprika != nil && (flags?.isOn(.paprikaImport) ?? false) }

    /// The "Import from Recipe Keeper" row (#390): only with the `recipeKeeperImport` flag on, and an importer.
    var showsRecipeKeeperImport: Bool { paprika != nil && (flags?.isOn(.recipeKeeperImport) ?? false) }

    /// The switch under the import rows (#387, #390, #391): whether the next import files recipes by their categories.
    func onPaprikaFilingChange(_ enabled: Bool) { uiState.paprikaFiling = enabled }

    /// Import from the Paprika export the user picked in the file importer (#382).
    @discardableResult
    func onPaprikaPicked(_ url: URL) -> Task<Void, Never>? {
        guard let paprika, !uiState.backup.isBusy else { return nil }
        uiState.backup = .importingPaprika
        let filing = uiState.paprikaFiling
        return Task {
            switch await paprika.importPaprika(url, filing: filing) {
            case .success(let summary): uiState.backup = .importedPaprika(summary)
            case .failure(let error): uiState.backup = .paprikaFailed(error)
            }
        }
    }

    /// Import from the Recipe Keeper export the user picked in the file importer (#390).
    @discardableResult
    func onRecipeKeeperPicked(_ url: URL) -> Task<Void, Never>? {
        guard let paprika, !uiState.backup.isBusy else { return nil }
        uiState.backup = .importingRecipeKeeper
        let filing = uiState.paprikaFiling
        return Task {
            switch await paprika.importPaprika(url, filing: filing, source: .recipeKeeper) {
            case .success(let summary): uiState.backup = .importedRecipeKeeper(summary)
            case .failure(let error): uiState.backup = .recipeKeeperFailed(error)
            }
        }
    }

    /// The "Import from Mealie" row (#391): only with the `mealieImport` flag on, and an importer.
    var showsMealieImport: Bool { mealie != nil && (flags?.isOn(.mealieImport) ?? false) }

    /// Import from the Mealie file the user picked in the file importer (#391).
    @discardableResult
    func onMealiePicked(_ url: URL) -> Task<Void, Never>? {
        guard let mealie, !uiState.backup.isBusy else { return nil }
        uiState.backup = .importingMealie
        let filing = uiState.paprikaFiling
        return Task {
            switch await mealie.importMealie(url, filing: filing) {
            case .success(let summary): uiState.backup = .importedMealie(summary)
            case .failure(let error): uiState.backup = .mealieFailed(error)
            }
        }
    }

    /// The Steps section's "Chef mode" (#100): only with the `chefMode` flag on.
    var showsChefMode: Bool { flags?.isOn(.chefMode) ?? false }

    func onAmountsInStepsChange(_ enabled: Bool) {
        preferences.amountsInSteps = enabled
        uiState.amountsInSteps = enabled
    }

    /// Asks the phone what Chef mode can do, once, when its row first shows, so the model is
    /// never woken otherwise. Returns the work so a test can await it.
    @discardableResult
    func onStepsShown() -> Task<Void, Never>? {
        guard showsChefMode, uiState.chefSupport == nil, !askedChefSupport else { return nil }
        askedChefSupport = true
        return Task { [weak self, shortSteps] in
            let support = await shortSteps?.support() ?? .unsupported
            self?.uiState.chefSupport = support
        }
    }

    /// The Chef mode switch: only turns on where the phone can write short steps.
    func onChefModeChange(_ enabled: Bool) {
        if enabled && !(uiState.chefSupport?.isAvailable ?? false) { return }
        preferences.chefMode = enabled
        uiState.chefMode = enabled
    }

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
                if let url = await files.writeExport(json: exported.json, exportedAt: exported.exportedAt, photos: exported.photos) {
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
            uiState.backup = BackupStatus(await backups.importFile(url, files: files))
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
