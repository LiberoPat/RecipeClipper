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

/// An .ics file to share: its name and its text (#52).
struct CalendarFile: Equatable {
    let fileName: String
    let text: String
}

/// One cell of the month grid: `inMonth` is false for the days that fill the first and last rows.
struct MonthDay: Equatable, Identifiable {
    let day: Int64
    let inMonth: Bool
    let mealCount: Int
    var id: Int64 { day }
}

/// The month view (#52): `days` is whole weeks from the locale's first day (see
/// `PlanDays.monthGrid`), empty until the plan has loaded.
struct MonthUiState: Equatable {
    var monthStart: Int64
    var thisMonthStart: Int64
    var days: [MonthDay] = []

    var isThisMonth: Bool { monthStart == thisMonthStart }
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
    /// The month view, or nil while the week is shown.
    var month: MonthUiState?
    /// A day the week view scrolls to once, after a tap in the month view.
    var focusDay: Int64?
    /// The shown week as a calendar file, waiting for the screen to share it (#52).
    var calendarFile: CalendarFile?

    var isThisWeek: Bool { weekStart == thisWeekStart }

    /// Whether the week shown has anything to put in a calendar file.
    var hasMeals: Bool { days.contains { !$0.meals.isEmpty } }
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
    @ObservationIgnored private var monthSubscription: AnyCancellable?
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

    // MARK: Month view (#52)

    /// Shows the month of the week shown: today's month for this week, else the month holding
    /// most of the week (its fourth day).
    func onShowMonth() {
        let today = calendar.today()
        let first = PlanDays.monthStart(uiState.isThisWeek ? today : uiState.weekStart + 3)
        uiState.today = today
        uiState.month = MonthUiState(monthStart: first, thisMonthStart: PlanDays.monthStart(today))
        showMonth(first)
    }

    /// Back to the week view, on the week that was shown.
    func onShowWeek() {
        monthSubscription = nil
        uiState.month = nil
    }

    func onPreviousMonth() {
        if let month = uiState.month { showMonth(PlanDays.addMonths(month.monthStart, -1)) }
    }

    func onNextMonth() {
        if let month = uiState.month { showMonth(PlanDays.addMonths(month.monthStart, 1)) }
    }

    /// Back to the month holding today, which may have changed since the view opened.
    func onThisMonth() {
        let today = calendar.today()
        uiState.today = today
        uiState.month?.thisMonthStart = PlanDays.monthStart(today)
        showMonth(PlanDays.monthStart(today))
    }

    private func showMonth(_ first: Int64) {
        guard uiState.month != nil else { return }
        uiState.month?.monthStart = first
        uiState.month?.days = []
        let grid = PlanDays.monthGrid(first, firstDayOfWeek: calendar.firstDayOfWeek())
        let next = PlanDays.addMonths(first, 1)
        monthSubscription = plan.observeDays(start: grid[0], end: grid[grid.count - 1])
            .receive(on: DispatchQueue.main)
            .sink { [weak self] meals in
                guard let self, self.uiState.month?.monthStart == first else { return }
                let counts = Dictionary(grouping: meals, by: \.day).mapValues(\.count)
                self.uiState.month?.days = grid.map {
                    MonthDay(day: $0, inMonth: $0 >= first && $0 < next, mealCount: counts[$0] ?? 0)
                }
            }
    }

    /// A day in the month grid: back to the week view, on that day's week, scrolled to it.
    func onMonthDaySelected(_ day: Int64) {
        onShowWeek()
        uiState.focusDay = day
        let start = PlanDays.weekStart(day, firstDayOfWeek: calendar.firstDayOfWeek())
        if start != uiState.weekStart { showWeek(start) }
    }

    /// The week view has scrolled to `focusDay`.
    func onFocusHandled() { uiState.focusDay = nil }

    // MARK: Calendar file (#52)

    /// The week shown as an .ics file, for the screen to share. Nothing for an empty week.
    func onShareCalendar() {
        guard uiState.hasMeals else { return }
        let names = Dictionary(uniqueKeysWithValues: uiState.mealTypes.map { ($0.id, $0.name) })
        let text = MealPlanIcs.calendar(uiState.days.flatMap(\.meals), mealTypeNames: names, stampMillis: calendar.now())
        uiState.calendarFile = CalendarFile(fileName: MealPlanIcs.fileName(weekStart: uiState.weekStart), text: text)
    }

    /// The share sheet has gone (or couldn't open): the file is done with.
    func onCalendarShared() { uiState.calendarFile = nil }

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
