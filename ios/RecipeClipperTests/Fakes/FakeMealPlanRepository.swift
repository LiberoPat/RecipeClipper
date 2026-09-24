import Combine
import Foundation
@testable import RecipeClipper

/// A fake that models the plan rather than only recording calls (Android's
/// FakeMealPlanRepository): `meals` and `types` are real state, `observeDays` filters and orders
/// them as the SQL does, and deleting a user type moves its meals to Dinner.
final class FakeMealPlanRepository: MealPlanRepository {
    static let breakfast: Int64 = 1
    static let lunch: Int64 = 2
    static let dinner: Int64 = 3
    static let snack: Int64 = 4

    static let defaultTypes = [
        MealType(id: breakfast, name: "Breakfast", builtInKey: "breakfast", sortOrder: 0),
        MealType(id: lunch, name: "Lunch", builtInKey: "lunch", sortOrder: 1),
        MealType(id: dinner, name: "Dinner", builtInKey: MealType.dinner, sortOrder: 2),
        MealType(id: snack, name: "Snack", builtInKey: "snack", sortOrder: 3),
    ]

    let types = CurrentValueSubject<[MealType], Never>(defaultTypes)
    let meals = CurrentValueSubject<[PlannedMeal], Never>([])
    /// recipeId → title, for meals added through `addRecipe`.
    var titles: [Int64: String] = [:]

    private var nextId: Int64 = 1000
    private var deleted: [Int64: PlannedMeal] = [:]

    func observeMealTypes() -> AnyPublisher<[MealType], Never> { types.eraseToAnyPublisher() }

    func observeDays(start: Int64, end: Int64) -> AnyPublisher<[PlannedMeal], Never> {
        meals.combineLatest(types)
            .map { all, order in
                let rank = Dictionary(uniqueKeysWithValues: order.enumerated().map { ($1.id, $0) })
                return all.enumerated()
                    .filter { $0.element.day >= start && $0.element.day <= end }
                    .sorted {
                        ($0.element.day, rank[$0.element.mealTypeId] ?? .max, $0.offset)
                            < ($1.element.day, rank[$1.element.mealTypeId] ?? .max, $1.offset)
                    }
                    .map(\.element)
            }
            .eraseToAnyPublisher()
    }

    func addRecipe(recipeId: Int64, day: Int64, mealTypeId: Int64, servings: Int?) async {
        nextId += 1
        meals.value.append(PlannedMeal(
            id: nextId, day: day, mealTypeId: mealTypeId, recipeId: recipeId,
            title: titles[recipeId] ?? "Recipe \(recipeId)", imageUrl: nil, servings: servings, note: nil
        ))
    }

    func addNote(_ note: String, day: Int64, mealTypeId: Int64) async {
        let text = note.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        nextId += 1
        meals.value.append(PlannedMeal(
            id: nextId, day: day, mealTypeId: mealTypeId, recipeId: nil,
            title: nil, imageUrl: nil, servings: nil, note: text
        ))
    }

    func move(entryId: Int64, day: Int64, mealTypeId: Int64) async {
        guard let meal = meals.value.first(where: { $0.id == entryId }) else { return }
        meals.value.removeAll { $0.id == entryId }
        meals.value.append(PlannedMeal(
            id: meal.id, day: day, mealTypeId: mealTypeId, recipeId: meal.recipeId,
            title: meal.title, imageUrl: meal.imageUrl, servings: meal.servings, note: meal.note
        ))
    }

    func delete(entryId: Int64) async -> DeletedMeal? {
        guard let meal = meals.value.first(where: { $0.id == entryId }) else { return nil }
        meals.value.removeAll { $0.id == entryId }
        deleted[meal.id] = meal
        return DeletedMeal(entry: MealPlanEntryRecord(
            id: meal.id, day: meal.day, mealTypeId: meal.mealTypeId, recipeId: meal.recipeId,
            servings: meal.servings, note: meal.note, sortOrder: 0, updatedAt: 0
        ))
    }

    func restore(_ deleted: DeletedMeal) async {
        guard let meal = self.deleted.removeValue(forKey: deleted.entry.id) else { return }
        meals.value.append(meal)
    }

    func addMealType(name: String) async {
        let text = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        nextId += 1
        types.value.append(MealType(id: nextId, name: text, builtInKey: nil, sortOrder: types.value.count))
    }

    func renameMealType(id: Int64, name: String) async {
        types.value = types.value.map {
            $0.id == id ? MealType(id: $0.id, name: name, builtInKey: $0.builtInKey, sortOrder: $0.sortOrder) : $0
        }
    }

    func reorderMealTypes(_ orderedIds: [Int64]) async {
        let byId = Dictionary(uniqueKeysWithValues: types.value.map { ($0.id, $0) })
        types.value = orderedIds.enumerated().compactMap { index, id in
            byId[id].map { MealType(id: $0.id, name: $0.name, builtInKey: $0.builtInKey, sortOrder: index) }
        }
    }

    func deleteMealType(id: Int64) async {
        guard let type = types.value.first(where: { $0.id == id }), !type.isBuiltIn else { return }
        meals.value = meals.value.map {
            $0.mealTypeId == id
                ? PlannedMeal(id: $0.id, day: $0.day, mealTypeId: Self.dinner, recipeId: $0.recipeId,
                              title: $0.title, imageUrl: $0.imageUrl, servings: $0.servings, note: $0.note)
                : $0
        }
        types.value.removeAll { $0.id == id }
    }
}

/// A pinned today and first day of the week. Defaults: Wednesday 2026-09-23, weeks from Monday.
final class FakePlanCalendar: PlanCalendar {
    /// 2026-09-23, a Wednesday.
    static let wednesday: Int64 = 20_719
    static let mondayFirst = 2
    static let sundayFirst = 1

    var todayValue: Int64
    var firstDay: Int

    init(today: Int64 = wednesday, firstDayOfWeek: Int = mondayFirst) {
        todayValue = today
        firstDay = firstDayOfWeek
    }

    func today() -> Int64 { todayValue }
    func firstDayOfWeek() -> Int { firstDay }
}
