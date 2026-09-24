import Foundation

/// The composition root: the one place concrete types are constructed. Everything else sees
/// only the protocols in Data/Contracts.swift.
@MainActor
final class AppContainer {
    let recipeRepository: RecipeRepository
    let listRepository: ListRepository
    let preferences: AppPreferences
    let clock: Clock
    let connectivity: Connectivity
    let backupRepository: BackupRepository
    let backupFiles: BackupFiles
    let appInfo: AppInfo
    let alarms: TimerAlarmScheduler

    init(
        recipeRepository: RecipeRepository,
        listRepository: ListRepository,
        backupRepository: BackupRepository,
        preferences: AppPreferences,
        clock: Clock,
        connectivity: Connectivity = StaticConnectivity(),
        backupFiles: BackupFiles = FileBackupFiles(),
        appInfo: AppInfo = BundleAppInfo(),
        alarms: TimerAlarmScheduler = NoOpTimerAlarmScheduler()
    ) {
        self.recipeRepository = recipeRepository
        self.listRepository = listRepository
        self.backupRepository = backupRepository
        self.preferences = preferences
        self.clock = clock
        self.connectivity = connectivity
        self.backupFiles = backupFiles
        self.appInfo = appInfo
        self.alarms = alarms
    }

    /// The real graph: SQLite on disk, the blog source, UserDefaults. Under XCTest (the unit
    /// tests are hosted by the app) the database is in memory and preferences are a
    /// throwaway suite, so a test run never touches a real user's data.
    static func live() -> AppContainer {
        #if DEBUG
        if let uiTest = UITestSeeding.makeContainer() { return uiTest }
        #endif
        let testing = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
        let clock = SystemClock()
        let database: AppDatabase
        do {
            database = try AppDatabase(path: testing ? nil : AppDatabase.defaultPath())
        } catch {
            fatalError("Couldn't open the recipe database: \(error)")
        }
        let defaults = testing ? (UserDefaults(suiteName: "RecipeClipperTestHost") ?? .standard) : .standard
        return AppContainer(
            recipeRepository: DefaultRecipeRepository(
                db: database, source: BlogRecipeSource(), clock: clock,
                renderedPages: WebViewRenderedPageSource()
            ),
            listRepository: DefaultListRepository(db: database, clock: clock),
            backupRepository: DefaultBackupRepository(db: database, clock: clock),
            preferences: UserDefaultsAppPreferences(defaults: defaults),
            clock: clock,
            connectivity: PathConnectivity(),
            // Under XCTest nothing is scheduled, so a test run never raises the notification
            // prompt (UI-test seeding above takes the default, which is the same no-op).
            alarms: testing ? NoOpTimerAlarmScheduler() : NotificationTimerScheduler(clock: clock)
        )
    }

    func makeHomeViewModel() -> HomeViewModel {
        HomeViewModel(repository: recipeRepository)
    }

    func makeHistoryViewModel() -> HistoryViewModel {
        HistoryViewModel(repository: recipeRepository)
    }

    func makeRecipeViewModel(recipeId: Int64?, url: String?, openInCookMode: Bool = false) -> RecipeViewModel {
        RecipeViewModel(
            recipeId: recipeId, url: url, repository: recipeRepository, preferences: preferences,
            clock: clock, connectivity: connectivity, appInfo: appInfo, alarms: alarms,
            openInCookMode: openInCookMode
        )
    }

    func makeSaveToListViewModel() -> SaveToListViewModel {
        SaveToListViewModel(repository: listRepository)
    }

    func makeSettingsViewModel() -> SettingsViewModel {
        SettingsViewModel(preferences: preferences, backups: backupRepository, files: backupFiles)
    }

    func makeListsViewModel() -> ListsViewModel {
        ListsViewModel(repository: listRepository)
    }

    func makeListDetailViewModel(listId: Int64) -> ListDetailViewModel {
        ListDetailViewModel(listId: listId, repository: listRepository)
    }
}
