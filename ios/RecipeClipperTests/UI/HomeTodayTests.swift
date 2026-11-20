import XCTest
@testable import RecipeClipper

/// Home's Today card (#402): the earliest meal today not yet cooked, from Week, in the meal
/// types' order; a recipe cooked today drops off. Port of the Today tests in Android's
/// `HomeViewModelTest`.
@MainActor
final class HomeTodayTests: XCTestCase {
    private let clock = TestClock(now: 0)
    private let repository = FakeRecipeRepository()
    private let plan = FakeMealPlanRepository()
    private let cooked = FakeCookedPhotoRepository()
    /// Today at the test clock's 0, in whatever zone the machine is in: the day the card follows.
    private let today = PlanDays.today(millis: 0)

    override func setUp() {
        super.setUp()
        cooked.today = today
    }

    private func meal(
        _ id: Int64, _ type: Int64, _ recipeId: Int64?, _ title: String?, day: Int64? = nil, note: String? = nil, servings: Int? = nil
    ) -> PlannedMeal {
        PlannedMeal(id: id, day: day ?? today, mealTypeId: type, recipeId: recipeId, title: title, imageUrl: nil, servings: servings, note: note)
    }

    private func home(withCooked: Bool = true) async -> HomeViewModel {
        let vm = HomeViewModel(
            repository: repository, clock: clock, sleep: clock.sleep,
            mealPlan: plan, cooked: withCooked ? cooked : nil
        )
        await settleMain()
        return vm
    }

    func testTodaysEarliestMealLeadsTheOthersFollowInMealTypeOrder() async {
        // Planned out of order, and dinner first in the plan: the types' order decides.
        plan.meals.send([
            meal(1, FakeMealPlanRepository.dinner, 12, "Lemon chicken", servings: 6),
            meal(2, FakeMealPlanRepository.snack, 13, "Apple slices"),
            meal(3, FakeMealPlanRepository.breakfast, 11, "Porridge"),
            meal(4, FakeMealPlanRepository.lunch, 14, "Soup"),
            meal(5, FakeMealPlanRepository.dinner, 15, "Tomorrow's", day: today + 1),
        ])
        let vm = await home()

        let card = vm.uiState.today
        XCTAssertEqual(card?.lead.title, "Porridge")
        XCTAssertEqual(card?.lead.recipeId, 11)
        XCTAssertEqual(card?.others.map(\.title), ["Soup", "Lemon chicken", "Apple slices"])
        XCTAssertEqual(card?.others.map(\.mealType), ["Lunch", "Dinner", "Snack"])
        XCTAssertEqual(card?.others[1].servings, 6)
    }

    func testAUsersOwnMealTypeSitsWhereTheCookPutIt() async {
        plan.types.send(
            [MealType(id: 9, name: "Brunch", builtInKey: nil, sortOrder: 0)]
                + FakeMealPlanRepository.defaultTypes.map { MealType(id: $0.id, name: $0.name, builtInKey: $0.builtInKey, sortOrder: $0.sortOrder + 1) }
        )
        plan.meals.send([meal(1, FakeMealPlanRepository.breakfast, 11, "Porridge"), meal(2, 9, 12, "Eggs")])
        let vm = await home()

        XCTAssertEqual(vm.uiState.today?.lead.title, "Eggs")
        XCTAssertEqual(vm.uiState.today?.others.map(\.title), ["Porridge"])
    }

    func testAMealCookedTodayDropsOffAndTheEarliestOneLeftLeads() async {
        plan.meals.send([
            meal(1, FakeMealPlanRepository.breakfast, 11, "Porridge"),
            meal(2, FakeMealPlanRepository.lunch, 14, "Soup"),
            meal(3, FakeMealPlanRepository.dinner, 12, "Lemon chicken"),
        ])
        let vm = await home()
        XCTAssertEqual(vm.uiState.today?.lead.title, "Porridge")

        // "Mark as cooked", or a photo, or a finished run: a cooking entry dated today.
        cooked.mark(recipeId: 11)
        await settleMain()
        XCTAssertEqual(vm.uiState.today?.lead.title, "Soup")
        XCTAssertEqual(vm.uiState.today?.others.map(\.title), ["Lemon chicken"])

        // An entry from another day doesn't count.
        cooked.mark(recipeId: 14, day: today - 1)
        await settleMain()
        XCTAssertEqual(vm.uiState.today?.lead.title, "Soup")
    }

