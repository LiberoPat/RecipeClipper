import Combine
import Foundation
import Observation

/// One recipe screen, opened either by id (history, home, a list) or by URL (the share
/// target). It follows `AppPreferences.settings`, so a default changed in Settings while the
/// recipe is open re-renders it in place.
@MainActor
@Observable
final class RecipeViewModel {
    static let tick = Duration.milliseconds(250)
    /// How long typing must pause before the note is written. Android's NOTES_SAVE_DELAY_MS.
    static let notesSaveDelay = Duration.milliseconds(500)

    private(set) var uiState: RecipeUiState

    @ObservationIgnored private let recipeId: Int64?
    @ObservationIgnored private let shareUrl: String?
    @ObservationIgnored private let repository: RecipeRepository
    @ObservationIgnored private let preferences: AppPreferences
    @ObservationIgnored private let clock: Clock
    @ObservationIgnored private let sleep: Sleep
    @ObservationIgnored private let connectivity: Connectivity
    @ObservationIgnored private let appInfo: AppInfo
    @ObservationIgnored private let alarms: TimerAlarmScheduler
    // Opened from a timer notification: start cook mode once the recipe has loaded.
    @ObservationIgnored private var openInCookMode: Bool
    // Opened from the Week (#49): show the planned servings rather than the saved choice. For
    // this visit only; it isn't saved unless the cook changes the servings here.
    @ObservationIgnored private var plannedServings: Int?
    // The last queued cook-progress or servings write. Each waits for the one before, so two
    // quick taps can never land out of order. They hold the repository, not the ViewModel, so a
    // write queued just before the screen is popped still lands.
    @ObservationIgnored private var lastWrite: Task<Void, Never>?

    @ObservationIgnored private var loadTask: Task<Void, Never>?
    // Waits for offline -> online while an offline / fetch-failed error is showing.
    @ObservationIgnored private var reconnectTask: Task<Void, Never>?
    // Step index -> wall-clock time (ms) its timer ends. Only running timers are in here.
    @ObservationIgnored private var deadlines: [Int: Int64] = [:]
    @ObservationIgnored private var tickTask: Task<Void, Never>?
    // The note as typed but not yet written (with its recipe), and the debounced write.
    @ObservationIgnored private var pendingNotes: (id: Int64, text: String)?
    @ObservationIgnored private var notesTask: Task<Void, Never>?
    @ObservationIgnored private var settingsSubscription: AnyCancellable?

    // Chef mode (#100): on when both its flag and its setting are.
    @ObservationIgnored private let shortSteps: ShortStepRepository?
    @ObservationIgnored private let flags: FeatureFlags?
    @ObservationIgnored private var chefOn = false
    @ObservationIgnored private var chefTask: Task<Void, Never>?
    @ObservationIgnored private var chefSubscription: AnyCancellable?
    /// The loaded recipe's short steps as saved, before rendering; empty while Chef mode is off.
    @ObservationIgnored private var rawShortSteps: [String?] = []

    init(
        recipeId: Int64?,
        url: String?,
        repository: RecipeRepository,
        preferences: AppPreferences,
        clock: Clock,
        sleep: @escaping Sleep = Sleeps.real,
        connectivity: Connectivity = StaticConnectivity(),
        appInfo: AppInfo = StaticAppInfo(),
        alarms: TimerAlarmScheduler = NoOpTimerAlarmScheduler(),
        openInCookMode: Bool = false,
        plannedServings: Int? = nil,
        shortSteps: ShortStepRepository? = nil,
        flags: FeatureFlags? = nil
    ) {
        self.shortSteps = shortSteps
        self.flags = flags
        self.recipeId = recipeId.flatMap { $0 > 0 ? $0 : nil }
        self.shareUrl = url.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        self.repository = repository
        self.preferences = preferences
        self.clock = clock
        self.sleep = sleep
        self.connectivity = connectivity
        self.appInfo = appInfo
        self.alarms = alarms
        self.openInCookMode = openInCookMode
        self.plannedServings = plannedServings.flatMap { $0 > 0 ? $0 : nil }
        // Seeded synchronously so the first render already uses the user's units.
        let settings = preferences.current
        uiState = RecipeUiState(
            unitSystem: settings.unitSystem,
            convertLiquids: settings.convertLiquids,
            temperatureUnit: settings.temperatureUnit,
            darkWhileCooking: settings.darkWhileCooking
        )
        chefOn = (flags?.isOn(.chefMode) ?? false) && settings.chefMode
        // Settings can change a default while this screen is alive underneath it; this keeps
        // the open recipe in step instead of showing the units it was opened with (#24).
        settingsSubscription = preferences.settings
            .receive(on: DispatchQueue.main)
            .sink { [weak self] settings in self?.applySettings(settings) }
        load()
    }

