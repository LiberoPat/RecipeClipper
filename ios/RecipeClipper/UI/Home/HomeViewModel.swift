import Combine
import Foundation
import Observation

/// `loaded` is false until the database has answered once, so the screen doesn't flash its
/// "nothing here yet" hint at someone who has plenty of history. `linkInput` is the "Paste a
/// link" alert's text (#286, the Recipes screen's alert), shown while `pastingLink`.
struct HomeUiState: Equatable {
    var loaded = false
    var pastingLink = false
    var linkInput = ""
    /// Go found nothing to open in the pasted text (#372); cleared by the next keystroke.
    var linkNoRecipe = false
    /// The "Ask an AI" sheet (#351) is open.
    var askAiOpen = false
    var continueCooking: RecipeSummary?
    /// Where the cook stopped on `continueCooking` (#286); nil when it isn't being cooked.
    var resume: CookResume?
    /// Today's next meal from Week (#402), shown in the resume card's place while nothing is
    /// being cooked (`resume` nil); nil when nothing is left to cook today.
    var today: TodayCard?
    /// The Recipes and Lists rows' counts (#286); nil until known.
    var recipeCount: Int?
    var listCount: Int?
    /// "Restore from a backup file" (#150): offered on an empty library; its outcome shows under it.
    var restore: BackupStatus = .idle
    /// The only recipe is the tour's sample (#151), which leaves the library as good as empty.
    var onlySample = false

    var libraryEmpty: Bool { loaded && (continueCooking == nil || onlySample) }

    /// The restore row shows on an empty library, and stays to say how the restore went.
    var showsRestore: Bool { libraryEmpty || restore != .idle }

    /// The sheet's Go is enabled for any text (#372): a link imports, anything else goes where a share would.
    var canOpenLink: Bool { !linkInput.kIsBlank }
}

/// A recipe being cooked (#286): cook mode was left on, or a step timer still runs. `step` is the
/// one stopped on (1-based) of `stepCount`; `timerSecondsLeft` is the running timer that ends
/// first, recomputed from its saved deadline, or nil when none runs.
struct CookResume: Equatable {
    let recipeId: Int64
    let step: Int
    let stepCount: Int
    let timerSecondsLeft: Int?

    /// Where `recipe` stands at `now`; nil when it isn't being cooked (or has no steps).
    static func of(_ recipe: Recipe, now: Int64) -> CookResume? {
        let count = recipe.instructions.count
        guard count > 0 else { return nil }
        let cook = recipe.cook
        // A finished run (every step done) is never resumed, whatever its timers (#402).
        let finished = (0 ..< count).allSatisfy { cook.doneSteps.contains($0) }
        guard !finished else { return nil }
        let timer = cook.timers
            .filter { (0 ..< count).contains($0.key) }
            .compactMap(\.value.endsAt)
            .filter { $0 > now }
            .min()
            .map { CookSession.secondsUntil($0, now) }
        guard cook.active || timer != nil else { return nil }
        return CookResume(
            recipeId: recipe.id, step: min(max(cook.currentStep, 0), count - 1) + 1,
            stepCount: count, timerSecondsLeft: timer
        )
    }
}

/// One of today's planned meals (#402): `recipeId` is nil for a note, which only shows its `title`
/// (the note's text). `mealType` is the name it is planned under ("Dinner").
struct TodayMeal: Equatable {
    let entryId: Int64
    let mealType: String
    let recipeId: Int64?
    let title: String
    let imageUrl: String?
    let servings: Int?
}

/// Home's Today card (#402): `lead`, the earliest meal today not yet cooked, and the `others`
/// still to cook, in meal type order. A recipe counts as cooked today with a cooking entry dated
/// today (a photo, "Mark as cooked", or a finished run), and then drops off.
struct TodayCard: Equatable {
    let lead: TodayMeal
    let others: [TodayMeal]

    /// Today's card at `today`, from the day's `meals`; nil when nothing is left to cook.
    static func of(meals: [PlannedMeal], types: [MealType], today: Int64, cooked: Set<Int64>) -> TodayCard? {
        let sorted = types.sorted { ($0.sortOrder, $0.id) < ($1.sortOrder, $1.id) }
        let order = Dictionary(uniqueKeysWithValues: sorted.enumerated().map { ($1.id, $0) })
        let names = Dictionary(uniqueKeysWithValues: types.map { ($0.id, $0.name) })
        let left = meals.enumerated()
            .filter { $0.element.day == today && ($0.element.recipeId.map { !cooked.contains($0) } ?? true) }
            .sorted { (order[$0.element.mealTypeId] ?? .max, $0.offset) < (order[$1.element.mealTypeId] ?? .max, $1.offset) }
            .compactMap { _, meal -> TodayMeal? in
                let text = (meal.title ?? meal.note)?.trimmingCharacters(in: .whitespacesAndNewlines)
                guard let title = text, !title.isEmpty else { return nil }
                return TodayMeal(
                    entryId: meal.id, mealType: names[meal.mealTypeId] ?? "", recipeId: meal.recipeId,
                    title: title, imageUrl: meal.imageUrl, servings: meal.servings
                )
            }
        return left.first.map { TodayCard(lead: $0, others: Array(left.dropFirst())) }
    }
}

