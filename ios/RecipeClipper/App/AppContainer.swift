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
    /// Chef mode's short steps (#100); nil (tests) leaves Chef mode unsupported.
    let shortStepRepository: ShortStepRepository?
    /// The on-device model's typed decisions (#104); nil (tests) keeps today's rules.
    let decisionRepository: DecisionRepository?
    /// The one-time unlock (#107): StoreKit in the live app, none in tests.
    let entitlements: Entitlements
    /// Which library limit applies (#107), mirrored for the repositories and the extension.
    let libraryPolicy: LibraryPolicy
    /// "I made this" (#116): the user's own photos; nil (most tests) leaves the section out.
    let cookedPhotoRepository: CookedPhotoRepository?
    /// The automatic backup copy in iCloud Drive (#150); the live app only (nil under XCTest).
    let autoBackup: AutoBackup?
    /// Session drafts for "Clip it yourself" (#37): one store for the app's lifetime.
    let clipDrafts = ClipDraftStore()
    /// A fixed page "Clip it yourself" shows instead of the live one. UI tests only.
    let clipFixtureHTML: String?
    /// The live database, when there is one on disk that another process (the share
    /// extension) can also write to.
    private let sharedDatabase: AppDatabase?
    /// The first-run tour (#151): its rules, and the tips every screen reads from the environment.
    let firstRunTour: FirstRunTour
    let tips: TipsViewModel

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
        notificationPermission: NotificationPermission = FixedNotificationPermission(granted: true),
        shortStepRepository: ShortStepRepository? = nil,
        decisionRepository: DecisionRepository? = nil,
        entitlements: Entitlements? = nil,
        libraryMirror: DefaultsLibraryLimit? = nil,
        cookedPhotoRepository: CookedPhotoRepository? = nil,
        autoBackup: AutoBackup? = nil,
        // Unless given, the tour is done: a unit test sees no welcome or tip it didn't ask for.
        tourPreferences: TourPreferences = MemoryTourPreferences()
    ) {
        self.autoBackup = autoBackup
        self.cookedPhotoRepository = cookedPhotoRepository
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
        self.shortStepRepository = shortStepRepository
        self.decisionRepository = decisionRepository
        self.entitlements = entitlements ?? UnavailableEntitlements()
        libraryPolicy = LibraryPolicy(flags: self.featureFlags, entitlements: self.entitlements, mirror: libraryMirror)
        firstRunTour = FirstRunTour(preferences: tourPreferences, recipes: recipeRepository)
        tips = TipsViewModel(preferences: tourPreferences, flags: self.featureFlags)
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
        let preferences = UserDefaultsAppPreferences(defaults: defaults)
        // The limit (#107) as the share extension reads it too, from the App Group suite.
        let libraryLimit = DefaultsLibraryLimit(defaults: defaults)
        let storeKit = testing ? nil : StoreKitEntitlements()
        let featureFlags = testing ? nil : FeatureFlags(store: UserDefaultsFeatureFlagStore())
        // "I made this" (#116): beside the database, or a throwaway folder under XCTest.
        let photoStore = testing
            ? FilePhotoStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent("TestHostPhotos"))
            : FilePhotoStore(databasePath: AppDatabase.defaultPath())
        let backupRepository = DefaultBackupRepository(db: database, clock: clock, library: libraryLimit, photos: photoStore)
        let decisions = DefaultDecisionRepository(
            db: database, model: FoundationModelsDecisionModel(), clock: clock,
            isOn: { featureFlags?.isOn(.aiDecisions) ?? false },
            countBracketsOn: { featureFlags?.isOn(.aiCountBrackets) ?? false }
        )
        let container = AppContainer(
            recipeRepository: DefaultRecipeRepository(
                db: database, source: BlogRecipeSource(), clock: clock,
                renderedPages: WebViewRenderedPageSource(), library: libraryLimit,
                extractor: FoundationModelsPageRecipeExtractor(),
                extractionOn: { featureFlags?.isOn(.llmExtraction) ?? false },
                photos: photoStore
            ),
            listRepository: DefaultListRepository(db: database, clock: clock),
            mealPlanRepository: DefaultMealPlanRepository(db: database, clock: clock),
            groceryRepository: DefaultGroceryRepository(db: database, clock: clock, decisions: decisions),
            pantryRepository: DefaultPantryRepository(db: database, clock: clock),
            backupRepository: backupRepository,
            preferences: preferences,
            clock: clock,
            connectivity: PathConnectivity(),
            // Under XCTest nothing is scheduled, so a test run never raises the notification
            // prompt (UI-test seeding above takes the default, which is the same no-op).
            alarms: testing ? NoOpTimerAlarmScheduler() : NotificationTimerScheduler(clock: clock),
            sharedDatabase: testing ? nil : database,
            featureFlags: featureFlags,
            notificationPermission: testing ? FixedNotificationPermission(granted: true) : SystemNotificationPermission(),
            shortStepRepository: DefaultShortStepRepository(
                db: database, shortener: FoundationModelsStepShortener(), clock: clock
            ),
            decisionRepository: decisions,
            entitlements: storeKit,
            libraryMirror: libraryLimit,
            cookedPhotoRepository: DefaultCookedPhotoRepository(db: database, store: photoStore, clock: clock),
            // #150: never under XCTest, so a test run never writes to anyone's iCloud Drive.
            autoBackup: testing ? nil : AutoBackup(
                backups: backupRepository, folder: ICloudBackupFolder(),
                store: UserDefaultsAutoBackupStore(defaults: defaults), clock: clock
            ),
            // Under XCTest (the unit tests' host) the tour stays done, as for any test container.
            tourPreferences: testing ? MemoryTourPreferences() : preferences
        )
        // Files no photo names any more (a delete whose Undo never came, an import's unused
        // copies) go once the process is past them.
        if let photos = container.cookedPhotoRepository { Task { await photos.sweep() } }
        if !testing { container.startExpiryReminders(NotificationExpiryReminderScheduler()) }
        storeKit?.start()
        container.libraryPolicy.startMirroring()
        return container
    }

    func makeHomeViewModel() -> HomeViewModel {
        HomeViewModel(repository: recipeRepository, backups: backupRepository, files: backupFiles)
    }

    func makeRecipesViewModel() -> RecipesViewModel {
        RecipesViewModel(
            repository: recipeRepository, preferences: preferences, library: libraryPolicy,
            cookedSort: cookedPhotoRepository != nil && featureFlags.isOn(.cookedPhotos)
        )
    }

    func makeRecipeViewModel(
        recipeId: Int64?, url: String?, openInCookMode: Bool = false, plannedServings: Int? = nil
    ) -> RecipeViewModel {
        RecipeViewModel(
            recipeId: recipeId, url: url, repository: recipeRepository, preferences: preferences,
            clock: clock, connectivity: connectivity, appInfo: appInfo, alarms: alarms,
            openInCookMode: openInCookMode, plannedServings: plannedServings,
            shortSteps: shortStepRepository, flags: featureFlags, entitlements: entitlements,
            decisions: decisionRepository
        )
    }

    func makeWeekViewModel() -> WeekViewModel {
        WeekViewModel(plan: mealPlanRepository, recipes: recipeRepository, calendar: planCalendar)
    }

    func makeAddToPlanViewModel() -> AddToPlanViewModel {
        AddToPlanViewModel(repository: mealPlanRepository, calendar: planCalendar)
    }

    func makeGroceriesViewModel() -> GroceriesViewModel {
        GroceriesViewModel(
            repository: groceryRepository, pantry: pantryRepository, calendar: planCalendar, decisions: decisionRepository
        )
    }

    func makeAddToGroceriesViewModel() -> AddToGroceriesViewModel {
        AddToGroceriesViewModel(
            repository: groceryRepository, preferences: preferences, pantry: pantryRepository, decisions: decisionRepository
        )
    }

    func makePantryViewModel() -> PantryViewModel {
        PantryViewModel(pantry: pantryRepository, groceries: groceryRepository, calendar: planCalendar)
    }

    func makeWhatINeedViewModel(weekStart: Int64) -> WhatINeedViewModel {
        WhatINeedViewModel(
            weekStart: weekStart, groceries: groceryRepository, pantry: pantryRepository, preferences: preferences,
            decisions: decisionRepository
        )
    }

    func makeMealTypesViewModel() -> MealTypesViewModel {
        MealTypesViewModel(repository: mealPlanRepository)
    }

    func makeClipViewModel(url: String) -> ClipViewModel {
        ClipViewModel(url: url, repository: recipeRepository, drafts: clipDrafts, entitlements: entitlements)
    }

    func makeEditRecipeViewModel(recipeId: Int64?) -> EditRecipeViewModel {
        EditRecipeViewModel(recipeId: recipeId, repository: recipeRepository, entitlements: entitlements)
    }

    /// "Your cooks" (#116): only behind the `cookedPhotos` flag, and only with a repository.
    var makeCookedPhotosViewModel: (() -> CookedPhotosViewModel)? {
        guard let photos = cookedPhotoRepository, featureFlags.isOn(.cookedPhotos) else { return nil }
        return { CookedPhotosViewModel(repository: photos) }
    }

    func makeSaveToListViewModel() -> SaveToListViewModel {
        SaveToListViewModel(repository: listRepository)
    }

    func makeSettingsViewModel() -> SettingsViewModel {
        SettingsViewModel(
            preferences: preferences, backups: backupRepository, files: backupFiles, appVersion: appInfo.appVersion,
            flags: featureFlags, notificationPermission: notificationPermission,
            shortSteps: shortStepRepository, entitlements: entitlements, autoBackup: autoBackup, clock: clock
        )
    }

    /// The welcome (#151): at the first plain launch, or from "Show the tour again" (`again`).
    func makeWelcomeViewModel(again: Bool) -> WelcomeViewModel {
        WelcomeViewModel(tour: firstRunTour, flags: featureFlags, again: again)
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