    /// Android's onCleared: a popped screen stops its import and its tick loop. Neither task
    /// holds the ViewModel strongly, so popping the screen really does let it go.
    deinit {
        loadTask?.cancel()
        tickTask?.cancel()
        reconnectTask?.cancel()
        notesTask?.cancel()
        chefTask?.cancel()
        // A note typed just before leaving is still written: one short write, owned by no one.
        if let pending = pendingNotes {
            Task { [repository] in await repository.setNotes(id: pending.id, notes: pending.text) }
        }
    }

    // MARK: Loading

    func onRetry() { load() }

    private func load() {
        loadTask?.cancel()
        reconnectTask?.cancel()
        reconnectTask = nil
        uiState.content = .loading
        uiState.reportSiteUrl = nil
        uiState.clipUrl = nil
        uiState.asWrittenSteps = []
        // Weak: an import on a screen that has been popped must not keep the ViewModel alive.
        loadTask = Task { [weak self, recipeId, shareUrl, repository] in
            let result: ParseResult
            if let recipeId {
                if let recipe = await repository.open(id: recipeId) {
                    result = .success(recipe)
                } else {
                    result = .error(.notSaved)
                }
            } else if let shareUrl {
                result = await repository.importFromUrl(shareUrl)
            } else {
                result = .error(.nothingToShow)
            }
            // A retry started meanwhile owns the screen now; this result is stale.
            guard !Task.isCancelled, let self else { return }
            switch result {
            case .success(var recipe):
                if let planned = plannedServings {
                    plannedServings = nil
                    recipe.servingsTarget = planned
                }
                uiState.content = .success(successContent(recipe))
                uiState.checkedIngredients = recipe.checkedIngredients
                uiState.notes = recipe.notes ?? ""
                restoreCook(recipe)
                startChef()
                if openInCookMode {
                    openInCookMode = false
                    onCookStart()
                }
            case .error(let error):
                uiState.content = .error(error)
                uiState.reportSiteUrl = reportSiteUrl(for: error)
                uiState.clipUrl = error == .noRecipeFound ? shareUrl : nil
                if error.reloadsOnReconnect { reloadOnReconnect() }
            }
        }
    }

    /// Only a shared link that loaded but held no recipe is worth reporting: a block, being
    /// offline or a failed fetch usually lifts on its own, and a saved recipe has no page to
    /// report.
    private func reportSiteUrl(for error: ParseError) -> String? {
        guard error == .noRecipeFound, let shareUrl else { return nil }
        return SiteReportLink.issueUrl(link: shareUrl, platform: appInfo.platform, appVersion: appInfo.appVersion)
    }

    /// Picks up where the cook left off, even if the app was closed or killed meanwhile (Android's
    /// `restoreCook`). A timer that was running resumes from its saved deadline; one whose
    /// deadline passed shows as finished and already alerted (its notification announced it).
    /// The recipe's pending alerts are replaced by its running timers', which also clears any
    /// left over from a re-share that changed the steps. Indexes past the last step are dropped.
    private func restoreCook(_ recipe: Recipe) {
        deadlines = [:]
        let saved = recipe.cook
        let count = recipe.instructions.count
        let now = clock.now()
        var timers: [Int: StepTimer] = [:]
        var running: [StepAlarm] = []
        for (step, timer) in saved.timers where step >= 0 && step < count {
            if let endsAt = timer.endsAt, endsAt > now {
                deadlines[step] = endsAt
                running.append(StepAlarm(recipeId: recipe.id, recipeTitle: recipe.name, step: step, endsAt: endsAt))
                timers[step] = StepTimer(
                    totalSeconds: timer.totalSeconds, remainingSeconds: Self.secondsUntil(endsAt, now), running: true
                )
            } else if timer.endsAt != nil || timer.remainingSeconds == 0 {
                timers[step] = StepTimer(totalSeconds: timer.totalSeconds, remainingSeconds: 0, running: false, alerted: true)
            } else {
                timers[step] = StepTimer(totalSeconds: timer.totalSeconds, remainingSeconds: timer.remainingSeconds, running: false)
            }
        }
        alarms.replaceAll(recipeId: recipe.id, with: running.sorted { $0.step < $1.step })
        uiState.cook = CookState(
            active: saved.active && count > 0,
            currentStep: count > 0 ? min(max(saved.currentStep, 0), count - 1) : 0,
            doneSteps: saved.doneSteps.filter { $0 >= 0 && $0 < count },
            timers: timers
        )
        if !deadlines.isEmpty { ensureTicking() }
    }

