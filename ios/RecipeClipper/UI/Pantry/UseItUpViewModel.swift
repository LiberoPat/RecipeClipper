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
@MainActor
@Observable
final class UseItUpViewModel {
    private(set) var uiState = UseItUpUiState()

    private enum Target: Equatable {
        case item(Int64)
        case all
    }

    @ObservationIgnored private let plan: MealPlanRepository
    @ObservationIgnored private let isOn: () -> Bool
    @ObservationIgnored private var subscription: AnyCancellable?
    @ObservationIgnored private var typesSubscription: AnyCancellable?
    @ObservationIgnored private var items: [PantryItem] = []
    @ObservationIgnored private var saved: [SavedRecipe] = []
    @ObservationIgnored private var meals: [PlannedMeal] = []
    @ObservationIgnored private var target: Target?
    @ObservationIgnored private var mealType: Int64?
    @ObservationIgnored private var writing: Set<PlannedRecipeDay> = []

    init(
        pantry: PantryRepository, lists: ListRepository, plan: MealPlanRepository, calendar: PlanCalendar,
        isOn: @escaping () -> Bool
    ) {
        self.plan = plan
        self.isOn = isOn
        let today = calendar.today()
        uiState.today = today
        subscription = pantry.observeItems()
            .combineLatest(lists.observeSavedRecipes(), plan.observeDays(start: today, end: today + 1))
            .receive(on: DispatchQueue.main)
            .sink { [weak self] items, saved, meals in
                guard let self else { return }
                self.items = items
                self.saved = saved
                self.meals = meals
                self.update()
            }
        typesSubscription = plan.observeMealTypes()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] types in
                self?.mealType = (types.first { $0.builtInKey == MealType.dinner } ?? types.first)?.id
            }
    }

    /// The row's line: the sheet for that one item.
    func onOpen(itemId: Int64) {
        target = .item(itemId)
        update()
    }

    /// The expiry reminder's tap: the sheet for everything due.
    func onOpenAll() {
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
        Task { await plan.addRecipe(recipeId: recipeId, day: day, mealTypeId: type, servings: nil) }
    }

    private func update() {
        let today = uiState.today
        guard isOn() else {
            uiState = UseItUpUiState(today: today)
            return
        }
        let groups = UseItUp.groups(items, saved: saved, today: today)
        let sheet: [UseItUpGroup]? = switch target {
        case nil: nil
        case .all: groups
        case .item(let id): groups.filter { $0.item.id == id }
        }
        let planned = Set(meals.compactMap { meal in meal.recipeId.map { PlannedRecipeDay(recipeId: $0, day: meal.day) } })
        // A tap is guarded until the plan shows it, so a quick second tap adds nothing.
        writing.subtract(planned)
        uiState = UseItUpUiState(
            today: today,
            counts: Dictionary(uniqueKeysWithValues: groups.map { ($0.item.id, $0.recipes.count) }),
            sheet: sheet.flatMap { $0.isEmpty ? nil : $0 },
            planned: planned
        )
    }
}
