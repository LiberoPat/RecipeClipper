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

    init(
        recipeId: Int64?,
        url: String?,
        repository: RecipeRepository,
        preferences: AppPreferences,
        clock: Clock,
        sleep: @escaping Sleep = Sleeps.real,
        connectivity: Connectivity = StaticConnectivity()
    ) {
        self.recipeId = recipeId.flatMap { $0 > 0 ? $0 : nil }
        self.shareUrl = url.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        self.repository = repository
        self.preferences = preferences
        self.clock = clock
        self.sleep = sleep
        self.connectivity = connectivity
        // Seeded synchronously so the first render already uses the user's units.
        let settings = preferences.current
        uiState = RecipeUiState(
            unitSystem: settings.unitSystem,
            convertLiquids: settings.convertLiquids,
            temperatureUnit: settings.temperatureUnit,
            darkWhileCooking: settings.darkWhileCooking
        )
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
            case .success(let recipe):
                uiState.content = .success(successContent(recipe))
                uiState.checkedIngredients = recipe.checkedIngredients
                uiState.notes = recipe.notes ?? ""
            case .error(let error):
                uiState.content = .error(error)
                if error.reloadsOnReconnect { reloadOnReconnect() }
            }
        }
    }

    /// While an offline or fetch-failed error is on screen, waits for the connection to go from
    /// offline to online and then loads again, once. Already online is not a transition: a
    /// fetch failure while connected waits for a drop and a return, never reloading in a loop.
    /// Any new load (including Try again) cancels it.
    private func reloadOnReconnect() {
        reconnectTask = Task { [weak self, connectivity] in
            var sawOffline = false
            var reconnected = false
            for await online in connectivity.onlineUpdates() {
                if !online {
                    sawOffline = true
                } else if sawOffline {
                    reconnected = true
                    break
                }
            }
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
    /// sharing. Nil when nothing is loaded. Presenting the share sheet is the view's job.
    func shareText() -> String? {
        guard let content = uiState.content.success else { return nil }
        return RecipeShareText.format(
            recipe: content.recipe,
            servings: content.servings,
            ingredients: content.ingredients,
            instructions: content.instructions
        )
    }

    /// Deletes the recipe outright, list memberships included, then sets `deleted` so the
    /// screen can pop. No undo here: the screen showing it is already gone by then.
    func onDelete() {
        guard let id = uiState.content.success?.recipe.id else { return }
        Task {
            _ = await repository.delete(id: id)
            uiState.deleted = true
        }
    }

    func onServingsChange(_ target: Int) {
        guard var content = uiState.content.success, let servings = content.servings else { return }
        let scale = ServingsScale(base: servings.base, target: min(max(target, 1), Servings.max))
        content.servings = scale
        content.ingredients = render(content.recipe, scale, uiState.unitSystem, uiState.convertLiquids)
        uiState.content = .success(content)
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
        content.ingredients = render(content.recipe, content.servings, uiState.unitSystem, uiState.convertLiquids)
        content.instructions = renderInstructions(content.recipe, uiState.temperatureUnit)
        uiState.content = .success(content)
    }

    // MARK: Cook mode

    func onCookStart() {
        guard let content = uiState.content.success else { return }
        let count = content.instructions.count
        guard count > 0 else { return }
        // A finished run starts fresh; anything else resumes where it was left.
        var cook = uiState.cook.doneSteps.count >= count ? CookState() : uiState.cook
        cook.active = true
        cook.currentStep = min(max(cook.currentStep, 0), count - 1)
        uiState.cook = cook
    }

    func onCookExit() { uiState.cook.active = false }

    func onIngredientsToggle() { uiState.cook.ingredientsExpanded.toggle() }

    /// Tapping a step makes it current. Only `onStepDone` ever advances or marks progress.
    func onStepSelected(_ index: Int) { uiState.cook.currentStep = index }

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
    }

    // MARK: Step timers. Several can run at once, since steps overlap.

    func onTimerStart(_ step: Int) {
        guard let content = uiState.content.success,
              step >= 0, step < content.stepTimerSeconds.count,
              let total = content.stepTimerSeconds[step] else { return }
        deadlines[step] = clock.now() + Int64(total) * 1000
        uiState.cook.timers[step] = StepTimer(totalSeconds: total, remainingSeconds: total, running: true)
        ensureTicking()
    }

    func onTimerToggle(_ step: Int) {
        guard var timer = uiState.cook.timers[step] else { return }
        if timer.running {
            deadlines[step] = nil
            timer.running = false
            uiState.cook.timers[step] = timer
        } else if timer.remainingSeconds > 0 {
            deadlines[step] = clock.now() + Int64(timer.remainingSeconds) * 1000
            timer.running = true
            uiState.cook.timers[step] = timer
            ensureTicking()
        }
    }

    func onTimerReset(_ step: Int) {
        guard let timer = uiState.cook.timers[step] else { return }
        deadlines[step] = nil
        uiState.cook.timers[step] = StepTimer(
            totalSeconds: timer.totalSeconds, remainingSeconds: timer.totalSeconds, running: false
        )
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

    private func tickOnce() {
        let now = clock.now()
        var timers = uiState.cook.timers
        var changed = false
        for (step, end) in deadlines {
            let seconds = max(0, Int((Double(end - now) / 1000).rounded(.up)))
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
        let servings = Servings.parse(recipe.yield).map { ServingsScale(base: $0, target: $0) }
        return RecipeSuccess(
            recipe: recipe,
            servings: servings,
            ingredients: render(recipe, servings, uiState.unitSystem, uiState.convertLiquids),
            instructions: renderInstructions(recipe, uiState.temperatureUnit),
            stepTimerSeconds: recipe.instructions.map { StepTimers.parse($0) },
            sourceDomain: SourceDomain.of(recipe.sourceUrl)
        )
    }

    // Scale first, then convert, so a converted amount always matches the chosen servings.
    private func render(_ recipe: Recipe, _ servings: ServingsScale?, _ system: UnitSystem, _ convertLiquids: Bool) -> [String] {
        let factor = servings.map { Double($0.target) / Double($0.base) } ?? 1.0
        return recipe.ingredients.map {
            UnitConverter.convert(
                IngredientScaler.scale($0, factor: factor), system: system, includeLiquids: convertLiquids, separatorFrom: $0
            )
        }
    }

    // Instructions aren't scaled, but oven temperatures follow the chosen temperature unit —
    // independent of the ingredient unit system.
    private func renderInstructions(_ recipe: Recipe, _ unit: TemperatureUnit) -> [String] {
        recipe.instructions.map { TemperatureConverter.convert($0, unit: unit) }
    }
}