    /// While an offline or fetch-failed error is on screen, waits for the connection to go from
    /// offline to online and then loads again, once. Already online is not a transition: a
    /// fetch failure while connected waits for a drop and a return, never reloading in a loop.
    /// Any new load (including Try again) cancels it.
    private func reloadOnReconnect() {
        reconnectTask = Task { [weak self, connectivity] in
            let reconnected = await connectivity.waitForReconnect()
            guard reconnected, !Task.isCancelled, let self else { return }
            load()
        }
    }

    // MARK: Reading view

    func onIngredientChecked(_ index: Int, _ checked: Bool) {
        var next = uiState.checkedIngredients
        if checked { next.insert(index) } else { next.remove(index) }
        uiState.checkedIngredients = next
        // Saved as they change, so closing the app mid-cook doesn't lose the ticks.
        guard let id = uiState.content.success?.recipe.id else { return }
        Task { [repository] in await repository.setChecked(id: id, checked: next) }
    }

    /// The user's note, edited in place. The screen shows every keystroke at once; the write
    /// waits until typing pauses for `notesSaveDelay`, so a sentence is one write rather than
    /// one per letter. Leaving the screen before then still saves it (see `deinit`).
    func onNotesChange(_ text: String) {
        uiState.notes = text
        guard let id = uiState.content.success?.recipe.id else { return }
        pendingNotes = (id, text)
        notesTask?.cancel()
        // Weak across the sleep, so a popped screen still lets its ViewModel (and deinit) go.
        notesTask = Task { [weak self, sleep] in
            do { try await sleep(Self.notesSaveDelay) } catch { return }
            await self?.flushNotes()
        }
    }

    private func flushNotes() async {
        guard let pending = pendingNotes else { return }
        pendingNotes = nil
        await repository.setNotes(id: pending.id, notes: pending.text)
    }

    /// The recipe as currently on screen — scaled servings, converted units — formatted for
    /// sharing. Nil when nothing is loaded. Presenting the share sheet is the view's job. The
    /// view passes `labels` from the string catalog, so the words follow the phone's language.
    func shareText(labels: RecipeShareText.Labels = .english) -> String? {
        guard let content = uiState.content.success else { return nil }
        return RecipeShareText.format(
            recipe: content.recipe,
            servings: content.servings,
            ingredients: content.ingredients,
            instructions: content.instructions,
            labels: labels
        )
    }

    /// Deletes the recipe outright, list memberships included, then sets `deleted` so the
    /// screen can pop. No undo here: the screen showing it is already gone by then.
    func onDelete() {
        guard let id = uiState.content.success?.recipe.id else { return }
        // A deleted recipe's running timers must not ring later.
        for step in deadlines.keys { alarms.cancel(recipeId: id, step: step) }
        deadlines = [:]
        Task {
            _ = await repository.delete(id: id)
            uiState.deleted = true
        }
    }

    /// "Update from source" (#29), confirmed in the view first: replaces the user's version with
    /// the site's. The recipe stays on screen meanwhile; on success it is shown afresh, on
    /// failure it is kept as it was and `updateError` says why.
    func onUpdateFromSource() {
        guard let recipe = uiState.content.success?.recipe, recipe.canUpdateFromSource,
              !uiState.updatingFromSource else { return }
        uiState.updatingFromSource = true
        uiState.updateError = nil
        Task { [weak self, repository] in
            let result = await repository.updateFromSource(id: recipe.id)
            guard let self else { return }
            uiState.updatingFromSource = false
            switch result {
            case .success(let fresh):
                // Steps may have changed: drop this screen's alarms; restoreCook reschedules
                // whatever the saved progress still holds.
                for step in deadlines.keys { alarms.cancel(recipeId: recipe.id, step: step) }
                uiState.content = .success(successContent(fresh))
                uiState.checkedIngredients = fresh.checkedIngredients
                uiState.asWrittenSteps = []
                restoreCook(fresh)
                startChef()
            case .error(let error):
                uiState.updateError = error
            }
        }
    }

