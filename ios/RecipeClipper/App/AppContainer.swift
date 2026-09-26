import Foundation

/// The composition root: the one place concrete types are constructed. Everything else sees
/// only the protocols in Data/Contracts.swift.
@MainActor
final class AppContainer {
    let recipeRepository: RecipeRepository
    let listRepository: ListRepository
    let mealPlanRepository: MealPlanRepository
    let groceryRepository: GroceryRepository
    let pantryRepository: PantryRepository
    let planCalendar: PlanCalendar
    let preferences: AppPreferences
    let clock: Clock
    let connectivity: Connectivity
    let backupRepository: BackupRepository
    let backupFiles: BackupFiles
    let appInfo: AppInfo
    let alarms: TimerAlarmScheduler
    /// Asks to post notifications when expiry reminders are turned on (#52).
    let notificationPermission: NotificationPermission
    /// Keeps the pantry's expiry reminders scheduled (#52); the live app only.
    private(set) var expiryReminders: ExpiryReminderCoordinator?
    /// The feature flags (#87), read by the root view and Developer settings.
    let featureFlags: FeatureFlags
    /// Session drafts for "Clip it yourself" (#37): one store for the app's lifetime.
    let clipDrafts = ClipDraftStore()
    /// A fixed page "Clip it yourself" shows instead of the live one. UI tests only.
    let clipFixtureHTML: String?
    /// The live database, when there is one on disk that another process (the share
    /// extension) can also write to.
    private let sharedDatabase: AppDatabase?

    init(
        recipeRepository: RecipeRepository,
        listRepository: ListRepository,
        mealPlanRepository: MealPlanRepository,
        groceryRepository: GroceryRepository,
        pantryRepository: PantryRepository,
        backupRepository: BackupRepository,
        preferences: AppPreferences,
        clock: Clock,
        planCalendar: PlanCalendar? = nil,
        connectivity: Connectivity = StaticConnectivity(),
        backupFiles: BackupFiles = FileBackupFiles(),
        appInfo: AppInfo = BundleAppInfo(),
        alarms: TimerAlarmScheduler = NoOpTimerAlarmScheduler(),
        clipFixtureHTML: String? = nil,
        sharedDatabase: AppDatabase? = nil,
        featureFlags: FeatureFlags? = nil,
        notificationPermission: NotificationPermission = FixedNotificationPermission(granted: true)
    ) {
        self.recipeRepository = recipeRepository
        self.listRepository = listRepository
        self.mealPlanRepository = mealPlanRepository
        self.groceryRepository = groceryRepository
        self.pantryRepository = pantryRepository
        self.planCalendar = planCalendar ?? SystemPlanCalendar(clock: clock)
        self.backupRepository = backupRepository
        self.preferences = preferences
        self.clock = clock
        self.connectivity = connectivity
        self.backupFiles = backupFiles
        self.appInfo = appInfo
        self.alarms = alarms
        self.notificationPermission = notificationPermission
        self.clipFixtureHTML = clipFixtureHTML
        self.sharedDatabase = sharedDatabase
        // Unless given a store, overrides last only for this run (unit tests).
        self.featureFlags = featureFlags ?? FeatureFlags(store: MemoryFeatureFlagStore())
    }

    /// Called when the app comes to the foreground. The share extension saves recipes into the
    /// same database from its own process, which this process's observers never hear about, so
    /// every open list re-queries (Home's "Continue cooking" then shows what was just shared).
    func refreshAfterExternalChanges() {
        sharedDatabase?.refreshObservers()
        // A day may have passed: plan the expiry reminders from today again.
        expiryReminders?.reschedule()
    }

    /// Starts the expiry reminders (#52) over `scheduler`. The live app only: a test run never
    /// schedules a notification.
    func startExpiryReminders(_ scheduler: ExpiryReminderScheduler) {
        let coordinator = ExpiryReminderCoordinator(
            pantry: pantryRepository, preferences: preferences, flags: featureFlags,
            scheduler: scheduler, clock: clock
        )
        coordinator.start()
        expiryReminders = coordinator
    }

