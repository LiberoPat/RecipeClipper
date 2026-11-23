import XCTest
@testable import RecipeClipper

/// The Plan's quiet pantry note (#456): which planned ingredients the pantry hasn't got, by name.
/// Android's `PlanPantryNoteTest`; the corpus's `Gap` rows pin the rest to the Kotlin.
final class PlanPantryNoteTests: XCTestCase {

    private func item(_ name: String, inStock: Bool = true, alwaysHave: Bool = false, language: String = "en") -> PantryItem {
        PantryItem(
            id: Int64(abs(name.hashValue % 100_000)), name: name, quantity: nil, language: language, aisle: .other,
            inStock: inStock, alwaysHave: alwaysHave, purchasedDay: nil, expiresDay: nil
        )
    }

    private func gap(_ pantry: [PantryItem], _ lines: String..., language: String? = "en") -> PantryGap? {
        PlanPantryNote.gap(title: "Recipe", language: language, lines: lines, pantry: pantry)
    }

    private func grocery(_ text: String, checked: Bool = false, language: String? = "en") -> GroceryItem {
        GroceryItem(id: Int64(abs(text.hashValue % 100_000)), text: text, language: language, aisle: .other, checked: checked, sortOrder: 0)
    }

    private func meal(_ id: Int64, _ day: Int64, _ lines: String..., recipe: Bool = true) -> PlannedMeal {
        PlannedMeal(
            id: id, day: day, mealTypeId: 1, recipeId: recipe ? id : nil, title: recipe ? "Recipe \(id)" : nil,
            imageUrl: nil, servings: nil, note: recipe ? nil : "Leftovers", language: recipe ? "en" : nil, ingredients: lines
        )
    }

    func testEverythingInThePantrySaysNothing() {
        XCTAssertNil(gap([item("flour"), item("eggs")], "2 cups flour", "3 large eggs"))
    }

    func testAnEmptyPantryOrOneInAnotherLanguageSaysNothing() {
        XCTAssertNil(gap([], "2 cups flour", "1 tbsp tahini"))
        XCTAssertNil(gap([item("Mehl", language: "de")], "2 cups flour", "1 tbsp tahini"))
    }

    func testTheMissingNamesInTheRecipesOrder() {
        XCTAssertEqual(gap([item("flour")], "2 cups flour", "2 tbsp tahini", "1 tsp sumac"), PantryGap(missing: ["tahini", "sumac"]))
    }

    func testThreeAreNamedPastThatTwoAndHowManyMore() {
        let three = gap([item("flour")], "2 tbsp tahini", "1 tsp sumac", "1 lemon")!
        XCTAssertEqual(three.names, ["tahini", "sumac", "lemon"])
        XCTAssertEqual(three.more, 0)
        XCTAssertFalse(three.capped)
        let five = gap([item("flour")], "2 tbsp tahini", "1 tsp sumac", "1 lemon", "2 cloves garlic", "1 cup chickpeas")!
        XCTAssertEqual(five.names, ["tahini", "sumac"])
        XCTAssertEqual(five.more, 3)
        XCTAssertTrue(five.capped)
        XCTAssertEqual(five.missing, ["tahini", "sumac", "lemon", "garlic", "chickpeas"])
    }

    func testHeadingsBlankAndUnnameableLinesAreNotCounted() {
        XCTAssertNil(gap([item("flour")], "For the dough:", "", "2 cups flour", "salt and pepper to taste"))
    }

    func testRiceFlourIsNeverFlour() {
        XCTAssertEqual(gap([item("flour")], "2 cups rice flour"), PantryGap(missing: ["rice flour"]))
        XCTAssertEqual(gap([item("rice flour")], "2 cups flour"), PantryGap(missing: ["flour"]))
        XCTAssertNil(gap([item("flour")], "2 cups all-purpose flour"))
    }

    func testAStapleIsHadAnItemOutOfStockIsMissingTheQuantityNeverMatters() {
        XCTAssertNil(gap([item("salt", inStock: false, alwaysHave: true), item("butter")], "1 tsp salt", "2 kg butter"))
        XCTAssertEqual(gap([item("butter", inStock: false)], "2 tbsp butter"), PantryGap(missing: ["butter"]))
    }

    func testTheModelsSameAnswerTurnsMissingIntoHad() {
        let decided = Decisions(answers: [DecisionQuestion.sameIngredient("scallions", "green onion", language: "en"): "same"])
        XCTAssertNil(PlanPantryNote.gap(title: "r", language: "en", lines: ["2 scallions"], pantry: [item("green onion")], decisions: decided))
    }

    func testGapsSkipNotesThePastAndAnEmptyPantry() {
        let meals = [
            meal(1, 9, "1 tbsp tahini"), meal(2, 10, "1 tbsp tahini", "2 cups flour"),
            meal(3, 11, "2 cups flour"), meal(4, 12, recipe: false),
        ]
        XCTAssertEqual(PlanPantryNote.gaps(meals, pantry: [item("flour")], today: 10), [2: PantryGap(missing: ["tahini"])])
        XCTAssertTrue(PlanPantryNote.gaps(meals, pantry: [], today: 10).isEmpty)
    }

    func testAMissingNameOnTheGroceryListIsCountedNotNamed() {
        let lines = ["2 tbsp tahini", "1 tsp sumac", "2 cups flour"]
        XCTAssertEqual(
            PlanPantryNote.gap(title: "r", language: "en", lines: lines, pantry: [item("flour")], groceries: [grocery("2 tbsp tahini")]),
            PantryGap(missing: ["sumac"], onList: 1)
        )
        let all = PlanPantryNote.gap(
            title: "r", language: "en", lines: lines, pantry: [item("flour")], groceries: [grocery("tahini"), grocery("1 jar sumac")]
        )!
        XCTAssertTrue(all.allOnList)
        XCTAssertEqual(all.onList, 2)
    }

    func testATickedLineAnotherLanguageOrAnotherIngredientDoesntCount() {
        for list in [[grocery("rice flour", checked: true)], [grocery("flour")], [grocery("rice flour", language: "de")]] {
            XCTAssertEqual(
                PlanPantryNote.gap(title: "r", language: "en", lines: ["2 cups rice flour"], pantry: [item("flour")], groceries: list),
                PantryGap(missing: ["rice flour"])
            )
        }
    }

    func testLinesToAddAreTheRenderedLinesNamingWhatIsStillMissing() {
        let gap = PantryGap(missing: ["tahini", "onion"])
        XCTAssertEqual(
            PlanPantryNote.linesToAdd(gap, language: "en", rendered: ["For the sauce:", "3 tbsp tahini", "1 cup flour", "2 onions, sliced", "salt to taste"]),
            ["3 tbsp tahini", "2 onions, sliced"]
        )
        XCTAssertTrue(PlanPantryNote.linesToAdd(gap, language: "xx", rendered: ["3 tbsp tahini"]).isEmpty)
    }
}
