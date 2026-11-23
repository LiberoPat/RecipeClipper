import Foundation
import Observation

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
    /// Import from Paprika (#382); nil under test unless given.
    let paprikaImportRepository: PaprikaImportRepository?
    /// Import from Mealie (#391); nil under test unless given.
    let mealieImportRepository: MealieImportRepository?
    let appInfo: AppInfo
    let alarms: TimerAlarmScheduler
    /// The one path that squares the countdowns outside the app (#309) with the database (#333).
    let timerCountdowns: TimerCountdownSync
    /// Asks to post notifications when expiry reminders are turned on (#52).
    let notificationPermission: NotificationPermission
    /// Keeps the pantry's expiry reminders scheduled (#52); the live app only.
    private(set) var expiryReminders: ExpiryReminderCoordinator?
    /// Keeps the evening and Tonight reminders scheduled (#397); the live app only.
    private(set) var mealReminders: MealReminderCoordinator?
    /// The home-screen widgets' redraws (#309); the live app only.
    private(set) var widgetRefresher: WidgetRefresher?
    /// Where the share extension leaves a Reddit post it was blocked from reading (#213); nil
    /// under test.
    var pendingClip: PendingClip?
    /// Where the share extension leaves images shared in (#226); nil under test.
    var pendingScan: PendingScan?
    /// Where the share extension leaves text for the editor (#306); nil under test.
    var pendingText: PendingText?
    /// Where it leaves a week of recipes for the meal-plan review (#352); nil under test.
    var pendingPlan: PendingPlan?
    /// A recipe written inside a shared page's article, left by the share extension (#435).
    var pendingArticle: PendingArticle?
    /// A scan's pages while they are read and checked (#226), in the App Group container.
    let scanPages = ScanPages.shared()
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
    /// Pro (#413): the one place the Week extras learn whether they are open (the flag, the store, the override).
    let proAccess: ProAccess
    /// "I made this" (#116): the user's own photos; nil (most tests) leaves the section out.
    let cookedPhotoRepository: CookedPhotoRepository?
    /// The automatic backup copy in iCloud Drive (#150); the live app only (nil under XCTest).
    let autoBackup: AutoBackup?
    /// The file that carries recipes and items to someone else (#149); nil (most tests) leaves
    /// "Send as file" out and ignores a file opened with the app.
    let shareFileRepository: ShareFileRepository?
    /// Session drafts for "Clip it yourself" (#37): one store for the app's lifetime.
    let clipDrafts = ClipDraftStore()
    /// A fixed page "Clip it yourself" shows instead of the live one. UI tests only.
    let clipFixtureHTML: String?
    /// The live database, when there is one on disk that another process (the share
    /// extension) can also write to.
    private let sharedDatabase: AppDatabase?
    /// The first-run tour (#151, #190): the sample recipe, and the tooltips every screen reads
    /// from the environment.
    let firstRunTour: FirstRunTour
    let tooltips: TooltipsViewModel
    /// When each recipe's pantry use-up sheet was last settled (#147).
    let useUpLog: UseUpLog
    /// The meal types "Plan my week" last had ticked (#392).
    let planChoices: PlanChoices
    /// Reads a Reddit post's photos on the device (#198).
    let photoTextReader: PhotoTextReader

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
        paprikaImportRepository: PaprikaImportRepository? = nil,
        mealieImportRepository: MealieImportRepository? = nil,
        shareFileRepository: ShareFileRepository? = nil,
        // Unless given, every tooltip is seen: a unit test sees none it didn't ask for.
        tourPreferences: TourPreferences = MemoryTourPreferences(),
        useUpLog: UseUpLog = MemoryUseUpLog(),
        planChoices: PlanChoices = MemoryPlanChoices(),
        photoTextReader: PhotoTextReader = UnavailablePhotoTextReader()
    ) {
        self.photoTextReader = photoTextReader
        self.useUpLog = useUpLog
        self.planChoices = planChoices
        self.shareFileRepository = shareFileRepository
        self.autoBackup = autoBackup
        self.paprikaImportRepository = paprikaImportRepository
        self.mealieImportRepository = mealieImportRepository
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
        timerCountdowns = TimerCountdownSync(repository: recipeRepository, alarms: alarms)
        self.notificationPermission = notificationPermission
        self.clipFixtureHTML = clipFixtureHTML
        self.sharedDatabase = sharedDatabase
        // Unless given a store, overrides last only for this run (unit tests).
        self.featureFlags = featureFlags ?? FeatureFlags(store: MemoryFeatureFlagStore())
        self.shortStepRepository = shortStepRepository
        self.decisionRepository = decisionRepository
        self.entitlements = entitlements ?? UnavailableEntitlements()
        libraryPolicy = LibraryPolicy(flags: self.featureFlags, entitlements: self.entitlements, mirror: libraryMirror)
        proAccess = DefaultProAccess(flags: self.featureFlags, entitlements: self.entitlements)
        firstRunTour = FirstRunTour(preferences: tourPreferences, recipes: recipeRepository)
        tooltips = TooltipsViewModel(preferences: tourPreferences)
    }

    /// Called when the app comes to the foreground. The share extension saves recipes into the
    /// same database from its own process, which this process's observers never hear about, so
    /// every open list re-queries (Home's "Continue cooking" then shows what was just shared).
    func refreshAfterExternalChanges() {
        sharedDatabase?.refreshObservers()
        // A day may have passed: plan the expiry reminders from today again.
        expiryReminders?.reschedule()
        // And the evening and Tonight reminders (#397), whose recipes the extension may have re-shared.
        mealReminders?.refresh()
        // A re-share from the extension may have dropped a cook's progress: its countdowns go (#333).
        Task { await timerCountdowns.reconcile() }
    }

    /// The countdowns outside the app (#309) go when `liveTimers` is turned off (#333), as the
    /// widgets follow their flag above. The live app only.
    func followLiveTimersFlag(initial: Bool = true) {
        _ = withObservationTracking { featureFlags.isOn(.liveTimers) } onChange: { [weak self] in
            Task { @MainActor in self?.followLiveTimersFlag(initial: false) }
        }
        if !initial { Task { await timerCountdowns.reconcile() } }
    }

    /// Starts the expiry reminders (#52) over `scheduler`. The live app only: a test run never
    /// schedules a notification.
    func startExpiryReminders(_ scheduler: ExpiryReminderScheduler) {
        let coordinator = ExpiryReminderCoordinator(
            pantry: pantryRepository, preferences: preferences,
            scheduler: scheduler, clock: clock
        )
        coordinator.start()
        expiryReminders = coordinator
    }

    /// Starts the evening and Tonight reminders (#397) over `scheduler`. The live app only.
    func startMealReminders(_ scheduler: MealReminderScheduler) {
        let coordinator = MealReminderCoordinator(
            mealPlan: mealPlanRepository, recipes: recipeRepository, preferences: preferences,
            flags: featureFlags, scheduler: scheduler, clock: clock
        )
        coordinator.start()
        mealReminders = coordinator
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
        // A recipe's own picture (#386) reaches the image loader as `photo://<name>`, a file in this store.
        ImageLoader.photoDirectory = URL(fileURLWithPath: photoStore.path(""))
        let backupRepository = DefaultBackupRepository(db: database, clock: clock, library: libraryLimit, photos: photoStore)
        let decisions = DefaultDecisionRepository(
            db: database, model: FoundationModelsDecisionModel(), clock: clock,
            isOn: { featureFlags?.isOn(.aiDecisions) ?? false },
            countBracketsOn: { featureFlags?.isOn(.aiCountBrackets) ?? false }
        )
        let container = AppContainer(
            recipeRepository: DefaultRecipeRepository(
                db: database,
                // Routed by host: Reddit links to the Reddit source (#11), the rest to the blog one.
                source: RoutingRecipeSource(
                    blog: BlogRecipeSource(), reddit: RedditRecipeSource(),
                    redditOn: { featureFlags?.isOn(.reddit) ?? true }
                ),
                clock: clock,
                renderedPages: WebViewRenderedPageSource(),
                // Hosts that passed Cloudflare's check lately (#220), rendered first.
                clearedHosts: testing ? NoClearedHosts() : FileClearedHosts(clock: clock),
                library: libraryLimit,
                extractor: FoundationModelsPageRecipeExtractor(),
                extractionOn: { featureFlags?.isOn(.llmExtraction) ?? false },
                photos: photoStore,
                versionsOn: { featureFlags?.isOn(.recipeVersions) ?? false },
                articleOn: { featureFlags?.isOn(.articleRecipes) ?? false },
                tipsOn: { featureFlags?.isOn(.recipeTips) ?? false }
            ),
            listRepository: DefaultListRepository(
                db: database, clock: clock, reorderOn: { featureFlags?.isOn(.reorderLists) ?? true }
            ),
            mealPlanRepository: DefaultMealPlanRepository(db: database, clock: clock),
            groceryRepository: DefaultGroceryRepository(db: database, clock: clock, decisions: decisions),
            pantryRepository: DefaultPantryRepository(db: database, clock: clock),
            backupRepository: backupRepository,
            preferences: preferences,
            clock: clock,
            connectivity: PathConnectivity(),
            // Under XCTest nothing is scheduled, so a test run never raises the notification
            // prompt (UI-test seeding above takes the default, which is the same no-op).
            // Outside XCTest (where featureFlags is nil), each running timer also counts down in
            // a Live Activity (#309).
            alarms: featureFlags.map { flags in
                LiveActivityTimerScheduler(
                    wrapping: NotificationTimerScheduler(clock: clock),
                    display: ActivityKitTimerDisplay(clock: clock),
                    flags: flags
                )
            } ?? NoOpTimerAlarmScheduler(),
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
            paprikaImportRepository: DefaultPaprikaImportRepository(
                files: FilePaprikaFiles(photos: photoStore), db: database, clock: clock, library: libraryLimit, photos: photoStore
            ),
            mealieImportRepository: DefaultMealieImportRepository(
                files: FileMealieFiles(photos: photoStore), db: database, clock: clock, library: libraryLimit, photos: photoStore
            ),
            shareFileRepository: DefaultShareFileRepository(db: database, clock: clock, library: libraryLimit),
            // Under XCTest (the unit tests' host) every tooltip is seen, as for any test container.
            tourPreferences: testing ? MemoryTourPreferences() : preferences,
            useUpLog: testing ? MemoryUseUpLog() : preferences,
            planChoices: testing ? MemoryPlanChoices() : preferences,
            photoTextReader: VisionPhotoTextReader()
        )
        // Files no photo names any more (a delete whose Undo never came, an import's unused
        // copies) go once the process is past them.
        if let photos = container.cookedPhotoRepository { Task { await photos.sweep() } }
        if !testing { container.startExpiryReminders(NotificationExpiryReminderScheduler()) }
        if !testing { container.startMealReminders(NotificationMealReminderScheduler()) }
        if !testing { container.startWidgets(WidgetRefresher(switchStore: DefaultsWidgetsSwitch(defaults: defaults))) }
        if !testing { container.followLiveTimersFlag() }
        storeKit?.start()
        container.libraryPolicy.startMirroring()
        container.startMirroring(.reddit, into: DefaultsRedditSwitch(defaults: defaults))
        if !testing { container.pendingClip = PendingClip(defaults: defaults) }
        // Scans (#226): the extension's copy of the flag, the hand-off, and pages nobody finished
        // with, gone after an hour.
        container.startMirroring(.photoText, into: DefaultsPhotoTextSwitch(defaults: defaults))
        if !testing {
            container.pendingScan = PendingScan(defaults: defaults, pages: container.scanPages)
            let pages = container.scanPages
            Task.detached(priority: .utility) { pages.sweep(olderThan: 60 * 60) }
        }
        // Shared text (#306): the extension's copy of the flag, and the hand-off.
        container.startMirroring(.shareText, into: DefaultsShareTextSwitch(defaults: defaults))
        if !testing { container.pendingText = PendingText(defaults: defaults) }
        // A week of recipes in shared text (#352): the same, for its review.
        container.startMirroring(.mealPlanImport, into: DefaultsMealPlanSwitch(defaults: defaults))
        // Recipe tips (#432): the extension saves a link with its tips kept apart only while this is on.
        container.startMirroring(.recipeTips, into: DefaultsRecipeTipsSwitch(defaults: defaults))
        if !testing { container.pendingPlan = PendingPlan(defaults: defaults) }
        // A recipe written inside an article (#435): the extension's copy of the flag, and the hand-off.
        container.startMirroring(.articleRecipes, into: DefaultsArticleRecipesSwitch(defaults: defaults))
        if !testing { container.pendingArticle = PendingArticle(defaults: defaults) }
        return container
    }

    func makeHomeViewModel() -> HomeViewModel {
        HomeViewModel(
            repository: recipeRepository, backups: backupRepository, files: backupFiles,
            lists: listRepository, clock: clock, pasted: pastedTextRouter,
            mealPlan: mealPlanRepository, cooked: cookedPhotoRepository,
            preferences: preferences, flags: featureFlags
        )
    }

    /// Where pasted text goes (#372), as a share of it would: the flags read as they stand.
    var pastedTextRouter: PastedTextRouter {
        PastedTextRouter(
            shareTextOn: { [featureFlags] in featureFlags.isOn(.shareText) },
            mealPlanOn: { [featureFlags] in featureFlags.isOn(.mealPlanImport) }
        )
    }

    /// A list pasted in the app (#372), waiting for Groceries' "Add this list" sheet to open on it.
    var pendingListText: String?

    /// The waiting list's text, once.
    func takePendingList() -> String? {
        defer { pendingListText = nil }
        return pendingListText
    }

    func makeRecipesViewModel(view: RecipeFilter? = nil) -> RecipesViewModel {
        RecipesViewModel(
            repository: recipeRepository, preferences: preferences, library: libraryPolicy,
            cookedSort: cookedPhotoRepository != nil, countdowns: timerCountdowns,
            view: view, lists: listRepository, pasted: pastedTextRouter, pantry: pantryRepository,
            decisions: decisionRepository
        )
    }

    func makeRecipeViewModel(
        recipeId: Int64?, url: String?, openInCookMode: Bool = false, plannedServings: Int? = nil,
        startInCookMode: Bool = false
    ) -> RecipeViewModel {
        RecipeViewModel(
            recipeId: recipeId, url: url, repository: recipeRepository, preferences: preferences,
            clock: clock, connectivity: connectivity, appInfo: appInfo, alarms: alarms,
            openInCookMode: openInCookMode, plannedServings: plannedServings,
            shortSteps: shortStepRepository, flags: featureFlags, entitlements: entitlements,
            decisions: decisionRepository, countdowns: timerCountdowns,
            cooked: cookedPhotoRepository, startInCookMode: startInCookMode
        )
    }

    func makeWeekViewModel() -> WeekViewModel {
        WeekViewModel(
            plan: mealPlanRepository, recipes: recipeRepository, calendar: planCalendar,
            skipOn: { [featureFlags] in featureFlags.isOn(.skipTonight) },
            groceries: groceryRepository, freshFood: featureFlags.isOn(.freshFood),
            pantry: pantryRepository, decisions: decisionRepository, preferences: preferences,
            pantryNote: featureFlags.isOn(.planPantryNote)
        )
    }

    /// The Pro screen (#413): Settings' Pro row opens it, while Pro adds something.
    func makeProViewModel() -> ProViewModel {
        ProViewModel(pro: proAccess)
    }

    func makeAddToPlanViewModel() -> AddToPlanViewModel {
        AddToPlanViewModel(repository: mealPlanRepository, calendar: planCalendar)
    }

    func makeGroceriesViewModel() -> GroceriesViewModel {
        GroceriesViewModel(
            repository: groceryRepository, pantry: pantryRepository, calendar: planCalendar, decisions: decisionRepository,
            mealPlan: mealPlanRepository, recipes: recipeRepository
        )
    }

    /// Keeps an extension's copy of `flag` current (`DefaultsFlagSwitch`; the `reddit` flag for the
    /// share extension, #11, `photoText`, #226, `shareText`, #306, `widgets` for the widgets, #309):
    /// `sink` takes its value now and after every change, as `LibraryPolicy` does the library limit.
    func startMirroring(_ flag: Flag, into sink: @escaping @MainActor (Bool) -> Void) {
        let on = withObservationTracking { featureFlags.isOn(flag) } onChange: { [weak self] in
            Task { @MainActor in self?.startMirroring(flag, into: sink) }
        }
        sink(on)
    }

    func startMirroring<F>(_ flag: Flag, into mirror: DefaultsFlagSwitch<F>) {
        startMirroring(flag) { mirror.store($0) }
    }

    /// The home-screen widgets (#309): redrawn when the plan, the list or the `widgets` flag
    /// changes, the flag mirrored for the widget extension as above. The live app only.
    func startWidgets(_ refresher: WidgetRefresher) {
        widgetRefresher = refresher
        refresher.start(mealPlan: mealPlanRepository, groceries: groceryRepository, today: planCalendar.today())
        startMirroring(.widgets) { [weak self] on in self?.widgetRefresher?.flag(on: on) }
    }

    /// Makes "Print or save as PDF" and the cookbook's ViewModel (#318); nil with the `print` flag off.
    var makePrintViewModel: (() -> PrintViewModel)? {
        guard featureFlags.isOn(.print) else { return nil }
        let recipes = recipeRepository, preferences = preferences, decisions = decisionRepository
        return { PrintViewModel(recipes: recipes, preferences: preferences, decisions: decisions) }
    }

    /// Makes "Ask an AI"'s ViewModel (#351, #353); nil with the `askAi` flag off.
    var makeAskAiViewModel: (() -> AskAiViewModel)? {
        guard featureFlags.isOn(.askAi) else { return nil }
        let pantry = pantryRepository, lists = listRepository, recipes = recipeRepository
        let preferences = preferences, clock = clock
        let pasted = pastedTextRouter
        let mealPlan = mealPlanRepository, choices = planChoices
        return { [featureFlags] in
            AskAiViewModel(
                pantry: pantry, lists: lists, recipes: recipes, preferences: preferences, clock: clock, pasted: pasted,
                mealPlan: mealPlan, choices: choices, planPlus: { featureFlags.isOn(.planMyWeekPlus) }
            )
        }
    }

    /// Makes "Send as file"'s ViewModel (#149); nil without a share file repository.
    var makeSendFileViewModel: (() -> SendFileViewModel)? {
        guard let share = shareFileRepository else { return nil }
        return { SendFileViewModel(share: share) }
    }

    /// The sheet a file opened with the app shows (#149): one for the app's life, since the file
    /// can arrive over any screen.
    private(set) lazy var receiveFileViewModel: ReceiveFileViewModel? = shareFileRepository.map { share in
        ReceiveFileViewModel(files: backupFiles, share: share)
    }

    func makeReceiveListViewModel() -> ReceiveListViewModel {
        ReceiveListViewModel(groceries: groceryRepository, pantry: pantryRepository, calendar: planCalendar)
    }

    func makeAddToGroceriesViewModel() -> AddToGroceriesViewModel {
        AddToGroceriesViewModel(
            repository: groceryRepository, preferences: preferences, pantry: pantryRepository, decisions: decisionRepository
        )
    }

    func makePantryUseUpViewModel() -> PantryUseUpViewModel {
        PantryUseUpViewModel(
            pantry: pantryRepository, groceries: groceryRepository, log: useUpLog, clock: clock, decisions: decisionRepository
        )
    }

    func makePantryViewModel() -> PantryViewModel {
        PantryViewModel(pantry: pantryRepository, groceries: groceryRepository, calendar: planCalendar)
    }

    func makeUseItUpViewModel() -> UseItUpViewModel {
        let flags = featureFlags
        return UseItUpViewModel(
            pantry: pantryRepository, lists: listRepository, plan: mealPlanRepository, calendar: planCalendar,
            isOn: { flags.isOn(.useItUp) }
        )
    }

    func makeWhatINeedViewModel(weekStart: Int64) -> WhatINeedViewModel {
        WhatINeedViewModel(
            weekStart: weekStart, groceries: groceryRepository, pantry: pantryRepository, preferences: preferences,
            decisions: decisionRepository, calendar: planCalendar, freshFood: featureFlags.isOn(.freshFood)
        )
    }

    func makeMealTypesViewModel() -> MealTypesViewModel {
        MealTypesViewModel(repository: mealPlanRepository)
    }

    func makeClipViewModel(url: String, blocked: Bool = false, check: Bool = false) -> ClipViewModel {
        ClipViewModel(
            url: url, repository: recipeRepository, drafts: clipDrafts, entitlements: entitlements, readBlocked: blocked,
            check: check
        )
    }

    /// The post the share extension left (#213), if it did so within `PendingClip.window`.
    func takePendingClip() -> String? {
        pendingClip?.take(now: clock.now())
    }

    /// The images the share extension left (#226), if it did so within `PendingScan.window`,
    /// and only while the photoText flag is on.
    func takePendingScan() -> [String]? {
        guard let pages = pendingScan?.take(now: clock.now()) else { return nil }
        return featureFlags.isOn(.photoText) ? pages : nil
    }

    /// "Scan a recipe" (#226): the editor over the cook's own pages, read on the device.
    func makeEditRecipeViewModel(scanPages: [String]) -> EditRecipeViewModel {
        EditRecipeViewModel(
            recipeId: nil, repository: recipeRepository, entitlements: entitlements,
            photoReader: photoTextReader, scanPages: scanPages
        )
    }

    /// The reading of the text the share extension left (#306), if it did so within
    /// `PendingText.window`, and only while the shareText flag is on.
    func takePendingText() -> SharedText.Reading? {
        guard let reading = pendingText?.take(now: clock.now()) else { return nil }
        return featureFlags.isOn(.shareText) ? reading : nil
    }

    /// The text of a week of recipes the share extension left (#352), if within
    /// `PendingPlan.window`, and only while the shareText and mealPlanImport flags are on.
    func takePendingPlan() -> String? {
        guard let text = pendingPlan?.take(now: clock.now()) else { return nil }
        return featureFlags.isOn(.shareText) && featureFlags.isOn(.mealPlanImport) ? text : nil
    }

    /// The recipes written inside an article that the share extension left (#435), if it did so
    /// within `PendingArticle.window`, and only while the articleRecipes flag is on.
    func takePendingArticle() -> [ArticleRecipe]? {
        guard let articles = pendingArticle?.take(now: clock.now()), !articles.isEmpty else { return nil }
        return featureFlags.isOn(.articleRecipes) ? articles : nil
    }

    /// The chooser over several recipes written inside one article (#435).
    func makeArticleChoiceViewModel(articles: [ArticleRecipe]) -> ArticleChoiceViewModel {
        ArticleChoiceViewModel(articles: articles, repository: recipeRepository)
    }

    /// The editor over a recipe written inside an article (#435), to check.
    func makeEditRecipeViewModel(article: ArticleRecipe) -> EditRecipeViewModel {
        EditRecipeViewModel(recipeId: nil, repository: recipeRepository, entitlements: entitlements, article: article)
    }

    /// The meal-plan review (#352) over the recipes `text` holds.
    func makeMealPlanReviewViewModel(text: String) -> MealPlanReviewViewModel {
        MealPlanReviewViewModel(
            text: text, recipes: recipeRepository, plan: mealPlanRepository, calendar: planCalendar,
            limit: { [libraryPolicy] in libraryPolicy.limit }, entitlements: entitlements,
            freshFood: featureFlags.isOn(.freshFood), planPlus: featureFlags.isOn(.planMyWeekPlus),
            tipsOn: featureFlags.isOn(.recipeTips)
        )
    }

    /// Text shared in (#306): the editor over its reading, split or as written.
    func makeEditRecipeViewModel(shared: SharedText.Reading) -> EditRecipeViewModel {
        EditRecipeViewModel(
            recipeId: nil, repository: recipeRepository, entitlements: entitlements, shared: shared,
            tipsOn: featureFlags.isOn(.recipeTips)
        )
    }

    func makeEditRecipeViewModel(recipeId: Int64?) -> EditRecipeViewModel {
        EditRecipeViewModel(
            recipeId: recipeId, repository: recipeRepository, entitlements: entitlements, countdowns: timerCountdowns
        )
    }

    /// "Read the photo" (#198): the editor over a Reddit post's photos, read on the device.
    func makeEditRecipeViewModel(photo: PhotoPost) -> EditRecipeViewModel {
        EditRecipeViewModel(
            recipeId: nil, repository: recipeRepository, entitlements: entitlements,
            photo: photo, photoReader: photoTextReader
        )
    }

    /// "Your cooks" (#116): only behind the `cookedPhotos` flag, and only with a repository.
    var makeCookedPhotosViewModel: (() -> CookedPhotosViewModel)? {
        guard let photos = cookedPhotoRepository else { return nil }
        return { CookedPhotosViewModel(repository: photos) }
    }

    func makeSaveToListViewModel() -> SaveToListViewModel {
        SaveToListViewModel(repository: listRepository)
    }

    func makeSettingsViewModel() -> SettingsViewModel {
        SettingsViewModel(
            preferences: preferences, backups: backupRepository, files: backupFiles, appVersion: appInfo.appVersion,
            flags: featureFlags, notificationPermission: notificationPermission,
            shortSteps: shortStepRepository, entitlements: entitlements, autoBackup: autoBackup, clock: clock,
            paprika: paprikaImportRepository, mealie: mealieImportRepository, pro: proAccess
        )
    }

    func makeDeveloperSettingsViewModel() -> DeveloperSettingsViewModel {
        DeveloperSettingsViewModel(flags: featureFlags)
    }

    func makeListsViewModel() -> ListsViewModel {
        ListsViewModel(repository: listRepository)
    }

    func makeListDetailViewModel(listId: Int64) -> ListDetailViewModel {
        ListDetailViewModel(
            listId: listId, repository: listRepository, recipes: recipeRepository, preferences: preferences,
            decisions: decisionRepository, countdowns: timerCountdowns
        )
    }
}
