import Combine
import Foundation
import Observation

/// One day of the week and its meals, in plan order.
struct WeekDay: Equatable, Identifiable {
    let day: Int64
    let meals: [PlannedMeal]
    var id: Int64 { day }
}

/// The "+" sheet of one day: a meal type, then a recipe from history (searchable) or the typed
/// text as a note. `results` is nil until history has answered.
struct AddToDayState: Equatable {
    var day: Int64
    var mealTypeId: Int64?
    var query = ""
    var results: [RecipeSummary]?
}

/// Moving `meal`: another day (this week and next, from the week shown) or meal type.
struct MoveState: Equatable {
    var meal: PlannedMeal
    var days: [Int64]
    var day: Int64
    var mealTypeId: Int64
}

/// A meal just removed: its id keeps two removals of the same title apart.
struct RemovedMeal: Equatable {
    let id: Int64
    let label: String
}

/// `days` is empty until the plan has loaded. `removed` names the meal just removed, for the
/// undo snackbar.
struct WeekUiState: Equatable {
    var weekStart: Int64 = 0
    var thisWeekStart: Int64 = 0
    var today: Int64 = 0
    var days: [WeekDay] = []
    var mealTypes: [MealType] = []
    var adding: AddToDayState?
    var moving: MoveState?
    var removed: RemovedMeal?

    var isThisWeek: Bool { weekStart == thisWeekStart }
}

/// The Week tab (#49; Android's WeekViewModel): seven days from the locale's first day of the
/// week, ‹ › between weeks and "This week" back. Everything is written as it happens; removing
/// a meal can be undone from the snackbar.
@MainActor
@Observable
final class WeekViewModel {
    static let debounce = Duration.milliseconds(250)

    private(set) var uiState: WeekUiState

    @ObservationIgnored private let plan: MealPlanRepository
    @ObservationIgnored private let recipes: RecipeRepository
    @ObservationIgnored private let calendar: PlanCalendar
    @ObservationIgnored private let sleep: Sleep
    @ObservationIgnored private var daysSubscription: AnyCancellable?
    @ObservationIgnored private var typesSubscription: AnyCancellable?
    @ObservationIgnored private var searchSubscription: AnyCancellable?
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored private var removedMeal: DeletedMeal?

    init(plan: MealPlanRepository, recipes: RecipeRepository, calendar: PlanCalendar, sleep: @escaping Sleep = Sleeps.real) {
        self.plan = plan
        self.recipes = recipes
        self.calendar = calendar
        self.sleep = sleep
        let today = calendar.today()
        let start = PlanDays.weekStart(today, firstDayOfWeek: calendar.firstDayOfWeek())
        uiState = WeekUiState(weekStart: start, thisWeekStart: start, today: today)
        typesSubscription = plan.observeMealTypes()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] types in self?.uiState.mealTypes = types }
        showWeek(start)
    }

    // MARK: Weeks

    func onPreviousWeek() { showWeek(uiState.weekStart - 7) }

    func onNextWeek() { showWeek(uiState.weekStart + 7) }

    /// Back to the week holding today, which may have changed since the screen opened.
    func onThisWeek() {
        let today = calendar.today()
        let start = PlanDays.weekStart(today, firstDayOfWeek: calendar.firstDayOfWeek())
        uiState.today = today
        uiState.thisWeekStart = start
        showWeek(start)
    }

    private func showWeek(_ start: Int64) {
        uiState.weekStart = start
        uiState.days = []
        daysSubscription = plan.observeDays(start: start, end: start + 6)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] meals in
                guard let self, self.uiState.weekStart == start else { return }
                let byDay = Dictionary(grouping: meals, by: \.day)
                self.uiState.days = PlanDays.weekDays(start).map { WeekDay(day: $0, meals: byDay[$0] ?? []) }
            }
    }

    // MARK: Adding to a day

    func onAddToDay(_ day: Int64) {
        uiState.adding = AddToDayState(day: day, mealTypeId: defaultType(uiState.mealTypes))
        search("")
    }

    func onAddQueryChange(_ query: String) {
        uiState.adding?.query = query
        search(query)
    }

    /// Debounced, then the latest query's history only (Android's debounce + flatMapLatest).
    private func search(_ query: String) {
        searchTask?.cancel()
        searchTask = Task { [weak self, sleep] in
            do { try await sleep(Self.debounce) } catch { return }
            guard !Task.isCancelled, let self else { return }
            self.searchSubscription = self.recipes.observeHistory(query: query)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] found in self?.uiState.adding?.results = found }
        }
    }

    func onAddMealTypeSelected(_ id: Int64) { uiState.adding?.mealTypeId = id }

    func onAddRecipe(_ recipeId: Int64) {
        guard let adding = uiState.adding, let type = adding.mealTypeId else { return }
        closeAdding()
        Task { await plan.addRecipe(recipeId: recipeId, day: adding.day, mealTypeId: type, servings: nil) }
    }

    /// The search field's text, planned as a note.
    func onAddNote() {
        guard let adding = uiState.adding, let type = adding.mealTypeId,
              !adding.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return }
        closeAdding()
        Task { await plan.addNote(adding.query, day: adding.day, mealTypeId: type) }
    }

    func onAddDismissed() { closeAdding() }

    private func closeAdding() {
        searchTask?.cancel()
        searchSubscription = nil
        uiState.adding = nil
    }

    // MARK: Moving

    func onMoveStart(_ meal: PlannedMeal) {
        uiState.moving = MoveState(
            meal: meal,
            days: (0..<PlanDays.sheetDays).map { uiState.weekStart + Int64($0) },
            day: meal.day,
            mealTypeId: meal.mealTypeId
        )
    }

    func onMoveDaySelected(_ day: Int64) { uiState.moving?.day = day }

    func onMoveMealTypeSelected(_ id: Int64) { uiState.moving?.mealTypeId = id }

    func onMoveConfirm() {
        guard let moving = uiState.moving else { return }
        uiState.moving = nil
        Task { await plan.move(entryId: moving.meal.id, day: moving.day, mealTypeId: moving.mealTypeId) }
    }

    func onMoveDismissed() { uiState.moving = nil }

    // MARK: Removing, with undo

    /// Removes a meal from the plan, never the recipe. One undo at a time: a second removal
    /// settles the first.
    func onRemove(_ meal: PlannedMeal) {
        Task {
            guard let deleted = await plan.delete(entryId: meal.id) else { return }
            removedMeal = deleted
            uiState.removed = RemovedMeal(id: meal.id, label: meal.title ?? meal.note ?? "")
        }
    }

    func onUndoRemove() {
        guard let deleted = removedMeal else { return }
        removedMeal = nil
        uiState.removed = nil
        Task { await plan.restore(deleted) }
    }

    /// The snackbar timed out: the removal stands.
    func onSnackbarDismissed() {
        removedMeal = nil
        uiState.removed = nil
    }

    private func defaultType(_ types: [MealType]) -> Int64? {
        (types.first { $0.builtInKey == MealType.dinner } ?? types.first)?.id
    }
}
