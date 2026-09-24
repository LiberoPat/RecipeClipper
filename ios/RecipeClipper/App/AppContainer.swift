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

    init(
        recipeRepository: RecipeRepository,
        listRepository: ListRepository,
        backupRepository: BackupRepository,
        preferences: AppPreferences,
        clock: Clock,
        connectivity: Connectivity = StaticConnectivity(),
        backupFiles: BackupFiles = FileBackupFiles()
    ) {
        self.recipeRepository = recipeRepository
        self.listRepository = listRepository
        self.backupRepository = backupRepository
        self.preferences = preferences
        self.clock = clock
        self.connectivity = connectivity
        self.backupFiles = backupFiles
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
            recipeRepository: DefaultRecipeRepository(db: database, source: BlogRecipeSource(), clock: clock),
            listRepository: DefaultListRepository(db: database, clock: clock),
            backupRepository: DefaultBackupRepository(db: database, clock: clock),
            preferences: UserDefaultsAppPreferences(defaults: defaults),
            clock: clock,
            connectivity: PathConnectivity()
        )
    }

    func makeHomeViewModel() -> HomeViewModel {
        HomeViewModel(repository: recipeRepository)
    }

    func makeHistoryViewModel() -> HistoryViewModel {
        HistoryViewModel(repository: recipeRepository)
    }

    func makeRecipeViewModel(recipeId: Int64?, url: String?) -> RecipeViewModel {
        RecipeViewModel(
            recipeId: recipeId, url: url, repository: recipeRepository, preferences: preferences,
            clock: clock, connectivity: connectivity
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