@MainActor
@Observable
final class HomeViewModel {
    /// Continue cooking, and whether a second recipe exists (for the sample check).
    static let recentCount = 2
    /// How often a running timer's time left is recomputed, as in cook mode.
    static let tick = Duration.milliseconds(250)
    /// How often the day is looked at again, at most (#402).
    static let dayCheck = Duration.seconds(60)

    private(set) var uiState = HomeUiState()
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()
    // Restore (#150): the same file reading and merge as Settings' Import; nil offers none.
    @ObservationIgnored private let backups: BackupRepository?
    @ObservationIgnored private let files: BackupFiles?
    @ObservationIgnored private let repository: RecipeRepository
    @ObservationIgnored private let clock: Clock
    @ObservationIgnored private let sleep: Sleep
    // "Paste a link" takes any text (#372): where it goes is the shared-text route's; nil opens links only.
    @ObservationIgnored private let pasted: PastedTextRouter?
    // Today's meals from Week, and which are cooked (#402); nil shows no Today card.
    @ObservationIgnored private let mealPlan: MealPlanRepository?
    @ObservationIgnored private let cooked: CookedPhotoRepository?
    @ObservationIgnored private var todayCancellable: AnyCancellable?
    @ObservationIgnored private var followedDay: Int64?
    @ObservationIgnored private var dayTask: Task<Void, Never>?
    // The resume card (#286): the latest recipe followed as its cook progress is saved.
    @ObservationIgnored private var followedId: Int64?
    @ObservationIgnored private var followed: Recipe?
    @ObservationIgnored private var cookCancellable: AnyCancellable?
    @ObservationIgnored private var tickTask: Task<Void, Never>?