    /// The view has shown `updateError`.
    func onUpdateErrorShown() { uiState.updateError = nil }

    func onServingsChange(_ target: Int) {
        guard var content = uiState.content.success, let servings = content.servings else { return }
        let scale = ServingsScale(base: servings.base, target: min(max(target, 1), Servings.max))
        content.servings = scale
        content.ingredients = render(content.recipe, content.words, scale, uiState.unitSystem, uiState.convertLiquids)
        uiState.content = .success(content)
        // The recipe's own yield is saved as no choice at all.
        let id = content.recipe.id
        let saved: Int? = scale.target == scale.base ? nil : scale.target
        save { await $0.setServingsTarget(id: id, target: saved) }
    }

    /// The units dropdown on this screen. It is a global default, so it writes through; the
    /// state updates at once too, rather than waiting for `preferences.settings` to echo it.
    /// The other preferences are set only in Settings and reach this screen through
    /// `applySettings`.
    func onUnitSystemChange(_ system: UnitSystem) {
        preferences.unitSystem = system
        uiState.unitSystem = system
        rerender()
    }

    /// A change to the global defaults, from Settings or from this screen's own dropdown,
    /// arriving while the recipe is open. Only a change that affects the text re-renders it:
    /// darkWhileCooking is a display choice and leaves the recipe alone, and scaled servings,
    /// ticks and cook progress are kept either way.
    private func applySettings(_ settings: AppSettings) {
        let chef = (flags?.isOn(.chefMode) ?? false) && settings.chefMode
        if chef != chefOn {
            chefOn = chef
            startChef()
        }
        let rendersDifferently = settings.unitSystem != uiState.unitSystem
            || settings.convertLiquids != uiState.convertLiquids
            || settings.temperatureUnit != uiState.temperatureUnit
        var state = uiState
        state.unitSystem = settings.unitSystem
        state.convertLiquids = settings.convertLiquids
        state.temperatureUnit = settings.temperatureUnit
        state.darkWhileCooking = settings.darkWhileCooking
        // Assigned only when something changed, so an echo of our own write notifies no view.
        guard state != uiState else { return }
        uiState = state
        if rendersDifferently { rerender() }
    }

    private func rerender() {
        guard var content = uiState.content.success else { return }
        content.ingredients = render(content.recipe, content.words, content.servings, uiState.unitSystem, uiState.convertLiquids)
        content.instructions = renderInstructions(content.recipe, content.words, uiState.temperatureUnit)
        uiState.content = .success(content)
        applyShortSteps()
    }

    // MARK: Chef mode (#100): short steps written on the device

