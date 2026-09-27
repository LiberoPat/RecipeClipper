import XCTest
@testable import RecipeClipper

@MainActor
final class SettingsViewModelTests: XCTestCase {

    func testStateIsSeededFromPreferencesOnConstruction() {
        let preferences = FakeAppPreferences(
            unitSystem: .metric, convertLiquids: true, temperatureUnit: .celsius, darkWhileCooking: true
        )
        let vm = SettingsViewModel(preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles())

        XCTAssertEqual(vm.uiState.unitSystem, .metric)
        XCTAssertTrue(vm.uiState.convertLiquids)
        XCTAssertEqual(vm.uiState.temperatureUnit, .celsius)
        XCTAssertTrue(vm.uiState.darkWhileCooking)
    }

    func testDefaultsMatchAppPreferencesDefaults() {
        let vm = SettingsViewModel(preferences: FakeAppPreferences(), backups: FakeBackupRepository(), files: FakeBackupFiles())

        XCTAssertEqual(vm.uiState, SettingsUiState())
        XCTAssertEqual(vm.uiState.unitSystem, .asWritten)
        XCTAssertFalse(vm.uiState.convertLiquids)
        XCTAssertEqual(vm.uiState.temperatureUnit, .asWritten)
        XCTAssertFalse(vm.uiState.darkWhileCooking)
    }

    func testOnUnitSystemChangeWritesThroughAndUpdatesState() {
        let preferences = FakeAppPreferences()
        let vm = SettingsViewModel(preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles())

        vm.onUnitSystemChange(.metric)

        XCTAssertEqual(vm.uiState.unitSystem, .metric)
        XCTAssertEqual(preferences.unitSystem, .metric)
    }

    func testOnConvertLiquidsChangeWritesThroughAndUpdatesState() {
        let preferences = FakeAppPreferences()
        let vm = SettingsViewModel(preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles())

        vm.onConvertLiquidsChange(true)

        XCTAssertTrue(vm.uiState.convertLiquids)
        XCTAssertTrue(preferences.convertLiquids)
    }

    func testOnTemperatureUnitChangeWritesThroughIndependentlyOfUnitSystem() {
        let preferences = FakeAppPreferences()
        let vm = SettingsViewModel(preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles())

        vm.onTemperatureUnitChange(.fahrenheit)

        XCTAssertEqual(vm.uiState.temperatureUnit, .fahrenheit)
        XCTAssertEqual(preferences.temperatureUnit, .fahrenheit)
        XCTAssertEqual(vm.uiState.unitSystem, .asWritten)
    }

    func testOnDarkWhileCookingChangeWritesThroughAndUpdatesState() {
        let preferences = FakeAppPreferences()
        let vm = SettingsViewModel(preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles())

        vm.onDarkWhileCookingChange(true)

        XCTAssertTrue(vm.uiState.darkWhileCooking)
        XCTAssertTrue(preferences.darkWhileCooking)
    }

    func testAChangeWrittenElsewhereWhileSettingsIsOpenReachesItsState() async {
        let preferences = FakeAppPreferences()
        let vm = SettingsViewModel(preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles())
        await settleMain()

        // e.g. the recipe screen's units dropdown, with Settings on the back stack
        preferences.unitSystem = .ounces
        preferences.temperatureUnit = .celsius
        await settleMain()

        XCTAssertEqual(vm.uiState.unitSystem, .ounces)
        XCTAssertEqual(vm.uiState.temperatureUnit, .celsius)
    }

    func testASettersWriteEchoingBackThroughSettingsLeavesTheStateAsSet() async {
        let vm = SettingsViewModel(preferences: FakeAppPreferences(), backups: FakeBackupRepository(), files: FakeBackupFiles())
        await settleMain()

        vm.onUnitSystemChange(.metric)
        vm.onConvertLiquidsChange(true)
        await settleMain()

        XCTAssertEqual(vm.uiState, SettingsUiState(unitSystem: .metric, convertLiquids: true))
    }

    func testConvertLiquidsIsOfferedOnlyForOunces() {
        let vm = SettingsViewModel(preferences: FakeAppPreferences(), backups: FakeBackupRepository(), files: FakeBackupFiles())
        let offered = UnitSystem.allCases.filter { system in
            vm.onUnitSystemChange(system)
            return vm.showsConvertLiquids
        }
        XCTAssertEqual(offered, [.ounces])
    }

    // MARK: - Your recipes: export and import (#26)

    private func backupVM(_ backups: FakeBackupRepository, _ files: FakeBackupFiles) -> SettingsViewModel {
        SettingsViewModel(preferences: FakeAppPreferences(), backups: backups, files: files)
    }