    /// The most recent recipe is the "continue cooking" card. There is deliberately no "Saved"
    /// section and no "Recently viewed" shelf (#371; see CLAUDE.md, UI decisions). `lists` gives the
    /// Lists row its count (#286; nil shows none).
    init(
        repository: RecipeRepository, backups: BackupRepository? = nil, files: BackupFiles? = nil,
        lists: ListRepository? = nil, clock: Clock = SystemClock(), sleep: @escaping Sleep = Sleeps.real,
        pasted: PastedTextRouter? = nil, mealPlan: MealPlanRepository? = nil, cooked: CookedPhotoRepository? = nil
    ) {
        self.pasted = pasted
        self.mealPlan = mealPlan
        self.cooked = cooked
        self.repository = repository
        self.backups = backups
        self.files = files
        self.clock = clock
        self.sleep = sleep
        observeRecent()
        followToday()
        repository.observeHistory(query: "").map(\.count)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] count in self?.uiState.recipeCount = count }
            .store(in: &cancellables)
        lists?.observeLists().map(\.count)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] count in self?.uiState.listCount = count }
            .store(in: &cancellables)
    }

    deinit {
        tickTask?.cancel()
        dayTask?.cancel()
    }

    /// Follows today's card (#402) as the plan, the cooked entries and the day change. The clock's
    /// day, never the screen's: a loop wakes at midnight (or within a minute, if the clock or zone
    /// changed under it) and follows the new day.
    private func followToday() {
        guard mealPlan != nil else { return }
        followDay(PlanDays.today(millis: clock.now()))
        dayTask = Task { [weak self, clock, sleep] in
            while !Task.isCancelled {
                let now = clock.now()
                let offset = Int64(TimeZone.current.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(now) / 1000))) * 1000
                let untilMidnight = PlanDays.millisPerDay - (((now + offset) % PlanDays.millisPerDay) + PlanDays.millisPerDay) % PlanDays.millisPerDay
                do { try await sleep(min(.milliseconds(untilMidnight), Self.dayCheck)) } catch { break }
                self?.followDay(PlanDays.today(millis: clock.now()))
            }
        }
    }

    private func followDay(_ day: Int64) {
        guard let mealPlan, day != followedDay else { return }
        followedDay = day
        let cooked = cooked?.observeCookedOn(day: day) ?? Just(Set<Int64>()).eraseToAnyPublisher()
        todayCancellable = Publishers.CombineLatest3(
            mealPlan.observeDays(start: day, end: day), mealPlan.observeMealTypes(), cooked
        )
        .map { meals, types, cookedIds in TodayCard.of(meals: meals, types: types, today: day, cooked: cookedIds) }
        .receive(on: DispatchQueue.main)
        .sink { [weak self] card in
            guard let self, followedDay == day, uiState.today != card else { return }
            uiState.today = card
        }
    }

    /// The "Ask an AI" tile (#351): opens its sheet.
    func onAskAi() {
        uiState.askAiOpen = true
    }

    func onAskAiDismissed() {
        uiState.askAiOpen = false
    }

    private func observeRecent() {
        repository.observeRecent(limit: Self.recentCount)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] recent in
                guard let self else { return }
                uiState.loaded = true
                uiState.continueCooking = recent.first
                uiState.onlySample = false
                follow(recent.first?.id)
                // The sample alone (#151) still offers the restore row: there is nothing of
                // the user's to restore over.
                if recent.count == 1, let only = recent.first {
                    Task { [weak self, repository] in
                        let sample = await repository.sampleId()
                        guard let self, uiState.continueCooking?.id == only.id else { return }
                        uiState.onlySample = sample == only.id
                    }
                }
            }
            .store(in: &cancellables)
    }

    /// Follows Continue cooking's recipe (#286): a newer one never shows the last one's place.
    private func follow(_ id: Int64?) {
        guard id != followedId else { return }
        followedId = id
        followed = nil
        uiState.resume = nil
        cookCancellable = id.map { id in
            repository.observeRecipe(id: id)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] recipe in
                    guard let self, recipe == nil || recipe?.id == followedId else { return }
                    followed = recipe
                    refreshResume()
                }
        }
    }

    /// Recomputes the card's place from the saved progress and the clock; writes nothing.
    private func refreshResume() {
        let resume = followed.flatMap { CookResume.of($0, now: clock.now()) }
        if uiState.resume != resume { uiState.resume = resume }
        if resume?.timerSecondsLeft != nil { ensureTicking() }
    }

    /// One tick loop while a timer runs, recomputing its time left from its deadline (as cook
    /// mode does); it ends by itself once none runs, and `deinit` cancels it.
    private func ensureTicking() {
        guard tickTask == nil else { return }
        tickTask = Task { [weak self, sleep] in
            while self?.uiState.resume?.timerSecondsLeft != nil {
                do { try await sleep(Self.tick) } catch { break }
                self?.refreshResume()
            }
            self?.tickTask = nil
        }
    }

    /// Whether the tick loop is alive. For tests: it must not outlive the last running timer.
    var isTicking: Bool { tickTask != nil }

    // MARK: Paste a link (#286): the Recipes screen's alert, opened from Home's tile.

    func onPasteLink() {
        uiState.linkInput = ""
        uiState.pastingLink = true
    }

    func onLinkChange(_ text: String) {
        uiState.linkInput = text
        uiState.linkNoRecipe = false
    }

    /// The alert went away by itself. Only the flag is cleared, as on Recipes: SwiftUI may run
    /// Go's action before or after resetting the binding.
    func onLinkDismissed() {
        uiState.pastingLink = false
        uiState.linkNoRecipe = false
    }

    /// Go (#372): a link imports, any other text goes where the same text shared would (the
    /// editor, the meal-plan review, Groceries' list sheet). Returns where, closing the sheet;
    /// nil, and the sheet stays saying so, with nothing to open. Nothing is saved here.
    func onOpenLink() -> PasteTarget? {
        let text = uiState.linkInput
        guard !text.kIsBlank else { return nil }
        guard let target = pasted?.open(text) ?? UrlInput.normalize(text).map(PasteTarget.link) else {
            uiState.linkNoRecipe = true
            return nil
        }
        uiState.pastingLink = false
        uiState.linkInput = ""
        return target
    }

    // MARK: Restore (#150)

    /// Whether "Restore from a backup file" can be offered at all.
    var canRestore: Bool { backups != nil && files != nil }

    /// "Restore from a backup file" (#150): the file the user picked, merged in like Import.
    /// Returns the work so a test can await it.
    @discardableResult
    func onRestorePicked(_ url: URL) -> Task<Void, Never>? {
        guard let backups, let files, uiState.restore != .importing else { return nil }
        uiState.restore = .importing
        return Task {
            uiState.restore = BackupStatus(await backups.importFile(url, files: files))
        }
    }

    /// The file importer failed before a file was chosen (a provider error, not a cancel).
    func onRestorePickFailed() {
        guard uiState.restore != .importing else { return }
        uiState.restore = .failed(.readFailed)
    }
}