    /// (Re)starts the loaded recipe's short steps: the saved ones show at once, and the missing
    /// ones are written one by one, the steps showing as written meanwhile. Nothing happens on a
    /// phone or recipe language the model can't do; Chef mode off clears them.
    private func startChef() {
        chefTask?.cancel()
        chefTask = nil
        chefSubscription = nil
        rawShortSteps = []
        applyShortSteps()
        guard chefOn, let shortSteps, let recipe = uiState.content.success?.recipe else { return }
        let language = LanguageWords.forRecipe(recipe)?.language
        // Weak, and self is never held across the writing, so a popped screen goes at once.
        chefTask = Task { [weak self] in
            let support = await shortSteps.support()
            guard !Task.isCancelled, support.covers(language) else { return }
            self?.chefSubscription = shortSteps.observe(recipe)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] shorts in
                    self?.rawShortSteps = shorts
                    self?.applyShortSteps()
                }
            await shortSteps.fill(recipe)
        }
    }

    /// The saved short steps, rendered like the steps they stand for.
    private func applyShortSteps() {
        guard var content = uiState.content.success else { return }
        let shorts = rawShortSteps.count == content.recipe.instructions.count ? rawShortSteps : []
        let unit = uiState.temperatureUnit
        let rendered = shorts.map { $0.map { renderStep($0, content.words, unit) } }
        guard rendered != content.shortInstructions else { return }
        content.shortInstructions = rendered
        uiState.content = .success(content)
    }

    /// Chef mode: shows step `index` as written, or short again.
    func onStepAsWrittenToggle(_ index: Int) {
        if uiState.asWrittenSteps.contains(index) {
            uiState.asWrittenSteps.remove(index)
        } else {
            uiState.asWrittenSteps.insert(index)
        }
    }

    // MARK: Cook mode

    func onCookStart() {
        guard let content = uiState.content.success else { return }
        let count = content.instructions.count
        guard count > 0 else { return }
        // A finished run starts fresh; anything else resumes where it was left.
        var cook = uiState.cook
        if cook.doneSteps.count >= count {
            // Its timers go with it, so their alerts must too.
            for step in deadlines.keys { alarms.cancel(recipeId: content.recipe.id, step: step) }
            deadlines = [:]
            cook = CookState()
        }
        cook.active = true
        cook.currentStep = min(max(cook.currentStep, 0), count - 1)
        uiState.cook = cook
        saveCook()
    }

    func onCookExit() {
        uiState.cook.active = false
        saveCook()
    }

    func onIngredientsToggle() { uiState.cook.ingredientsExpanded.toggle() }

    /// Tapping a step makes it current. Only `onStepDone` ever advances or marks progress.
    func onStepSelected(_ index: Int) {
        uiState.cook.currentStep = index
        saveCook()
    }

    func onStepDone() {
        guard let content = uiState.content.success else { return }
        var cook = uiState.cook
        let count = content.instructions.count
        cook.doneSteps.insert(cook.currentStep)
        // Next unfinished step after this one, else the earliest one skipped, else finished.
        let done = cook.doneSteps
        let next = (cook.currentStep + 1 ..< max(count, cook.currentStep + 1)).first { !done.contains($0) }
            ?? (0 ..< count).first { !done.contains($0) }
        if let next {
            cook.currentStep = next
        } else {
            cook.active = false
        }
        uiState.cook = cook
        saveCook()
    }

    /// Saves the cook's place as it now stands (Android's `saveCook`). A running timer is saved
    /// by its deadline, so ticks never write and a closed app's timer keeps counting.
    /// `ingredientsExpanded` and `alerted` are screen state and aren't saved.
    private func saveCook() {
        guard let id = uiState.content.success?.recipe.id else { return }
        let cook = uiState.cook
        var timers: [Int: SavedTimer] = [:]
        for (step, timer) in cook.timers {
            timers[step] = SavedTimer(
                totalSeconds: timer.totalSeconds, remainingSeconds: timer.remainingSeconds, endsAt: deadlines[step]
            )
        }
        let progress = CookProgress(
            active: cook.active, currentStep: cook.currentStep, doneSteps: cook.doneSteps, timers: timers
        )
        save { await $0.setCookProgress(id: id, progress: progress) }
    }

    private func save(_ write: @escaping (RecipeRepository) async -> Void) {
        let previous = lastWrite
        lastWrite = Task { [repository] in
            await previous?.value
            await write(repository)
        }
    }

    /// Waits for every queued cook-progress and servings write. For tests.
    func settleWrites() async { await lastWrite?.value }

    // MARK: Step timers. Several can run at once, since steps overlap.

    func onTimerStart(_ step: Int) {
        guard let content = uiState.content.success,
              step >= 0, step < content.stepTimerSeconds.count,
              let total = content.stepTimerSeconds[step] else { return }
        let endsAt = clock.now() + Int64(total) * 1000
        deadlines[step] = endsAt
        uiState.cook.timers[step] = StepTimer(totalSeconds: total, remainingSeconds: total, running: true)
        alarms.schedule(StepAlarm(recipeId: content.recipe.id, recipeTitle: content.recipe.name, step: step, endsAt: endsAt))
        ensureTicking()
        saveCook()
    }

    func onTimerToggle(_ step: Int) {
        guard var timer = uiState.cook.timers[step], let recipe = uiState.content.success?.recipe else { return }
        if timer.running {
            deadlines[step] = nil
            timer.running = false
            uiState.cook.timers[step] = timer
            alarms.cancel(recipeId: recipe.id, step: step)
        } else if timer.remainingSeconds > 0 {
            let endsAt = clock.now() + Int64(timer.remainingSeconds) * 1000
            deadlines[step] = endsAt
            timer.running = true
            uiState.cook.timers[step] = timer
            alarms.schedule(StepAlarm(recipeId: recipe.id, recipeTitle: recipe.name, step: step, endsAt: endsAt))
            ensureTicking()
        } else {
            return
        }
        saveCook()
    }

    func onTimerReset(_ step: Int) {
        guard let timer = uiState.cook.timers[step] else { return }
        deadlines[step] = nil
        uiState.cook.timers[step] = StepTimer(
            totalSeconds: timer.totalSeconds, remainingSeconds: timer.totalSeconds, running: false
        )
        if let id = uiState.content.success?.recipe.id { alarms.cancel(recipeId: id, step: step) }
        saveCook()
    }

    func onTimerAlerted(_ step: Int) {
        uiState.cook.timers[step]?.alerted = true
    }

    /// One tick loop for every running timer. Remaining time is recomputed from the wall
    /// clock each tick rather than decremented, so a pause in delivery never drifts it. The
    /// loop ends by itself once no timer is running, and `deinit` cancels it.
    private func ensureTicking() {
        guard tickTask == nil else { return }
        // Weak across the sleep, so a screen popped with a timer running lets its ViewModel go.
        tickTask = Task { [weak self, sleep] in
            while self?.hasRunningTimers == true {
                do { try await sleep(Self.tick) } catch { break }
                self?.tickOnce()
            }
            self?.tickTask = nil
        }
    }

    /// Whether the tick loop is alive. For tests: it must not outlive the last running timer.
    var isTicking: Bool { tickTask != nil }

    private var hasRunningTimers: Bool { !deadlines.isEmpty }

    // A timer that reaches zero here keeps its alert: its deadline has passed, so the alert has
    // been delivered or is being delivered, and removing it would only race it. While this
    // recipe is on screen the notification is suppressed (NotificationRouter) and the in-app
    // beep sounds instead.

    private static func secondsUntil(_ end: Int64, _ now: Int64) -> Int {
        max(0, Int((Double(end - now) / 1000).rounded(.up)))
    }

    private func tickOnce() {
        let now = clock.now()
        var timers = uiState.cook.timers
        var changed = false
        for (step, end) in deadlines {
            let seconds = Self.secondsUntil(end, now)
            if seconds == 0 { deadlines[step] = nil }
            guard var timer = timers[step], timer.remainingSeconds != seconds else { continue }
            timer.remainingSeconds = seconds
            timer.running = seconds > 0
            timers[step] = timer
            changed = true
        }
        if changed { uiState.cook.timers = timers }
    }

    // MARK: Turning a recipe into what the screen shows

    private func successContent(_ recipe: Recipe) -> RecipeSuccess {
        // The recipe's language picks the words, never the phone's (#14).
        let words = LanguageWords.forRecipe(recipe)
        let servings = Servings.parse(recipe.yield, words: words).map { base in
            ServingsScale(base: base, target: recipe.servingsTarget.map { min(max($0, 1), Servings.max) } ?? base)
        }
        return RecipeSuccess(
            recipe: recipe,
            servings: servings,
            ingredients: render(recipe, words, servings, uiState.unitSystem, uiState.convertLiquids),
            instructions: renderInstructions(recipe, words, uiState.temperatureUnit),
            stepTimerSeconds: recipe.instructions.map { StepTimers.parse($0, words: words) },
            sourceDomain: SourceDomain.of(recipe.sourceUrl),
            words: words
        )
    }

    // Scale first, then convert, so a converted amount always matches the chosen servings.
    private func render(
        _ recipe: Recipe, _ words: LanguageWords?, _ servings: ServingsScale?, _ system: UnitSystem, _ convertLiquids: Bool
    ) -> [String] {
        let factor = servings.map { Double($0.target) / Double($0.base) } ?? 1.0
        return IngredientRendering.render(recipe.ingredients, factor: factor, system: system, convertLiquids: convertLiquids, words: words)
    }

    // Instructions aren't scaled, but oven temperatures follow the chosen temperature unit —
    // independent of the ingredient unit system.
    private func renderInstructions(_ recipe: Recipe, _ words: LanguageWords?, _ unit: TemperatureUnit) -> [String] {
        recipe.instructions.map { renderStep($0, words, unit) }
    }

    // One step as shown, as written or Chef mode's short version (#100): the same rendering.
    private func renderStep(_ step: String, _ words: LanguageWords?, _ unit: TemperatureUnit) -> String {
        TemperatureConverter.convert(step, unit: unit, words: words)
    }
}