    func testExportWritesTheFileAndHandsItToTheScreenToShare() async {
        let backups = FakeBackupRepository()
        backups.exportResult = .success(ExportedBackup(json: "{\"x\":1}", exportedAt: 42, recipeCount: 3))
        let files = FakeBackupFiles()
        let vm = backupVM(backups, files)

        let task = vm.onExport()
        XCTAssertEqual(vm.uiState.backup, .exporting)
        await task?.value

        XCTAssertEqual(files.written.map(\.json), ["{\"x\":1}"])
        XCTAssertEqual(files.written.map(\.exportedAt), [42])
        XCTAssertEqual(vm.uiState.backup, .readyToShare(URL(fileURLWithPath: "/tmp/recipe-clipper-test.json")))
        vm.onExportShared()
        XCTAssertEqual(vm.uiState.backup, .idle)
    }

    func testARefusedExportIsACauseAndNothingIsWritten() async {
        let backups = FakeBackupRepository()
        backups.exportResult = .failure(.exportFailed)
        let files = FakeBackupFiles()
        let vm = backupVM(backups, files)
        await vm.onExport()?.value
        XCTAssertEqual(vm.uiState.backup, .failed(.exportFailed))
        XCTAssertTrue(files.written.isEmpty)
    }

    func testAFileThatCannotBeWrittenIsExportFailed() async {
        let files = FakeBackupFiles()
        files.writeURL = nil
        let vm = backupVM(FakeBackupRepository(), files)
        await vm.onExport()?.value
        XCTAssertEqual(vm.uiState.backup, .failed(.exportFailed))
    }

    func testImportReadsThePickedFileAndShowsTheSummary() async {
        let url = URL(fileURLWithPath: "/tmp/picked.json")
        let files = FakeBackupFiles()
        files.files[url] = "the file"
        let backups = FakeBackupRepository()
        let summary = ImportSummary(recipesAdded: 12, listsAdded: 3, recipesAlreadyHere: 1, recipesSkipped: 0)
        backups.importResult = .success(summary)
        let vm = backupVM(backups, files)

        let task = vm.onImportPicked(url)
        XCTAssertEqual(vm.uiState.backup, .importing)
        await task?.value

        XCTAssertEqual(backups.importedTexts, ["the file"])
        XCTAssertEqual(vm.uiState.backup, .imported(summary))
        XCTAssertEqual(Strings.importSummary(summary), "Imported 12 recipes and 3 lists. 1 recipe was already here.")
    }

    func testAFileThatCannotBeReadNeverReachesTheRepository() async {
        let backups = FakeBackupRepository()
        let vm = backupVM(backups, FakeBackupFiles())
        await vm.onImportPicked(URL(fileURLWithPath: "/tmp/missing.json"))?.value
        XCTAssertEqual(vm.uiState.backup, .failed(.readFailed))
        XCTAssertTrue(backups.importedTexts.isEmpty)
    }

    func testARefusedImportShowsItsCause() async {
        let url = URL(fileURLWithPath: "/tmp/picked.json")
        let files = FakeBackupFiles()
        files.files[url] = "x"
        let backups = FakeBackupRepository()
        backups.importResult = .failure(.newerVersion(found: 2))
        let vm = backupVM(backups, files)
        await vm.onImportPicked(url)?.value
        XCTAssertEqual(vm.uiState.backup, .failed(.newerVersion(found: 2)))
    }

    func testASecondTapWhileOneIsRunningIsIgnored() async {
        let url = URL(fileURLWithPath: "/tmp/picked.json")
        let files = FakeBackupFiles()
        files.files[url] = "x"
        let backups = FakeBackupRepository()
        let vm = backupVM(backups, files)

        let first = vm.onImportPicked(url)
        XCTAssertNil(vm.onImportPicked(url))
        XCTAssertNil(vm.onExport())
        await first?.value

        XCTAssertEqual(backups.importedTexts.count, 1)
        XCTAssertEqual(backups.exportCalls, 0)
    }

    func testTheSummaryWordsEveryCase() {
        XCTAssertEqual(
            Strings.importSummary(ImportSummary(recipesAdded: 0, listsAdded: 0, recipesAlreadyHere: 4, recipesSkipped: 0)),
            "Nothing new: everything in that file is already here."
        )
        XCTAssertEqual(
            Strings.importSummary(ImportSummary(recipesAdded: 1, listsAdded: 0, recipesAlreadyHere: 0, recipesSkipped: 2)),
            "Imported 1 recipe. 2 older recipes in no list weren't added: history keeps the 50 most recent."
        )
    }
}
