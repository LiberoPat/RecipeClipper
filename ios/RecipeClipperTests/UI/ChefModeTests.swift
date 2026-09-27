import XCTest
@testable import RecipeClipper

/// `ChefMode` on its own (#169; Android's `ChefModeTest`): the model's short steps and count
/// brackets, with the model faked.
@MainActor
final class ChefModeTests: XCTestCase {
    private let oven = "Preheat the oven to 350°F and butter a 9-inch round cake tin."
    private let bake = "Bake for 25 to 30 minutes, until the top is golden and springy."
    private let shortOven = "Preheat oven to 350°F; butter a 9-inch tin."
    private let apples = "3 large apples, peeled and sliced (about 3 cups)"

    private func recipe(language: String = "en", yield: String? = "4 servings") -> Recipe {
        Recipe(
            name: "Cake", image: nil, ingredients: [apples], instructions: [oven, bake], prepTime: nil, cookTime: nil,
            totalTime: nil, yield: yield, sourceUrl: "https://example.com/cake", id: 1, language: language
        )
    }

    private func flags(chef: Bool, countBrackets: Bool = false) -> FeatureFlags {
        let flags = FeatureFlags(store: MemoryFeatureFlagStore())
        flags.set(.chefMode, chef)
        flags.set(.aiCountBrackets, countBrackets)
        return flags
    }

    func testShortStepsComeWithBothTheFlagAndTheSettingOnAndGoWithEither() async throws {
        let model = FakeStepShortener(written: [oven: shortOven])
        let (_, repository) = try await makeShortStepRepository(recipe(), model: model)
        let kept = recipe()
        var changes = 0

        let unflagged = ChefMode(shortSteps: repository, flags: flags(chef: false), decisions: nil, setting: true)
        unflagged.keptRecipe = { kept }
        unflagged.start()
        await settleMain()
        XCTAssertEqual(unflagged.shortSteps, [])
        XCTAssertEqual(model.asked, [], "the setting alone writes nothing")

        let chef = ChefMode(shortSteps: repository, flags: flags(chef: true), decisions: nil, setting: false)
        chef.keptRecipe = { kept }
        chef.onShortSteps = { changes += 1 }
        chef.start()
        await settleMain()
        XCTAssertEqual(chef.shortSteps, [])
        XCTAssertEqual(model.asked, [], "the flag alone writes nothing")

        chef.onSetting(true)
        await settleMain(until: { chef.shortSteps.first == shortOven })
        XCTAssertEqual(chef.shortSteps, [shortOven, nil])
        XCTAssertGreaterThan(changes, 0)

        chef.onSetting(false)
        XCTAssertEqual(chef.shortSteps, [])
    }

    func testNoKeptRecipeOrALanguageTheModelCantWriteGetsNoShortSteps() async throws {
        let model = FakeStepShortener(written: [oven: shortOven])
        let (_, repository) = try await makeShortStepRepository(recipe(), model: model)
        for kept in [nil, recipe(language: "ja")] {
            let chef = ChefMode(shortSteps: repository, flags: flags(chef: true), decisions: nil, setting: true)
            chef.keptRecipe = { kept }
            chef.start()
            await settleMain()
            XCTAssertEqual(chef.shortSteps, [])
        }
        XCTAssertEqual(model.asked, [])
    }

    func testCountBracketsAreAskedOnlyWithTheirFlagAndAStepperAndTheAnswerIsPassedOn() async {
        let question = DecisionQuestion.countBracket(apples, language: "en")
        let decisions = FakeDecisionRepository([question: "total"])
        let flags = flags(chef: false)
        let chef = ChefMode(shortSteps: nil, flags: flags, decisions: decisions, setting: false)
        var changes = 0
        chef.onDecisions = { changes += 1 }
        chef.observeDecisions()
        await settleMain()
        let content = RecipeRenderer.content(recipe(), settings: RecipeRenderer.Settings())

        chef.askCountBrackets(content)
        await settleMain()
        XCTAssertEqual(decisions.asked, [], "off without aiCountBrackets")
        XCTAssertEqual(changes, 0)

        flags.set(.aiCountBrackets, true)
        chef.askCountBrackets(RecipeRenderer.content(recipe(yield: nil), settings: RecipeRenderer.Settings()))
        await settleMain()
        XCTAssertEqual(decisions.asked, [], "no stepper, nothing to scale")

        chef.askCountBrackets(content)
        await settleMain(until: { changes == 1 })
        XCTAssertEqual(decisions.asked, [question])
        XCTAssertEqual(chef.decisions, Decisions(answers: [question: "total"]))
        XCTAssertEqual(changes, 1)
    }
}
