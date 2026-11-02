import XCTest
@testable import RecipeClipper

/// The home-screen widgets' content (#309); Android's `WidgetContentTest` has the same cases.
final class WidgetContentTests: XCTestCase {
    private let types = [
        MealType(id: 3, name: "Dinner", builtInKey: "dinner", sortOrder: 2),
        MealType(id: 1, name: "Breakfast", builtInKey: "breakfast", sortOrder: 0),
        MealType(id: 9, name: "Pudding", builtInKey: nil, sortOrder: 3)
    ]

    private func meal(_ id: Int64, day: Int64, type: Int64, title: String? = nil, note: String? = nil) -> PlannedMeal {
        PlannedMeal(id: id, day: day, mealTypeId: type, recipeId: title == nil ? nil : id, title: title,
                    imageUrl: nil, servings: nil, note: note)
    }

    func testTonightIsTodaysMealsInMealTypeOrder() {
        let meals = [
            meal(1, day: 100, type: 3, title: "Focaccia"),
            meal(2, day: 100, type: 9, note: " Ice cream "),
            meal(3, day: 101, type: 1, title: "Pancakes"),
            meal(4, day: 100, type: 1, title: "Porridge"),
            meal(5, day: 100, type: 3, note: "Salad")
        ]
        XCTAssertEqual(WidgetContent.tonight(meals, types: types, today: 100), [
            .init(mealType: "Breakfast", title: "Porridge"),
            .init(mealType: "Dinner", title: "Focaccia"),
            .init(mealType: "Dinner", title: "Salad"),
            .init(mealType: "Pudding", title: "Ice cream")
        ])
    }

    func testAMealWithNothingToShowIsLeftOut() {
        XCTAssertEqual(WidgetContent.tonight([meal(1, day: 100, type: 3, note: "  ")], types: types, today: 100), [])
    }

    func testGroceriesAreTheUntickedLinesAsShown() {
        let lines: [(String, Bool)] = [
            ("200 g flour", false), ("100 g flour", false), ("2 onions", false), ("1 cup milk", true),
            ("1 egg", false), ("1 egg", false), ("paper towels", false)
        ]
        let words = LanguageWords.forTag("en")
        let items = lines.enumerated().map { i, line in
            GroceryItem(id: Int64(i + 1), text: line.0, language: "en", aisle: Aisles.of(line.0, words: words),
                        checked: line.1, sortOrder: i + 1)
        }
        // Produce, dairy, baking, other; the ticked milk is in the basket.
        XCTAssertEqual(WidgetContent.groceries(GroceryCombiner.sections(items)),
                       ["2 onions", "2 eggs", "300 g flour", "paper towels"])
    }
}
