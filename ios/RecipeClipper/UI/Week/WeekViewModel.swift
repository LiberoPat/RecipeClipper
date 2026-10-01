import Combine
import Foundation
import Observation

/// One day and its meals, in plan order.
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

/// Moving `meal`: another day (today and the next 13) or meal type.
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

/// A scroll the list owes (#232): `day` to the top, animated or not. `id` tells two requests for
/// the same day apart, and lets a finished scroll clear only its own request.
struct ScrollTarget: Equatable {
    let day: Int64
    let animate: Bool
    let id: Int
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

/// What the snackbar says after a menu action (#52).
enum MenuMessage: Equatable {
    case saved(name: String)
    case saveFailed
    case applied(name: String, count: Int)
}

/// Reusable weekly menus on the Week tab (#52). `menus` is every saved menu; `saving` opens the
/// name prompt for the week shown; `picking` opens the menus sheet (apply, rename, delete);
/// `renaming` and `deleting` are the menu being renamed or confirmed for deletion.
struct MenusUiState: Equatable {
    var menus: [WeekMenu] = []
    var saving = false
    var picking = false
    var renaming: WeekMenu?
    var deleting: WeekMenu?
    var message: MenuMessage?
}

/// The Week tab (#49, rolling since #232; Android's WeekUiState). The list holds every day from
/// `firstDay` to `lastDay`, one section each; `topDay` is the day at the top of it. Weeks are
/// seven-day blocks counted from `today`, and `weekStart` is the block at the top: what the
/// header names and the week's actions use.
///
/// Only `meals` of the days from `loadedFrom` to `loadedTo` are held, a window around the top that
/// moves with it; a day outside it shows no meals until the window reaches it. `removed` names
/// the meal just removed, for the undo snackbar.
struct WeekUiState: Equatable {
    var today: Int64 = 0
    var firstDay: Int64 = 0
    var lastDay: Int64 = -1
    var topDay: Int64 = 0
    var loadedFrom: Int64 = 0
    var loadedTo: Int64 = -1
    var meals: [Int64: [PlannedMeal]] = [:]
    var mealTypes: [MealType] = []
    var adding: AddToDayState?
    var moving: MoveState?
    var removed: RemovedMeal?
    /// The month view, or nil while the days are shown.
    var month: MonthUiState?
    /// A day the list is to bring to the top, once.
    var scrollTo: ScrollTarget?
    /// The shown week as a calendar file, waiting for the screen to share it (#52).
    var calendarFile: CalendarFile?
    /// Saved weekly menus and their prompts (#52).
    var menus = MenusUiState()

    /// The first day of the block at the top.
    var weekStart: Int64 { PlanDays.blockStart(topDay, today: today) }

    /// "This week": the block that starts today.
    var isThisWeek: Bool { weekStart == today }

    /// Every day the list holds, in order.
    var allDays: ClosedRange<Int64> { firstDay...max(firstDay, lastDay) }

    func isLoaded(_ day: Int64) -> Bool { day >= loadedFrom && day <= loadedTo }

    func mealsOn(_ day: Int64) -> [PlannedMeal] { meals[day] ?? [] }

    /// The seven days of the block at the top with their meals, empty until they have loaded.
    var days: [WeekDay] {
        guard isLoaded(weekStart), isLoaded(weekStart + 6) else { return [] }
        return PlanDays.weekDays(weekStart).map { WeekDay(day: $0, meals: mealsOn($0)) }
    }

    /// Whether the week shown has anything to put in a calendar file or a menu.
    var hasMeals: Bool { days.contains { !$0.meals.isEmpty } }
}

/// The Week tab (#49; Android's WeekViewModel): one scroll of days, opening on today; ‹ › snap
/// to the previous or next seven-day block from today (#232), and "Today" comes back. Everything
/// is written as it happens; removing a meal can be undone from the snackbar.
@MainActor
@Observable
final class WeekViewModel {
    static let debounce = Duration.milliseconds(250)

