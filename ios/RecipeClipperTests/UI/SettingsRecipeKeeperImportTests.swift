import XCTest
@testable import RecipeClipper

/// Settings → Your recipes → Import from Recipe Keeper (#390; Android's SettingsRecipeKeeperImportViewModelTest
/// and SettingsRecipeKeeperImportScreenTest): the ViewModel's state, and the words each result is given.
@MainActor
final class SettingsRecipeKeeperImportTests: XCTestCase {
    private let importer = FakePaprikaImportRepository()
    private let backups = FakeBackupRepository()
    private let picked = URL(fileURLWithPath: "/tmp/RecipeKeeper_20261119.zip")

    private func flags(keeper: Bool = true, paprika: Bool = true) -> FeatureFlags {
        FeatureFlags(store: MemoryFeatureFlagStore(["recipeKeeperImport": keeper, "paprikaImport": paprika]), isDebug: false)
    }

    private func vm(flags: FeatureFlags? = nil, withImporter: Bool = true) -> SettingsViewModel {
        SettingsViewModel(
            preferences: FakeAppPreferences(), backups: backups, files: FakeBackupFiles(),
            flags: flags ?? self.flags(), paprika: withImporter ? importer : nil
        )
    }

    private let summary = PaprikaSummary(
        imported: 7, alreadyHere: 1, skippedForLimit: 0, notRecipes: 2, unreadable: 0, photosLeftOut: 1, freeLimit: nil, listsMade: 4
    )

    func testTheRowShowsOnlyWithItsOwnFlagOnAndAnImporterWhateverPaprikasFlagSays() {
        XCTAssertTrue(vm().showsRecipeKeeperImport)
        XCTAssertTrue(vm(flags: flags(paprika: false)).showsRecipeKeeperImport)
        XCTAssertFalse(vm(flags: flags(paprika: false)).showsPaprikaImport)
        XCTAssertFalse(vm(flags: flags(keeper: false)).showsRecipeKeeperImport)
        XCTAssertTrue(vm(flags: flags(keeper: false)).showsPaprikaImport)
        XCTAssertFalse(vm(withImporter: false).showsRecipeKeeperImport)
    }

    func testAPickedFileIsReadAsRecipeKeepersWithProgressThenItsSummaryShows() async {
        importer.result = .success(summary)
        let gate = AsyncGate()
        importer.gate = gate
        let vm = vm()

        let work = vm.onRecipeKeeperPicked(picked)
        XCTAssertEqual(vm.uiState.backup, .importingRecipeKeeper)
        XCTAssertTrue(vm.uiState.backup.isBusy)
        await gate.release()
        await work?.value

        XCTAssertEqual(importer.imported, [picked])
        XCTAssertEqual(importer.sources, [.recipeKeeper])
        XCTAssertEqual(vm.uiState.backup, .importedRecipeKeeper(summary))
    }

    func testTheOneFilingSwitchGoesToBothImports() async {
        let vm = vm()
        await vm.onRecipeKeeperPicked(picked)?.value
        vm.onPaprikaFilingChange(false)
        await vm.onRecipeKeeperPicked(URL(fileURLWithPath: "/tmp/two"))?.value
        await vm.onPaprikaPicked(URL(fileURLWithPath: "/tmp/three"))?.value
        XCTAssertEqual(importer.filings, [true, false, false])
        XCTAssertEqual(importer.sources, [.recipeKeeper, .recipeKeeper, .paprika])
    }

    func testARefusedFileShowsItsCause() async {
        importer.result = .failure(.notARecipeKeeperFile)
        let vm = vm()
        await vm.onRecipeKeeperPicked(picked)?.value
        XCTAssertEqual(vm.uiState.backup, .recipeKeeperFailed(.notARecipeKeeperFile))
    }

    func testOneThingAtATimeSoAPaprikaPickWaitsForARecipeKeeperOne() async {
        let gate = AsyncGate()
        importer.gate = gate
        let vm = vm()
        let first = vm.onRecipeKeeperPicked(picked)

        XCTAssertNil(vm.onPaprikaPicked(URL(fileURLWithPath: "/tmp/two")))
        XCTAssertNil(vm.onRecipeKeeperPicked(URL(fileURLWithPath: "/tmp/three")))
        await gate.release()
        await first?.value
        XCTAssertEqual(importer.imported, [picked])
    }

    // MARK: The words

    func testTheResultSaysEachCountThatIsntZeroInRecipeKeepersWords() {
        XCTAssertEqual(
            Strings.paprikaSummary(summary, importedKey: .recipeKeeper),
            "Imported 7 recipes from Recipe Keeper. 1 recipe was already here. "
                + "2 entries with no name, or with no ingredients or steps, were left out. "
                + "1 picture couldn't be read and was left out. 4 new lists were made from the categories."
        )
        // Paprika's own words are unchanged.
        XCTAssertTrue(Strings.paprikaSummary(summary).hasPrefix("Imported 7 recipes from Paprika."))
    }

    func testARefusedFileShowsItsCauseInWords() {
        XCTAssertEqual(
            Strings.message(for: PaprikaError.notARecipeKeeperFile, source: .recipeKeeper),
            "That isn't a Recipe Keeper export. Choose the .zip file Recipe Keeper made."
        )
        XCTAssertEqual(
            Strings.message(for: PaprikaError.tooLarge, source: .recipeKeeper),
            "That file is far larger than a Recipe Keeper export should be. Nothing was imported."
        )
        XCTAssertEqual(
            Strings.message(for: PaprikaError.tooLarge),
            "That file is far larger than a Paprika export should be. Nothing was imported."
        )
        XCTAssertEqual(backupStatusText(.recipeKeeperFailed(.damaged))?.isError, true)
        XCTAssertEqual(backupStatusText(.importingRecipeKeeper)?.text, "Reading the Recipe Keeper file…")
        XCTAssertEqual(Strings.recipeKeeperImportTitle, "Import from Recipe Keeper")
        XCTAssertEqual(Strings.paprikaFilingTitle, "Put recipes in lists by their categories")
    }
}
