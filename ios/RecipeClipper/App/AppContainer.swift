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
    let appInfo: AppInfo
    /// Session drafts for "Clip it yourself" (#37): one store for the app's lifetime.
    let clipDrafts = ClipDraftStore()
    /// A fixed page "Clip it yourself" shows instead of the live one. UI tests only.
    let clipFixtureHTML: String?

    init(
        recipeRepository: RecipeRepository,
        listRepository: ListRepository,
        preferences: AppPreferences,
        clock: Clock,
        connectivity: Connectivity = StaticConnectivity(),
        appInfo: AppInfo = BundleAppInfo(),
        clipFixtureHTML: String? = nil
    ) {
        self.recipeRepository = recipeRepository
        self.listRepository = listRepository
        self.preferences = preferences
        self.clock = clock
        self.connectivity = connectivity
        self.appInfo = appInfo
        self.clipFixtureHTML = clipFixtureHTML
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
            clock: clock, connectivity: connectivity, appInfo: appInfo
        )
    }

    func makeClipViewModel(url: String) -> ClipViewModel {
        ClipViewModel(url: url, repository: recipeRepository, drafts: clipDrafts)
    }

    func makeSaveToListViewModel() -> SaveToListViewModel {
        SaveToListViewModel(repository: listRepository)
    }

    func makeSettingsViewModel() -> SettingsViewModel {
        SettingsViewModel(preferences: preferences)
    }

    func makeListsViewModel() -> ListsViewModel {
        ListsViewModel(repository: listRepository)
    }

    func makeListDetailViewModel(listId: Int64) -> ListDetailViewModel {
        ListDetailViewModel(listId: listId, repository: listRepository)
    }
}
