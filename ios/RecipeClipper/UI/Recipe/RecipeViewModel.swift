import Combine
import Foundation
import Observation

/// One recipe screen, opened either by id (history, home, a list) or by URL (the share
/// target). It follows `AppPreferences.settings`, so a default changed in Settings while the
/// recipe is open re-renders it in place.
///
/// The single owner of `uiState` (#169): it loads, then hands each job to a collaborator and
/// writes what comes back. `RecipeRenderer` turns the recipe into what the screen shows,
/// `CookSession` runs cook mode and its timers, and `ChefMode` brings the on-device model's
/// short steps and decisions. This keeps the tick loop, the alert calls and the serialized
/// writes (ticks, notes, cook progress, servings).
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
    @ObservationIgnored private let sleep: Sleep
    @ObservationIgnored private let connectivity: Connectivity
    @ObservationIgnored private let appInfo: AppInfo
    @ObservationIgnored private let alarms: TimerAlarmScheduler
    @ObservationIgnored private let entitlements: Entitlements
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
    // Cook mode's steps and timers; this keeps only the tick loop and the alert calls.
    @ObservationIgnored private var cookSession: CookSession
    @ObservationIgnored private var tickTask: Task<Void, Never>?
    // The note as typed but not yet written (with its recipe), and the debounced write.
    @ObservationIgnored private var pendingNotes: (id: Int64, text: String)?
    @ObservationIgnored private var notesTask: Task<Void, Never>?
    @ObservationIgnored private var settingsSubscription: AnyCancellable?

    // Chef mode (#100) and the model's count brackets (#104), two of the renderer's inputs.
    @ObservationIgnored private let chef: ChefMode

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
        flags: FeatureFlags? = nil,
        entitlements: Entitlements = UnavailableEntitlements(),
        decisions: DecisionRepository? = nil
    ) {
        self.entitlements = entitlements
        self.recipeId = recipeId.flatMap { $0 > 0 ? $0 : nil }
        self.shareUrl = url.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        self.repository = repository
        self.preferences = preferences
        self.cookSession = CookSession(clock: clock)
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
            darkWhileCooking: settings.darkWhileCooking,
            amountsInSteps: settings.amountsInSteps
        )
        chef = ChefMode(shortSteps: shortSteps, flags: flags, decisions: decisions, setting: settings.chefMode)
        chef.keptRecipe = { [weak self] in
            guard let self, !self.uiState.notKept else { return nil }
            return self.uiState.content.success?.recipe
        }
        chef.onShortSteps = { [weak self] in self?.applyShortSteps() }
        chef.onDecisions = { [weak self] in self?.rerender() }
        chef.observeDecisions()
        // Settings can change a default while this screen is alive underneath it; this keeps
        // the open recipe in step instead of showing the units it was opened with (#24).
        settingsSubscription = preferences.settings
            .receive(on: DispatchQueue.main)
            .sink { [weak self] settings in self?.applySettings(settings) }
        load()
    }

    /// Android's onCleared: a popped screen stops its import and its tick loop (and `ChefMode`,
    /// going with it, its writing). No task holds the ViewModel strongly, so popping the screen
    /// really does let it go.
    deinit {
        loadTask?.cancel()
        tickTask?.cancel()
        reconnectTask?.cancel()
        notesTask?.cancel()
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
        uiState.photoPost = nil
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
            var shown = result
            if case .notKept(let recipe) = result {
                uiState.notKept = true
                shown = .success(recipe)
            }
            switch shown {
            case .notKept: break
            case .success(var recipe):
                if let planned = plannedServings {
                    plannedServings = nil
                    recipe.servingsTarget = planned
                }
                uiState.content = .success(RecipeRenderer.content(recipe, settings: renderSettings))
                uiState.checkedIngredients = recipe.checkedIngredients
                uiState.notes = recipe.notes ?? ""
                restoreCook(recipe)
                chef.start()
                askModel()
                if openInCookMode {
                    openInCookMode = false
                    onCookStart()
                }
            case .error(let error):
                uiState.content = .error(error)
                uiState.reportSiteUrl = reportSiteUrl(for: error)
                uiState.clipUrl = error == .noRecipeFound ? shareUrl : nil
                uiState.photoPost = photoPost(for: error)
                if error.reloadsOnReconnect { reloadOnReconnect() }
            }
        }
    }

    /// A shared Reddit post with no recipe text but a picture can have its photo read (#198).
    private func photoPost(for error: ParseError) -> PhotoPost? {
        guard case .noTranscription(let title, _, _) = error, let shareUrl,
              let images = error.photoUrls, !images.isEmpty else { return nil }
        return PhotoPost(url: shareUrl, title: title, imageUrls: images)
    }

    /// Only a shared link that loaded but held no recipe is worth reporting: a block, being
    /// offline or a failed fetch usually lifts on its own, and a saved recipe has no page to
    /// report.
    private func reportSiteUrl(for error: ParseError) -> String? {
        guard error == .noRecipeFound, let shareUrl else { return nil }
        return SiteReportLink.issueUrl(link: shareUrl, platform: appInfo.platform, appVersion: appInfo.appVersion)
    }

    /// Picks up where the cook left off (`CookSession.restore`; Android's `restoreCook`). The
    /// recipe's pending alerts are replaced by its running timers', which also clears any left
    /// over from a re-share that changed the steps.
    private func restoreCook(_ recipe: Recipe) {
        let restored = cookSession.restore(recipe)
        alarms.replaceAll(recipeId: recipe.id, with: restored.alarms)
        uiState.cook = restored.cook
        if cookSession.hasRunningTimers { ensureTicking() }
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
        for step in cookSession.stopTimers() { alarms.cancel(recipeId: id, step: step) }
        Task {
            // No undo here: the confirmation said the photos go with it (#116).
            if let deleted = await repository.delete(id: id) { await repository.forget(deleted) }
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
                for step in cookSession.stopTimers() { alarms.cancel(recipeId: recipe.id, step: step) }
                uiState.content = .success(RecipeRenderer.content(fresh, settings: renderSettings))
                uiState.checkedIngredients = fresh.checkedIngredients
                uiState.asWrittenSteps = []
                restoreCook(fresh)
                chef.start()
                askModel()
            case .error(let error):
                uiState.updateError = error
            case .notKept: break // an update never adds a recipe
            }
        }
    }

    /// The Unlock prompt on a recipe that wasn't kept (#107): buys the unlock, then saves the
    /// recipe on screen. A pending or failed purchase leaves it shown and unsaved, and says so.
    func onUnlock() {
        Task { [weak self, entitlements, repository] in
            let outcome = await entitlements.purchase()
            guard let self else { return }
            guard outcome == .unlocked else {
                if outcome.needsNotice { uiState.unlockNotice = outcome }
                return
            }
            guard var content = uiState.content.success else { return }
            if case .success(let saved) = await repository.keep(content.recipe) {
                content.recipe = saved
                uiState.content = .success(content)
                uiState.notKept = false
                chef.start() // now it has a row to keep short steps against
            }
        }
    }

    /// The view has shown `unlockNotice`.
    func onUnlockNoticeShown() { uiState.unlockNotice = nil }

    /// The view has shown `updateError`.
    func onUpdateErrorShown() { uiState.updateError = nil }

    func onServingsChange(_ target: Int) {
        guard let shown = uiState.content.success,
              let content = RecipeRenderer.withServings(shown, target: target, settings: renderSettings),
              let scale = content.servings else { return }
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
        chef.onSetting(settings.chefMode)
        let rendersDifferently = settings.unitSystem != uiState.unitSystem
            || settings.convertLiquids != uiState.convertLiquids
            || settings.temperatureUnit != uiState.temperatureUnit
            || settings.amountsInSteps != uiState.amountsInSteps
        var state = uiState
        state.unitSystem = settings.unitSystem
        state.convertLiquids = settings.convertLiquids
        state.temperatureUnit = settings.temperatureUnit
        state.darkWhileCooking = settings.darkWhileCooking
        state.amountsInSteps = settings.amountsInSteps
        // Assigned only when something changed, so an echo of our own write notifies no view.
        guard state != uiState else { return }
        uiState = state
        if rendersDifferently { rerender() }
    }

    // Re-renders the loaded recipe under the current settings, keeping its chosen servings.
    private func rerender() {
        guard let content = uiState.content.success else { return }
        uiState.content = .success(RecipeRenderer.rerender(content, settings: renderSettings, shortSteps: chef.shortSteps))
    }

    // Chef mode's short steps as they now stand, rendered like the steps they stand for.
    private func applyShortSteps() {
        guard let content = uiState.content.success else { return }
        let shown = RecipeRenderer.withShortSteps(content, chef.shortSteps, settings: renderSettings)
        guard shown.shortInstructions != content.shortInstructions else { return }
        uiState.content = .success(shown)
    }

    private var renderSettings: RecipeRenderer.Settings {
        RecipeRenderer.Settings(
            unitSystem: uiState.unitSystem,
            convertLiquids: uiState.convertLiquids,
            temperatureUnit: uiState.temperatureUnit,
            amountsInSteps: uiState.amountsInSteps,
            decisions: chef.decisions
        )
    }

    // The model's questions about the loaded recipe: count brackets (#104) and junk (#174).
    private func askModel() {
        guard let content = uiState.content.success else { return }
        chef.askCountBrackets(content)
        chef.askJunk(content)
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
        let start = cookSession.start(uiState.cook, stepCount: count)
        // A finished run starts fresh, and its timers go with it, so their alerts must too.
        for step in start.stopped { alarms.cancel(recipeId: content.recipe.id, step: step) }
        if let cook = start.cook { uiState.cook = cook }
        saveCook()
    }

    func onCookExit() {
        uiState.cook = cookSession.exit(uiState.cook)
        saveCook()
    }

    func onIngredientsToggle() { uiState.cook = cookSession.toggleIngredients(uiState.cook) }

    /// Tapping a step makes it current. Only `onStepDone` ever advances or marks progress.
    func onStepSelected(_ index: Int) {
        uiState.cook = cookSession.select(uiState.cook, step: index)
        saveCook()
    }

    func onStepDone() {
        guard let content = uiState.content.success else { return }
        let done = cookSession.done(uiState.cook, stepCount: content.instructions.count)
        // The end of cooking (#147): what was ticked goes to the pantry's use-up sheet. A
        // finished run finishes again only after starting fresh, so once per cook.
        if done.finished, let finished = cookSession.finishedCook(content, ticked: uiState.checkedIngredients) {
            uiState.cookFinished = finished
        }
        uiState.cook = done.cook
        saveCook()
    }

    /// The view has handed `cookFinished` on.
    func onCookFinishedHandled() { uiState.cookFinished = nil }

    /// Saves the cook's place as it now stands (`CookSession.progress`; Android's `saveCook`).
    private func saveCook() {
        guard let id = uiState.content.success?.recipe.id else { return }
        let progress = cookSession.progress(uiState.cook)
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
        applyTimer(cookSession.startTimer(uiState.cook, step: step, totalSeconds: total), content.recipe, step)
    }

    func onTimerToggle(_ step: Int) {
        guard let recipe = uiState.content.success?.recipe,
              let change = cookSession.toggleTimer(uiState.cook, step: step) else { return }
        applyTimer(change, recipe, step)
    }

    // Shows a timer's change, schedules or cancels its alert, and saves.
    private func applyTimer(_ change: CookSession.TimerChange, _ recipe: Recipe, _ step: Int) {
        uiState.cook = change.cook
        if let endsAt = change.endsAt {
            alarms.schedule(StepAlarm(recipeId: recipe.id, recipeTitle: recipe.name, step: step, endsAt: endsAt))
            ensureTicking()
        } else {
            alarms.cancel(recipeId: recipe.id, step: step)
        }
        saveCook()
    }

    func onTimerReset(_ step: Int) {
        guard let cook = cookSession.resetTimer(uiState.cook, step: step) else { return }
        uiState.cook = cook
        if let id = uiState.content.success?.recipe.id { alarms.cancel(recipeId: id, step: step) }
        saveCook()
    }

    func onTimerAlerted(_ step: Int) {
        if let cook = cookSession.alerted(uiState.cook, step: step) { uiState.cook = cook }
    }

    /// One tick loop for every running timer. Remaining time is recomputed from the wall
    /// clock each tick (`CookSession.tick`) rather than decremented, so a pause in delivery never
    /// drifts it. The loop ends by itself once no timer is running, and `deinit` cancels it.
    private func ensureTicking() {
        guard tickTask == nil else { return }
        // Weak across the sleep, so a screen popped with a timer running lets its ViewModel go.
        tickTask = Task { [weak self, sleep] in
            while self?.cookSession.hasRunningTimers == true {
                do { try await sleep(Self.tick) } catch { break }
                self?.tickOnce()
            }
            self?.tickTask = nil
        }
    }

    /// Whether the tick loop is alive. For tests: it must not outlive the last running timer.
    var isTicking: Bool { tickTask != nil }

    private func tickOnce() {
        if let cook = cookSession.tick(uiState.cook) { uiState.cook = cook }
    }
}
