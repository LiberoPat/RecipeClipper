import XCTest
@testable import RecipeClipper

/// The Steps section's "Chef mode" switch (#100), over a fake model.
@MainActor
final class SettingsChefModeTests: XCTestCase {
    private let preferences = FakeAppPreferences()

    private func makeViewModel(support: ChefSupport, flagOn: Bool = true) async throws -> SettingsViewModel {
        let recipe = Recipe(
            name: "Cake", image: nil, ingredients: [], instructions: ["Mix."], prepTime: nil, cookTime: nil,
            totalTime: nil, yield: nil, sourceUrl: "https://example.com/cake", id: 1
        )
        let (_, shortSteps) = try await makeShortStepRepository(recipe, model: FakeStepShortener(support: support))
        let flags = FeatureFlags(store: MemoryFeatureFlagStore())
        if flagOn { flags.set(.chefMode, true) }
        return SettingsViewModel(
            preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles(), flags: flags,
            shortSteps: shortSteps
        )
    }

    func testHiddenWithoutTheFlag() async throws {
        let vm = try await makeViewModel(support: .available(["en"]), flagOn: false)
        XCTAssertFalse(vm.showsSteps)
    }

    func testTurnsOnWhereThePhoneCan() async throws {
        let vm = try await makeViewModel(support: .available(["en", "de"]))
        XCTAssertTrue(vm.showsSteps)
        await vm.onStepsShown()?.value
        XCTAssertEqual(vm.uiState.chefSupport, .available(["en", "de"]))
        vm.onChefModeChange(true)
        XCTAssertTrue(preferences.chefMode)
        XCTAssertTrue(vm.uiState.chefMode)
    }

    func testStaysOffWhereThePhoneCannot() async throws {
        let vm = try await makeViewModel(support: .notEnabled)
        await vm.onStepsShown()?.value
        XCTAssertEqual(vm.uiState.chefSupport, .notEnabled)
        vm.onChefModeChange(true)
        XCTAssertFalse(preferences.chefMode)
    }
}
