import Combine
import Foundation
import Observation

/// The "Add to plan" sheet (#49): a day of this week or next, a meal type (Dinner to start),
/// and the servings (the recipe's yield to start; nil when it has no number, so no stepper).
/// `added` is set once the meal is written, and the sheet closes on it.
struct AddToPlanUiState: Equatable {
    var days: [Int64] = []
    var today: Int64 = 0
    var selectedDay: Int64 = 0
    var mealTypes: [MealType] = []
    var selectedMealTypeId: Int64?
    var servings: Int?
    var added = false

    var canAdd: Bool { selectedMealTypeId != nil && !added }
}

/// Backs the sheet opened from the recipe screen's menu (Android's AddToPlanViewModel). Unlike
/// save-to-list, adding is one deliberate act with a button. The recipe arrives through
/// `setRecipe`, as the sheet is not a navigation destination.
@MainActor
@Observable
final class AddToPlanViewModel {
    private(set) var uiState = AddToPlanUiState()

    @ObservationIgnored private let repository: MealPlanRepository
    @ObservationIgnored private let calendar: PlanCalendar
    @ObservationIgnored private var recipeId: Int64?
    @ObservationIgnored private var typesSubscription: AnyCancellable?

    init(repository: MealPlanRepository, calendar: PlanCalendar) {
        self.repository = repository
        self.calendar = calendar
        typesSubscription = repository.observeMealTypes()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] types in
                guard let self else { return }
                let keep = self.uiState.selectedMealTypeId.flatMap { id in types.contains { $0.id == id } ? id : nil }
                self.uiState.mealTypes = types
                self.uiState.selectedMealTypeId = keep ?? Self.defaultType(types)
            }
    }

    /// Opens the sheet for `id`, starting on today, Dinner and the recipe's own yield.
    func setRecipe(_ id: Int64, yieldServings: Int?) {
        recipeId = id
        let today = calendar.today()
        let start = PlanDays.weekStart(today, firstDayOfWeek: calendar.firstDayOfWeek())
        uiState.days = (0..<PlanDays.sheetDays).map { start + Int64($0) }
        uiState.today = today
        uiState.selectedDay = today
        uiState.selectedMealTypeId = Self.defaultType(uiState.mealTypes)
        uiState.servings = yieldServings
        uiState.added = false
    }

    func onDaySelected(_ day: Int64) { uiState.selectedDay = day }

    func onMealTypeSelected(_ id: Int64) { uiState.selectedMealTypeId = id }

    func onServingsChange(_ value: Int) {
        guard uiState.servings != nil else { return }
        uiState.servings = min(max(value, 1), Servings.max)
    }

    func onAdd() {
        guard let recipe = recipeId, let type = uiState.selectedMealTypeId, !uiState.added else { return }
        uiState.added = true
        let day = uiState.selectedDay
        let servings = uiState.servings
        Task { await repository.addRecipe(recipeId: recipe, day: day, mealTypeId: type, servings: servings) }
    }

    private static func defaultType(_ types: [MealType]) -> Int64? {
        (types.first { $0.builtInKey == MealType.dinner } ?? types.first)?.id
    }
}

/// Identified by instance, so the recipe screen can present its sheet with `sheet(item:)`.
extension AddToPlanViewModel: Identifiable {}
