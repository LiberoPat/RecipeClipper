@testable import RecipeClipper

/// Chef mode's model, faked (#100): `written` says what it writes for each step; a step not in
/// there gets nil ("can't right now"). `asked` records every step it was asked for.
final class FakeStepShortener: StepShortener {
    var supportResult: ChefSupport
    var written: [String: String]
    private(set) var asked: [String] = []

    init(support: ChefSupport = .available(["en", "de"]), written: [String: String] = [:]) {
        self.supportResult = support
        self.written = written
    }

    func support() async -> ChefSupport { supportResult }

    func shorten(_ step: String, language: String) async -> String? {
        asked.append(step)
        return written[step]
    }
}

/// An in-memory database holding `recipe`, and the real repository over it with `model`.
@MainActor
func makeShortStepRepository(_ recipe: Recipe, model: FakeStepShortener) async throws -> (AppDatabase, DefaultShortStepRepository) {
    let db = try AppDatabase(path: nil)
    try await db.write { conn in
        _ = try RecipeDao(db: conn).insert(RecipeRecord(
            id: recipe.id, sourceUrl: recipe.sourceUrl, title: recipe.name, imageUrl: nil,
            ingredients: recipe.ingredients, instructions: recipe.instructions, prepTime: nil, cookTime: nil,
            totalTime: nil, servings: recipe.yield, sourceType: "BLOG", lastViewedAt: 1, language: recipe.language
        ))
    }
    return (db, DefaultShortStepRepository(db: db, shortener: model, clock: DataTestClock()))
}