    /// The real graph: SQLite on disk, the blog source, UserDefaults. The database and the
    /// settings suite are in the App Group container the share extension also writes to. Under
    /// XCTest (the unit tests are hosted by the app) the database is in memory and preferences
    /// are a throwaway suite, so a test run never touches a real user's data.
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
        let defaults = UserDefaults(suiteName: testing ? "RecipeClipperTestHost" : AppGroup.identifier) ?? .standard
        let container = AppContainer(
            recipeRepository: DefaultRecipeRepository(
                db: database, source: BlogRecipeSource(), clock: clock,
                renderedPages: WebViewRenderedPageSource()
            ),
            listRepository: DefaultListRepository(db: database, clock: clock),
            mealPlanRepository: DefaultMealPlanRepository(db: database, clock: clock),
            groceryRepository: DefaultGroceryRepository(db: database, clock: clock),
            pantryRepository: DefaultPantryRepository(db: database, clock: clock),
            backupRepository: DefaultBackupRepository(db: database, clock: clock),
            preferences: UserDefaultsAppPreferences(defaults: defaults),
            clock: clock,
            connectivity: PathConnectivity(),
            // Under XCTest nothing is scheduled, so a test run never raises the notification
            // prompt (UI-test seeding above takes the default, which is the same no-op).
            alarms: testing ? NoOpTimerAlarmScheduler() : NotificationTimerScheduler(clock: clock),
            sharedDatabase: testing ? nil : database,
            featureFlags: testing ? nil : FeatureFlags(store: UserDefaultsFeatureFlagStore()),
            notificationPermission: testing ? FixedNotificationPermission(granted: true) : SystemNotificationPermission()
        )
        if !testing { container.startExpiryReminders(NotificationExpiryReminderScheduler()) }
        return container
    }

    func makeHomeViewModel() -> HomeViewModel {
        HomeViewModel(repository: recipeRepository)
    }

    func makeRecipesViewModel() -> RecipesViewModel {
        RecipesViewModel(repository: recipeRepository, preferences: preferences)
    }

    func makeRecipeViewModel(
        recipeId: Int64?, url: String?, openInCookMode: Bool = false, plannedServings: Int? = nil
    ) -> RecipeViewModel {
        RecipeViewModel(
            recipeId: recipeId, url: url, repository: recipeRepository, preferences: preferences,
            clock: clock, connectivity: connectivity, appInfo: appInfo, alarms: alarms,
            openInCookMode: openInCookMode, plannedServings: plannedServings
        )
    }

    func makeWeekViewModel() -> WeekViewModel {
        WeekViewModel(plan: mealPlanRepository, recipes: recipeRepository, calendar: planCalendar)
    }

    func makeAddToPlanViewModel() -> AddToPlanViewModel {
        AddToPlanViewModel(repository: mealPlanRepository, calendar: planCalendar)
    }

    func makeGroceriesViewModel() -> GroceriesViewModel {
        GroceriesViewModel(repository: groceryRepository, pantry: pantryRepository, calendar: planCalendar)
    }

    func makeAddToGroceriesViewModel() -> AddToGroceriesViewModel {
        AddToGroceriesViewModel(repository: groceryRepository, preferences: preferences, pantry: pantryRepository)
    }

    func makePantryViewModel() -> PantryViewModel {
        PantryViewModel(pantry: pantryRepository, groceries: groceryRepository, calendar: planCalendar)
    }

    func makeWhatINeedViewModel(weekStart: Int64) -> WhatINeedViewModel {
        WhatINeedViewModel(
            weekStart: weekStart, groceries: groceryRepository, pantry: pantryRepository, preferences: preferences
        )
    }

    func makeMealTypesViewModel() -> MealTypesViewModel {
        MealTypesViewModel(repository: mealPlanRepository)
    }

    func makeClipViewModel(url: String) -> ClipViewModel {
        ClipViewModel(url: url, repository: recipeRepository, drafts: clipDrafts)
    }

    func makeEditRecipeViewModel(recipeId: Int64?) -> EditRecipeViewModel {
        EditRecipeViewModel(recipeId: recipeId, repository: recipeRepository)
    }

    func makeSaveToListViewModel() -> SaveToListViewModel {
        SaveToListViewModel(repository: listRepository)
    }

    func makeSettingsViewModel() -> SettingsViewModel {
        SettingsViewModel(
            preferences: preferences, backups: backupRepository, files: backupFiles, appVersion: appInfo.appVersion,
            flags: featureFlags, notificationPermission: notificationPermission
        )
    }

    func makeDeveloperSettingsViewModel() -> DeveloperSettingsViewModel {
        DeveloperSettingsViewModel(flags: featureFlags)
    }

    func makeListsViewModel() -> ListsViewModel {
        ListsViewModel(repository: listRepository)
    }

    func makeListDetailViewModel(listId: Int64) -> ListDetailViewModel {
        ListDetailViewModel(listId: listId, repository: listRepository)
    }
}