    /// The list's days: a year back and two ahead of today (or of a day jumped to beyond).
    static let daysBefore: Int64 = 365
    static let daysAfter: Int64 = 730

    /// The window of meals held: four weeks either side of the block at the top.
    static let windowBefore: Int64 = 28
    static let windowDays: Int64 = 63

    private(set) var uiState: WeekUiState

    @ObservationIgnored private let plan: MealPlanRepository
    @ObservationIgnored private let recipes: RecipeRepository
    @ObservationIgnored private let calendar: PlanCalendar
    @ObservationIgnored private let sleep: Sleep
    @ObservationIgnored private var daysSubscription: AnyCancellable?
    @ObservationIgnored private var monthSubscription: AnyCancellable?
    @ObservationIgnored private var typesSubscription: AnyCancellable?
    @ObservationIgnored private var menusSubscription: AnyCancellable?
    @ObservationIgnored private var searchSubscription: AnyCancellable?
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored private var removedMeal: DeletedMeal?
    /// The first day of the window of meals held (see `WeekUiState.meals`).
    @ObservationIgnored private var window: Int64 = 0
    @ObservationIgnored private var scrollRequests = 0

    init(plan: MealPlanRepository, recipes: RecipeRepository, calendar: PlanCalendar, sleep: @escaping Sleep = Sleeps.real) {
        self.plan = plan
        self.recipes = recipes
        self.calendar = calendar
        self.sleep = sleep
        let today = calendar.today()
        uiState = WeekUiState(
            today: today, firstDay: today - Self.daysBefore, lastDay: today + Self.daysAfter, topDay: today
        )
        typesSubscription = plan.observeMealTypes()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] types in self?.uiState.mealTypes = types }
        menusSubscription = plan.observeMenus()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] menus in self?.uiState.menus.menus = menus }
        load(from: today - Self.windowBefore)
    }

    // MARK: Scrolling and weeks (#232)

    /// The list has scrolled freely and `day` is at the top. Ignored while a requested scroll is
    /// on its way, so the header doesn't flicker through the days it passes.
    func onTopDayChanged(_ day: Int64) {
        guard uiState.scrollTo == nil else { return }
        showTop(day)
    }

    /// The list has finished (or given up) the scroll `id`; `topDay` is the day now at the top.
    func onScrollHandled(_ id: Int, topDay: Int64?) {
        guard uiState.scrollTo?.id == id else { return }
        uiState.scrollTo = nil
        if let topDay { showTop(topDay) }
    }

    /// › : the first day of the block after the one at the top (or the one being scrolled to).
    func onNextWeek() { stepBlocks(1) }

    /// ‹ : the first day of the block before the one at the top.
    func onPreviousWeek() { stepBlocks(-1) }

    /// "Today": back to today at the top, which may have changed since the screen opened.
    func onToday() {
        uiState.today = calendar.today()
        jumpTo(uiState.today, animate: true)
    }

    /// The app is back in front. On a new day, the weeks start from it again and today goes to
    /// the top: the week starts on the day the app is opened.
    func onScreenResumed() {
        let today = calendar.today()
        guard today != uiState.today else { return }
        uiState.today = today
        jumpTo(today, animate: false)
    }

    private func stepBlocks(_ blocks: Int64) {
        let from = uiState.scrollTo?.day ?? uiState.topDay
        jumpTo(PlanDays.blockStart(from, today: uiState.today) + blocks * PlanDays.blockDays, animate: true)
    }

    /// Asks the list to bring `day` to the top. A day outside the list's days moves the whole
    /// range to sit around it (the same span as around today), and then it can't animate.
    private func jumpTo(_ day: Int64, animate: Bool) {
        scrollRequests += 1
        let inRange = day >= uiState.firstDay && day <= uiState.lastDay
        if !inRange {
            uiState.firstDay = day - Self.daysBefore
            uiState.lastDay = day + Self.daysAfter
        }
        uiState.topDay = day
        uiState.scrollTo = ScrollTarget(day: day, animate: animate && inRange, id: scrollRequests)
        follow(day)
    }

    private func showTop(_ day: Int64) {
        uiState.topDay = day
        follow(day)
    }

    /// Moves the window of meals when the block before or after the one at the top would fall
    /// outside it, re-centring it on that block: one query per four weeks scrolled.
    private func follow(_ day: Int64) {
        let start = PlanDays.blockStart(day, today: uiState.today)
        let to = window + Self.windowDays - 1
        if start - PlanDays.blockDays < window || start + 2 * PlanDays.blockDays - 1 > to {
            load(from: start - Self.windowBefore)
        }
    }

    /// Holds the meals of the window from `from`, replacing what was held: what is held stays
    /// one window, however far the list scrolls.
    private func load(from: Int64) {
        window = from
        let to = from + Self.windowDays - 1
        daysSubscription = plan.observeDays(start: from, end: to)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] meals in
                guard let self, self.window == from else { return }
                self.uiState.meals = Dictionary(grouping: meals, by: \.day)
                self.uiState.loadedFrom = from
                self.uiState.loadedTo = to
            }
    }

    // MARK: Month view (#52)

    /// Shows the month of the week shown: today's month for this week, else the month holding
    /// most of the week (its fourth day).
    func onShowMonth() {
        let today = calendar.today()
        let first = PlanDays.monthStart(uiState.isThisWeek ? today : uiState.weekStart + 3)
        uiState.month = MonthUiState(monthStart: first, thisMonthStart: PlanDays.monthStart(today))
        showMonth(first)
    }

    /// Back to the days, where they were.
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

    /// A day in the month grid: back to the days, with that one at the top.
    func onMonthDaySelected(_ day: Int64) {
        onShowWeek()
        jumpTo(day, animate: false)
    }

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
            days: (0..<PlanDays.sheetDays).map { uiState.today + Int64($0) },
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

