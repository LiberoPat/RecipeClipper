import XCTest
@testable import RecipeClipper

/// The free tier (#107) through the ViewModels, over a fake store: the recipe that wasn't kept,
/// the editor on a full library, the Settings row, Developer settings' override, the Recipes
/// count and the share card. Android's RecipeNotKeptTest, SettingsUnlockTest and friends.
@MainActor
final class FreeTierViewModelTests: XCTestCase {
    private let soup = Recipe(
        name: "Soup", image: nil, ingredients: ["1 leek"], instructions: ["Simmer."],
        prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "https://a.com/soup"
    )

    private func flags(freeTier: Bool) -> FeatureFlags {
        FeatureFlags(store: MemoryFeatureFlagStore(freeTier ? ["freeTier": true] : [:]), isDebug: false)
    }

    private func notKeptRecipe(_ store: FakeEntitlements) async -> (RecipeViewModel, FakeRecipeRepository) {
        let repository = FakeRecipeRepository()
        repository.importResult = .notKept(soup)
        let vm = RecipeViewModel(
            recipeId: nil, url: "https://a.com/soup", repository: repository, preferences: FakeAppPreferences(),
            clock: TestClock(), entitlements: store
        )
        await settleMain()
        return (vm, repository)
    }

    func testARecipeThatWasNotKeptIsShownAndUnlockingKeepsIt() async {
        let store = FakeEntitlements()
        let (vm, repository) = await notKeptRecipe(store)
        XCTAssertTrue(vm.uiState.notKept)
        XCTAssertEqual(vm.uiState.content.success?.recipe.name, "Soup")

        vm.onUnlock()
        await settleMain()
        XCTAssertEqual(store.purchases, 1)
        XCTAssertEqual(repository.keepCalls.map(\.name), ["Soup"])
        XCTAssertFalse(vm.uiState.notKept)
        XCTAssertEqual(vm.uiState.content.success?.recipe.id, 1)
    }

    func testAFailedPurchaseKeepsNothingAndSaysSo() async {
        let store = FakeEntitlements()
        store.purchaseOutcome = .failed
        let (vm, repository) = await notKeptRecipe(store)
        vm.onUnlock()
        await settleMain()
        XCTAssertTrue(repository.keepCalls.isEmpty)
        XCTAssertTrue(vm.uiState.notKept)
        XCTAssertEqual(vm.uiState.unlockNotice, .failed)
    }

    func testAFullLibraryKeepsTheEditorOpenAndUnlockThenSaves() async {
        let repository = FakeRecipeRepository()
        var unsaved = soup
        unsaved.id = 0
        repository.addManualResult = unsaved
        let store = FakeEntitlements()
        let vm = EditRecipeViewModel(recipeId: nil, repository: repository, entitlements: store)
        vm.onDraftChange(RecipeDraft(name: "Toast", instructionsText: "Toast the bread."))
        vm.onSave()
        await settleMain()
        XCTAssertTrue(vm.uiState.libraryFull)
        XCTAssertNil(vm.uiState.savedId)

        var saved = soup
        saved.id = 9
        repository.addManualResult = saved
        vm.onUnlock()
        await settleMain()
        XCTAssertFalse(vm.uiState.libraryFull)
        XCTAssertEqual(vm.uiState.savedId, 9)
    }

    private func settings(_ flags: FeatureFlags, _ store: FakeEntitlements) -> SettingsViewModel {
        SettingsViewModel(
            preferences: FakeAppPreferences(), backups: FakeBackupRepository(), files: FakeBackupFiles(),
            flags: flags, entitlements: store
        )
    }

    func testTheSettingsRowOnlyWithTheFlagThenUnlocks() async {
        let store = FakeEntitlements(UnlockState(price: "$2.99"))
        XCTAssertNil(settings(flags(freeTier: false), store).unlockRow)

        let vm = settings(flags(freeTier: true), store)
        XCTAssertEqual(vm.unlockRow, UnlockRow(unlocked: false, price: "$2.99"))
        vm.onUnlock()
        await settleMain()
        XCTAssertEqual(vm.unlockRow?.unlocked, true)
        XCTAssertEqual(vm.unlockRow?.busy, false)
        XCTAssertNil(vm.uiState.unlockNotice)
    }

    func testARestoreWithNothingToRestoreSaysSo() async {
        let store = FakeEntitlements()
        let vm = settings(flags(freeTier: true), store)
        vm.onRestore()
        await settleMain()
        XCTAssertEqual(store.restores, 1)
        XCTAssertEqual(vm.uiState.unlockNotice, .nothingToRestore)
        XCTAssertEqual(vm.unlockRow?.unlocked, false)
    }

    func testTheDeveloperOverrideUnlocksAndResetClearsIt() {
        let flags = flags(freeTier: true)
        let developer = DeveloperSettingsViewModel(flags: flags)
        let vm = settings(flags, FakeEntitlements())
        developer.onUnlockedOverrideChange(true)
        XCTAssertTrue(developer.uiState.unlockedOverride)
        XCTAssertTrue(developer.uiState.anyChanged)
        XCTAssertEqual(vm.unlockRow?.unlocked, true)
        developer.onReset()
        XCTAssertFalse(developer.uiState.unlockedOverride)
        XCTAssertNil(vm.unlockRow, "Reset turned the free tier off too")
    }

    func testTheRecipesCountIsOnlyOnTheFreeTier() async {
        let repository = FakeRecipeRepository()
        repository.history.send((1...12).map { testSummary($0) })
        let store = FakeEntitlements()
        let policy = LibraryPolicy(flags: flags(freeTier: true), entitlements: store)
        let vm = RecipesViewModel(repository: repository, sleep: immediateSleep, library: policy)
        await settleMain()
        XCTAssertEqual(vm.count?.saved, 12)
        XCTAssertEqual(vm.count?.max, 20)

        store.state.unlocked = true
        XCTAssertNil(vm.count)
        let off = RecipesViewModel(repository: repository, sleep: immediateSleep)
        await settleMain()
        XCTAssertNil(off.count)
    }

    func testThePolicyMirrorsTheLimitForTheShareExtension() {
        let defaults = UserDefaults(suiteName: "FreeTierViewModelTests")!
        defaults.removePersistentDomain(forName: "FreeTierViewModelTests")
        let mirror = DefaultsLibraryLimit(defaults: defaults)
        XCTAssertEqual(mirror.current(), .history(keep: 50))
        let flags = flags(freeTier: true)
        let store = FakeEntitlements()
        LibraryPolicy(flags: flags, entitlements: store, mirror: mirror).startMirroring()
        XCTAssertEqual(mirror.current(), .free(max: 20))
        flags.setUnlockedOverride(true)
        XCTAssertEqual(LibraryPolicy(flags: flags, entitlements: store).limit, .unlimited)
    }

    func testTheShareCardSaysWhenARecipeWasNotKept() async {
        let repository = FakeRecipeRepository()
        repository.importResult = .notKept(soup)
        let vm = ShareImportViewModel(repository: repository)
        vm.start(with: SharedInput(url: "https://a.com/soup", page: nil))
        await vm.currentLoad?.value
        XCTAssertEqual(vm.uiState, .notKept(title: "Soup"))
    }
}