    func testEveryMealCookedTodayAndNothingMidCookIsNoCard() async {
        plan.meals.send([
            meal(1, FakeMealPlanRepository.lunch, 14, "Soup"),
            meal(2, FakeMealPlanRepository.dinner, 12, "Lemon chicken"),
        ])
        let vm = await home()
        cooked.mark(recipeId: 14)
        cooked.mark(recipeId: 12)
        await settleMain()

        XCTAssertNil(vm.uiState.today)
    }

    func testANoteShowsItsTextWithNoRecipe() async {
        plan.meals.send([
            meal(1, FakeMealPlanRepository.lunch, nil, nil, note: "Leftovers"),
            meal(2, FakeMealPlanRepository.dinner, 12, "Lemon chicken"),
        ])
        let vm = await home()

        XCTAssertEqual(vm.uiState.today?.lead.title, "Leftovers")
        XCTAssertNil(vm.uiState.today?.lead.recipeId, "a note has no Start cooking")
        XCTAssertEqual(vm.uiState.today?.others.map(\.title), ["Lemon chicken"])
    }

    func testNothingPlannedTodayAndNothingMidCookIsNoCard() async {
        plan.meals.send([meal(1, FakeMealPlanRepository.dinner, 12, "Tomorrow's", day: today + 1)])
        repository.recent.send([testSummary(1)])
        let vm = await home()

        XCTAssertNil(vm.uiState.today)
        XCTAssertEqual(vm.uiState.continueCooking?.id, 1, "the latest recipe still counts for the empty-library check")
    }

    func testMidCookKeepsTheResumeWithTodaysCardBesideItForTheScreenToChoose() async {
        plan.meals.send([meal(1, FakeMealPlanRepository.dinner, 12, "Lemon chicken")])
        repository.recent.send([testSummary(1)])
        repository.recipes.send([1: Recipe(
            name: "Recipe 1", image: nil, ingredients: ["1 egg"], instructions: (1 ... 4).map { "Step \($0)" },
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "https://example.com/1", id: 1,
            cook: CookProgress(active: true, currentStep: 1)
        )])
        let vm = await home()

        XCTAssertEqual(vm.uiState.resume?.step, 2)
        XCTAssertEqual(vm.uiState.today?.lead.title, "Lemon chicken")
    }

    func testTheCardFollowsThePlanAsItChanges() async {
        let vm = await home()
        XCTAssertNil(vm.uiState.today)

        plan.meals.send([meal(1, FakeMealPlanRepository.dinner, 12, "Lemon chicken")])
        await settleMain()
        XCTAssertEqual(vm.uiState.today?.lead.title, "Lemon chicken")

        plan.meals.send([])
        await settleMain()
        XCTAssertNil(vm.uiState.today)
    }

    func testWhenTheDayRollsOverTheCardIsTheNewDays() async {
        plan.meals.send([
            meal(1, FakeMealPlanRepository.dinner, 12, "Today's dinner", day: today),
            meal(2, FakeMealPlanRepository.breakfast, 11, "Tomorrow's porridge", day: today + 1),
        ])
        let vm = await home()
        XCTAssertEqual(vm.uiState.today?.lead.title, "Today's dinner")

        await clock.advance(by: PlanDays.millisPerDay)
        await settleMain()
        XCTAssertEqual(vm.uiState.today?.lead.title, "Tomorrow's porridge")
    }

    func testWithoutAPlanThereIsNoTodayCard() async {
        let vm = HomeViewModel(repository: repository)
        await settleMain()

        XCTAssertNil(vm.uiState.today)
    }
}