// MARK: - Menus (#52)

extension WeekViewModel {
    func onSaveMenuStart() { uiState.menus.saving = true }

    func onSaveMenuDismissed() { uiState.menus.saving = false }

    /// Saves the week shown as `name`. A blank name does nothing.
    func onSaveMenu(_ name: String) {
        let text = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        uiState.menus.saving = false
        let weekStart = uiState.weekStart
        Task {
            let saved = await plan.saveWeekAsMenu(name: text, weekStart: weekStart, firstDayOfWeek: calendar.firstDayOfWeek())
            uiState.menus.message = saved ? .saved(name: text) : .saveFailed
        }
    }

    func onPickMenuStart() { uiState.menus.picking = true }

    func onPickMenuDismissed() { uiState.menus.picking = false }

    /// Adds `menu`'s meals to the week shown, after what is planned there.
    func onApplyMenu(_ menu: WeekMenu) {
        uiState.menus.picking = false
        let weekStart = uiState.weekStart
        Task {
            let added = await plan.applyMenu(id: menu.id, weekStart: weekStart, firstDayOfWeek: calendar.firstDayOfWeek())
            uiState.menus.message = .applied(name: menu.name, count: added)
        }
    }

    func onRenameMenuStart(_ menu: WeekMenu) { uiState.menus.renaming = menu }

    func onRenameMenuDismissed() { uiState.menus.renaming = nil }

    func onRenameMenu(_ name: String) {
        guard let menu = uiState.menus.renaming,
              !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        uiState.menus.renaming = nil
        Task { await plan.renameMenu(id: menu.id, name: name) }
    }

    func onDeleteMenuStart(_ menu: WeekMenu) { uiState.menus.deleting = menu }

    func onDeleteMenuDismissed() { uiState.menus.deleting = nil }

    func onDeleteMenuConfirm() {
        guard let menu = uiState.menus.deleting else { return }
        uiState.menus.deleting = nil
        Task { await plan.deleteMenu(id: menu.id) }
    }

    func onMenuMessageShown() { uiState.menus.message = nil }
}
