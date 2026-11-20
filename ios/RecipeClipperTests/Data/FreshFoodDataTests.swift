import XCTest
@testable import RecipeClipper

/// Fresh food's note on the Week (#396) over the real, SQLite-backed repositories: the planned
/// recipes' ingredient lines come from `GroceryRepository.plannedIngredients`, the meals from
/// `observeDays`, and a swap is two real moves.
@MainActor
final class FreshFoodDataTests: XCTestCase {
    private var db: AppDatabase!
    private let clock = TestClock()
    private let today = FakePlanCalendar.wednesday

    override func setUpWithError() throws { db = try AppDatabase(path: nil) }

    private func recipe(_ title: String, _ ingredients: [String]) async throws -> Int64 {
        try await db.write { conn in
            try RecipeDao(db: conn).insert(RecipeRecord(
                sourceUrl: "https://example.com/\(title)", title: title, imageUrl: nil,
                ingredients: ingredients, instructions: ["Cook."], prepTime: nil, cookTime: nil, totalTime: nil,
                servings: "4", sourceType: SourceType.blog.rawValue, lastViewedAt: 1
            ))
        }
    }

    func testTheWeeksNoteComesFromTheRealPlanAndASwapMovesBothMeals() async throws {
        let plan = DefaultMealPlanRepository(db: db, clock: clock)
        let groceries = DefaultGroceryRepository(db: db, clock: clock)
        let types = try await db.read { try MealPlanDao(db: $0).mealTypes() }
        let dinner = try XCTUnwrap(types.first { $0.builtInKey == MealType.dinner }).id
        let pasta = try await recipe("Carbonara", ["400 g spaghetti", "4 egg yolks"])
        let adobo = try await recipe("Adobo", ["2 lb chicken thighs", "1/2 cup soy sauce"])
        await plan.addRecipe(recipeId: pasta, day: today, mealTypeId: dinner, servings: nil)
        await plan.addRecipe(recipeId: adobo, day: today + 5, mealTypeId: dinner, servings: nil)

        let vm = WeekViewModel(
            plan: plan, recipes: FakeRecipeRepository(), calendar: FakePlanCalendar(), sleep: immediateSleep,
            groceries: groceries, freshFood: true
        )
        await settleMain(until: { !vm.uiState.freshNudges.isEmpty })

        let nudge = try XCTUnwrap(vm.uiState.freshNudges.values.first)
        XCTAssertEqual(nudge.name, "Chicken thighs")
        XCTAssertEqual(nudge.keeps, "1-2")
        XCTAssertEqual(nudge.day, today + 5)
        XCTAssertEqual(nudge.swapDay, today)

        vm.onFreshSwap(nudge.key)
        await settleMain(until: { vm.uiState.freshNudges.isEmpty })
        XCTAssertTrue(vm.uiState.freshNudges.isEmpty)
        let days = try await db.read { try MealPlanDao(db: $0).days(start: self.today, end: self.today + 6) }
        XCTAssertEqual(Dictionary(uniqueKeysWithValues: days.map { ($0.recipeId ?? 0, $0.day) }), [pasta: today + 5, adobo: today])
    }
}
