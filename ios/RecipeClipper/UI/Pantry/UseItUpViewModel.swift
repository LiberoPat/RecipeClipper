import Combine
import Foundation
import Observation

/// A recipe on a day of the plan: what the Use it up sheet (#305) shows as already planned.
struct PlannedRecipeDay: Hashable {
    let recipeId: Int64
    let day: Int64
}

/// "Use it up" (#305; Android's `UseItUpUiState`). `counts` is, per pantry item expiring soon,
/// how many saved recipes use it (its row's line); an item with none is absent. `sheet` is what
/// the open sheet lists (one item from its row, every one due from the reminder), nil while it's
/// closed. `planned` holds each recipe already on the plan for today or tomorrow. Everything is
/// empty while the flag is off.
struct UseItUpUiState: Equatable {
    var today: Int64 = 0
    var counts: [Int64: Int] = [:]
    var sheet: [UseItUpGroup]?
    var planned: Set<PlannedRecipeDay> = []
}

/// Backs the Pantry rows' "3 saved recipes use it" line and the sheet it opens (#305; Android's
/// `UseItUpViewModel`). Only saved recipes (in at least one list) are read; matching is
/// `UseItUp`'s, by name only. One tap plans a recipe, as Dinner at its own yield, today or on the
/// item's use-by day.
///
/// Today is re-read when the Pantry comes back in front and when the reminder is tapped, as the
/// Week's is (#332): the tab's ViewModel outlives the day. The groups are matched off the main
/// actor, once per change of the pantry, the saved recipes or the day (a tick or a cook step
/// rewrites a recipe without changing a line, and matches nothing again); the sheet's target
/// only picks from the result.
@MainActor
@Observable
final class UseItUpViewModel {
    private(set) var uiState = UseItUpUiState()

    private enum Target: Equatable {
        case item(Int64)
        case all
    }

    /// What the groups are matched from; the same again is skipped.
    private struct Inputs: Equatable {
        let items: [PantryItem]
        let saved: [SavedRecipe]
        let today: Int64
    }

    /// The groups matched for one `today`; the state waits for the day it was asked for.
    private struct Matched {
        let today: Int64
        let groups: [UseItUpGroup]
    }

    @ObservationIgnored private let plan: MealPlanRepository
    @ObservationIgnored private let calendar: PlanCalendar
    @ObservationIgnored private let isOn: () -> Bool
    @ObservationIgnored private var subscription: AnyCancellable?
    @ObservationIgnored private var mealsSubscription: AnyCancellable?
    @ObservationIgnored private var typesSubscription: AnyCancellable?
    @ObservationIgnored private var today: Int64
    @ObservationIgnored private var inputs: Inputs?
    @ObservationIgnored private var matching: Task<Void, Never>?
    /// How many times the groups were matched; the tests read it (#332).
    @ObservationIgnored private(set) var matchings = 0
    @ObservationIgnored private var matched: Matched?
    @ObservationIgnored private var meals: [PlannedMeal] = []
    @ObservationIgnored private var target: Target?
    @ObservationIgnored private var mealType: Int64?
    @ObservationIgnored private var writing: Set<PlannedRecipeDay> = []

    init(
        pantry: PantryRepository, lists: ListRepository, plan: MealPlanRepository, calendar: PlanCalendar,
        isOn: @escaping () -> Bool
    ) {
        self.plan = plan
        self.calendar = calendar
        self.isOn = isOn
        today = calendar.today()
        uiState.today = today
        subscription = pantry.observeItems()
            .combineLatest(lists.observeSavedRecipes())
            .receive(on: DispatchQueue.main)
            .sink { [weak self] items, saved in self?.match(items, saved) }
        observeMeals()
        typesSubscription = plan.observeMealTypes()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] types in
                self?.mealType = (types.first { $0.builtInKey == MealType.dinner } ?? types.first)?.id
            }
    }

    /// The Pantry is back in front: on a new day the window and "Plan today" move with it (#332).
    func onScreenResumed() {
        let day = calendar.today()
        guard day != today else { return }
        today = day
        observeMeals()
        if let inputs { match(inputs.items, inputs.saved) }
    }

    /// The row's line: the sheet for that one item.
    func onOpen(itemId: Int64) {
        target = .item(itemId)
        update()
    }

    /// The expiry reminder's tap: the sheet for everything due.
    func onOpenAll() {
        onScreenResumed()
        target = .all
        update()
    }

    func onDismiss() {
        target = nil
        update()
    }

    /// Plans `recipeId` on `day` as Dinner, unless it's already planned that day.
    func onPlan(recipeId: Int64, day: Int64) {
        let key = PlannedRecipeDay(recipeId: recipeId, day: day)
        guard let type = mealType, !uiState.planned.contains(key), writing.insert(key).inserted else { return }
        Task {
            // A write that failed frees the tap, so it can be tried again (#332).
            let written = await plan.addRecipe(recipeId: recipeId, day: day, mealTypeId: type, servings: nil)
            if !written { writing.remove(key) }
        }
    }

    private func observeMeals() {
        mealsSubscription = plan.observeDays(start: today, end: today + 1)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] meals in
                self?.meals = meals
                self?.update()
            }
    }

    /// Matches the groups off the main actor, unless nothing they're made from has changed.
    private func match(_ items: [PantryItem], _ saved: [SavedRecipe]) {
        let inputs = Inputs(items: items, saved: saved, today: today)
        guard inputs != self.inputs else { return }
        self.inputs = inputs
        matchings += 1
        matching?.cancel()
        matching = Task { [weak self] in
            let groups = await Task.detached(priority: .userInitiated) {
                UseItUp.groups(inputs.items, saved: inputs.saved, today: inputs.today)
            }.value
            guard let self, !Task.isCancelled else { return }
            matched = Matched(today: inputs.today, groups: groups)
            update()
        }
    }

    private func update() {
        let today = self.today
        guard isOn() else {
            target = nil
            uiState = UseItUpUiState(today: today)
            return
        }
        // The day's groups are on their way: nothing shows from yesterday's meanwhile, and a
        // request waits for them.
        guard let matched, matched.today == today else { return }
        let groups = matched.groups
        let sheet: [UseItUpGroup]? = switch target {
        case nil: nil
        case .all: groups
        case .item(let id): groups.filter { $0.item.id == id }
        }
        let planned = Set(meals.compactMap { meal in meal.recipeId.map { PlannedRecipeDay(recipeId: $0, day: meal.day) } })
        // A tap is guarded until the plan shows it, so a quick second tap adds nothing.
        writing.subtract(planned)
        // A request nothing answers (nothing due) is dropped at once, so a recipe saved later
        // never opens the sheet by itself (#332).
        if target != nil && sheet?.isEmpty != false { target = nil }
        uiState = UseItUpUiState(
            today: today,
            counts: Dictionary(uniqueKeysWithValues: groups.map { ($0.item.id, $0.recipes.count) }),
            sheet: sheet.flatMap { $0.isEmpty ? nil : $0 },
            planned: planned
        )
    }
}
