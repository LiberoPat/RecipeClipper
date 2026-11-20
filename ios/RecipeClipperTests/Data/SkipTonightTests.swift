import XCTest
@testable import RecipeClipper

/// Android's SkipTonightRepositoryTest (#398) against real SQLite, through the repository: every
/// move of a skip lands in one transaction (or none do), the plan order and meal types survive,
/// and Undo gives back the rows exactly as they were.
final class SkipTonightTests: XCTestCase {
    private var db: AppDatabase!
    private var repo: DefaultMealPlanRepository!
    private let today: Int64 = 20_000
    private var lastDay: Int64 { today + 730 }

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        repo = DefaultMealPlanRepository(db: db, clock: DataTestClock(1_000))
    }

    override func tearDown() {
        repo = nil
        db = nil
    }

    private func typeId(_ key: String) async throws -> Int64 {
        try await db.read { try MealPlanDao(db: $0).mealTypes() }.first { $0.builtInKey == key }!.id
    }

    private func makeRecipe(_ n: Int) async throws -> Int64 {
        try await db.write { [today] in
            try RecipeDao(db: $0).upsert(dataRecipeRecord("https://a.com/\(n)", viewedAt: Int64(n)), historyLimit: historyLimit, today: today)
        }
    }

    /// Plans a meal and returns its id; `recipe` nil plans a note.
    private func plan(_ day: Int64, _ type: String = "dinner", recipe: Int? = nil, note: String = "note") async throws -> Int64 {
        let typeId = try await typeId(type)
        if let recipe {
            await repo.addRecipe(recipeId: try await makeRecipe(recipe), day: day, mealTypeId: typeId, servings: 3)
        } else {
            await repo.addNote(note, day: day, mealTypeId: typeId)
        }
        return try await entries(from: day).filter { $0.day == day && $0.mealTypeId == typeId }.map(\.id).max()!
    }

    private func entries(from day: Int64 = 0) async throws -> [MealPlanEntryRecord] {
        try await db.read { try MealPlanDao(db: $0).entriesFrom(day) }
    }

    private func days() async throws -> [[Int64]] { try await entries().map { [$0.id, $0.day] } }

    private func move(_ id: Int64, _ from: Int64, _ to: Int64) -> PlanShift.Move {
        PlanShift.Move(mealId: id, fromDay: from, toDay: to)
    }

    func testPushingTheWeekMovesTheSkippedMealAndEveryLaterOneAndKeepsTheirTypes() async throws {
        let tonight = try await plan(today, recipe: 1)
        let lunch = try await plan(today + 1, "lunch", recipe: 2)
        let note = try await plan(today + 3, note: "Eat out")
        let typesBefore = Dictionary(uniqueKeysWithValues: try await entries().map { ($0.id, $0.mealTypeId) })

        let skipped = await repo.skip(mealId: tonight, choice: .pushWeek, today: today, lastDay: lastDay)

        XCTAssertEqual(skipped?.result.moved, [move(tonight, today, today + 1), move(lunch, today + 1, today + 2), move(note, today + 3, today + 4)])
        let after = try await days()
        XCTAssertEqual(after, [[tonight, today + 1], [lunch, today + 2], [note, today + 4]])
        let typesAfter = Dictionary(uniqueKeysWithValues: try await entries().map { ($0.id, $0.mealTypeId) })
        XCTAssertEqual(typesAfter, typesBefore)
    }

    func testAMealBeforeTheSkippedOneNeverMoves() async throws {
        let earlier = try await plan(today, recipe: 1)
        let skippedId = try await plan(today + 1, recipe: 2)
        let later = try await plan(today + 2, recipe: 3)

        _ = await repo.skip(mealId: skippedId, choice: .pushWeek, today: today, lastDay: lastDay)

        let after = try await days()
        XCTAssertEqual(after, [[earlier, today], [skippedId, today + 2], [later, today + 3]])
    }

    func testUndoGivesBackExactlyWhatWasThere() async throws {
        let first = try await plan(today, recipe: 1)
        _ = try await plan(today, recipe: 2) // two dinners in one slot: their order must survive
        _ = try await plan(today + 2, "lunch", note: "Leftovers")
        let before = try await entries()

        let skippedMaybe = await repo.skip(mealId: first, choice: .pushWeek, today: today, lastDay: lastDay)
        let skipped = try XCTUnwrap(skippedMaybe)
        let moved = try await entries()
        XCTAssertNotEqual(moved, before)
        await repo.undoSkip(skipped)

        let restored = try await entries()
        XCTAssertEqual(restored, before) // days, order, updatedAt, uid: all of it
    }

    func testNextFreeDayMovesOnlyThatMealToTheFirstDayWithoutItsType() async throws {
        let tonight = try await plan(today, recipe: 1)
        _ = try await plan(today + 1, recipe: 2)
        _ = try await plan(today + 2, note: "Eat out")
        let lunch = try await plan(today + 3, "lunch", recipe: 3)
        let before = try await entries()

        let skippedMaybe = await repo.skip(mealId: tonight, choice: .nextFreeDay, today: today, lastDay: lastDay)
        let skipped = try XCTUnwrap(skippedMaybe)

        XCTAssertEqual(skipped.result.moved, [move(tonight, today, today + 3)])
        let after = try await entries()
        XCTAssertEqual(after.first { $0.id == tonight }?.day, today + 3)
        XCTAssertEqual(after.first { $0.id == lunch }?.day, today + 3) // that day's lunch is untouched
        XCTAssertEqual(after.count, before.count)

        await repo.undoSkip(skipped)
        let restored = try await entries()
        XCTAssertEqual(restored, before)
    }

    func testRemoveDropsTheMealNotTheRecipeAndUndoPutsItBack() async throws {
        let tonight = try await plan(today, recipe: 1)
        let other = try await plan(today + 1, recipe: 2)
        let before = try await entries()
        let recipeId = try XCTUnwrap(before.first { $0.id == tonight }?.recipeId)

        let skippedMaybe = await repo.skip(mealId: tonight, choice: .remove, today: today, lastDay: lastDay)
        let skipped = try XCTUnwrap(skippedMaybe)

        XCTAssertEqual(skipped.result.removed, [tonight])
        let after = try await days()
        XCTAssertEqual(after, [[other, today + 1]])
        let recipe = try await db.read { try RecipeDao(db: $0).get(recipeId) }
        XCTAssertNotNil(recipe)

        await repo.undoSkip(skipped)
        let restored = try await entries()
        XCTAssertEqual(restored, before)
    }

    func testAMealInThePastCannotBeSkipped() async throws {
        let yesterday = try await plan(today - 1, recipe: 1)
        let before = try await entries()

        for choice in PlanShift.Choice.allCases {
            let skipped = await repo.skip(mealId: yesterday, choice: choice, today: today, lastDay: lastDay)
            XCTAssertNil(skipped)
        }
        let after = try await entries()
        XCTAssertEqual(after, before)
    }

    func testAMealThatIsGoneHasNothingToSkip() async {
        let skipped = await repo.skip(mealId: 12_345, choice: .pushWeek, today: today, lastDay: lastDay)
        XCTAssertNil(skipped)
    }

    func testTheMealsMovedStayOneTransactionWhenAWriteFails() async throws {
        let tonight = try await plan(today, recipe: 1)
        _ = try await plan(today + 1, recipe: 2)
        let last = try await plan(today + 2, recipe: 3)
        let before = try await entries()
        // The third move cannot be written: the first two must not stay.
        try await db.write { conn in
            try conn.run(
                "CREATE TRIGGER refuse BEFORE UPDATE OF day ON meal_plan_entries WHEN NEW.id = \(last) "
                    + "BEGIN SELECT RAISE(ABORT, 'refused'); END"
            )
        }

        let skipped = await repo.skip(mealId: tonight, choice: .pushWeek, today: today, lastDay: lastDay)

        XCTAssertNil(skipped)
        let after = try await entries()
        XCTAssertEqual(after, before)
    }

    func testTheSecondSkipsUndoDoesNotTouchTheFirst() async throws {
        let a = try await plan(today, recipe: 1)
        let b = try await plan(today + 4, recipe: 2)
        _ = await repo.skip(mealId: a, choice: .nextFreeDay, today: today, lastDay: lastDay) // a: today + 1
        let secondMaybe = await repo.skip(mealId: b, choice: .nextFreeDay, today: today, lastDay: lastDay)
        let second = try XCTUnwrap(secondMaybe) // b: today + 5

        await repo.undoSkip(second)

        let after = try await days()
        XCTAssertEqual(after, [[a, today + 1], [b, today + 4]])
    }
}
