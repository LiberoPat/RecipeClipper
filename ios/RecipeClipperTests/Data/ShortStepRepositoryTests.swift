import Combine
import XCTest
@testable import RecipeClipper

/// Chef mode's cache (#100) against real SQLite: checked, keyed by step text and language, pruned.
@MainActor
final class ShortStepRepositoryTests: XCTestCase {
    private let bake = "Bake for 25 to 30 minutes, until the top is golden and springy."
    private let whisk = "Whisk the eggs with the sugar until pale, thick and doubled."

    private func cake(_ steps: [String], language: String = "en") -> Recipe {
        Recipe(
            name: "Cake", image: nil, ingredients: ["2 eggs"], instructions: steps, prepTime: nil,
            cookTime: nil, totalTime: nil, yield: "8", sourceUrl: "https://example.com/cake", id: 7, language: language
        )
    }

    private func shown(_ repository: DefaultShortStepRepository, _ recipe: Recipe) async -> [String?] {
        await withCheckedContinuation { continuation in
            var cancellable: AnyCancellable?
            cancellable = repository.observe(recipe).first().sink { value in
                continuation.resume(returning: value)
                cancellable?.cancel()
            }
        }
    }

    func testPassingStepsAreSavedTheRestShowAsWrittenAndAreNotAskedAgain() async throws {
        let model = FakeStepShortener(written: [bake: "Bake 25–30 min until golden.", whisk: "Whisk 3 eggs with sugar."])
        let recipe = cake([bake, whisk, "Serve warm."])
        let (_, repository) = try await makeShortStepRepository(recipe, model: model)
        await repository.fill(recipe)

        let saved = await shown(repository, recipe)
        XCTAssertEqual(saved, ["Bake 25–30 min until golden.", nil, nil])
        XCTAssertEqual(model.asked, [bake, whisk])
        await repository.fill(recipe)
        XCTAssertEqual(model.asked, [bake, whisk])
    }

    func testAStepTheModelCouldNotDoIsAskedAgainAndAChangedStepIsWrittenAgain() async throws {
        let model = FakeStepShortener()
        let recipe = cake([bake])
        let (db, repository) = try await makeShortStepRepository(recipe, model: model)
        await repository.fill(recipe)
        model.written[bake] = "Bake 25–30 min until golden."
        await repository.fill(recipe)
        XCTAssertEqual(model.asked, [bake, bake])

        let edited = cake([whisk])
        model.written[whisk] = "Whisk eggs and sugar until pale."
        await repository.fill(edited)
        let saved = await shown(repository, edited)
        XCTAssertEqual(saved, ["Whisk eggs and sugar until pale."])
        let rows = try await db.read { try ShortStepDao(db: $0).rows(recipeId: 7, language: "en") }
        XCTAssertEqual(rows.count, 1)
        let other = await shown(repository, cake([whisk], language: "de"))
        XCTAssertEqual(other, [nil])
    }
}
