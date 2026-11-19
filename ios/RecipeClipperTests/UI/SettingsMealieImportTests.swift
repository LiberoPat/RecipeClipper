import XCTest
@testable import RecipeClipper

/// Settings → Your recipes → Import from Mealie (#391; Android's SettingsMealieImportViewModelTest and
/// SettingsMealieImportScreenTest): the ViewModel's state, and the words each result is given.
@MainActor
final class SettingsMealieImportTests: XCTestCase {
    private let mealie = FakeMealieImportRepository()
    private let backups = FakeBackupRepository()
    private let picked = URL(fileURLWithPath: "/tmp/export.zip")

    private func flags(_ on: Bool = true) -> FeatureFlags {
        FeatureFlags(store: MemoryFeatureFlagStore(["mealieImport": on]), isDebug: false)
    }

    private func vm(flags: FeatureFlags? = nil, importer: MealieImportRepository? = nil, usingDefaults: Bool = true) -> SettingsViewModel {
        SettingsViewModel(
            preferences: FakeAppPreferences(), backups: backups, files: FakeBackupFiles(),
            flags: flags ?? self.flags(), mealie: usingDefaults ? (importer ?? mealie) : importer
        )
    }

    private let summary = MealieSummary(
        imported: 12, alreadyHere: 2, skippedForLimit: 0, notRecipes: 1, unreadable: 0, photosLeftOut: 2, freeLimit: nil, listsMade: 3
    )

    func testTheRowShowsOnlyWithTheFlagOnAndAnImporter() {
        XCTAssertTrue(vm().showsMealieImport)
        XCTAssertFalse(vm(flags: flags(false)).showsMealieImport)
        XCTAssertFalse(vm(importer: nil, usingDefaults: false).showsMealieImport)
    }

    func testAPickedFileIsReadWithProgressThenItsSummaryShows() async {
        mealie.result = .success(summary)
        let gate = AsyncGate()
        mealie.gate = gate
        let vm = vm()

        let work = vm.onMealiePicked(picked)
        XCTAssertEqual(vm.uiState.backup, .importingMealie)
        XCTAssertTrue(vm.uiState.backup.isBusy)
        await gate.release()
        await work?.value

        XCTAssertEqual(mealie.imported, [picked])
        XCTAssertEqual(vm.uiState.backup, .importedMealie(summary))
    }

    func testTheFilingSwitchIsOnByDefaultIsThisVisitsChoiceAndGoesToTheImport() async {
        let vm = vm()
        XCTAssertTrue(vm.uiState.mealieFiling)
        await vm.onMealiePicked(picked)?.value
        vm.onMealieFilingChange(false)
        await vm.onMealiePicked(URL(fileURLWithPath: "/tmp/two"))?.value
        XCTAssertEqual(mealie.filings, [true, false])
        XCTAssertFalse(vm.uiState.mealieFiling)
    }

    func testTheSwitchSurvivesAPreferenceChange() async {
        let preferences = FakeAppPreferences()
        let vm = SettingsViewModel(
            preferences: preferences, backups: backups, files: FakeBackupFiles(), flags: flags(), mealie: mealie
        )
        vm.onMealieFilingChange(false)
        preferences.darkWhileCooking = true
        try? await Task.sleep(for: .milliseconds(50))
        XCTAssertFalse(vm.uiState.mealieFiling)
    }

    func testARefusedFileShowsItsCause() async {
        mealie.result = .failure(.damaged)
        let vm = vm()
        await vm.onMealiePicked(picked)?.value
        XCTAssertEqual(vm.uiState.backup, .mealieFailed(.damaged))
    }

    func testOneThingAtATimeSoExportImportAndASecondMealiePickWait() async {
        let gate = AsyncGate()
        mealie.gate = gate
        let vm = vm()
        let first = vm.onMealiePicked(picked)

        XCTAssertNil(vm.onMealiePicked(URL(fileURLWithPath: "/tmp/two")))
        XCTAssertNil(vm.onImportPicked(URL(fileURLWithPath: "/tmp/three")))
        XCTAssertNil(vm.onExport())
        await gate.release()
        await first?.value
        XCTAssertEqual(mealie.imported, [picked])
        XCTAssertEqual(backups.exportCalls, 0)
    }

    // MARK: The words

    func testTheResultSaysEachCountThatIsntZeroAndThePictures() {
        XCTAssertEqual(
            Strings.mealieSummary(summary),
            "Imported 12 recipes from Mealie. 2 recipes were already here. "
                + "1 entry that isn't a Mealie recipe, or has no name, ingredients or steps, was left out. "
                + "2 pictures couldn't be read and were left out. 3 new lists were made from the categories and tags."
        )
    }

    func testAFullFreeLibrarySaysHowManyWereLeftOut() {
        let full = MealieSummary(
            imported: 3, alreadyHere: 0, skippedForLimit: 40, notRecipes: 0, unreadable: 2, photosLeftOut: 0, freeLimit: 50
        )
        XCTAssertEqual(
            Strings.mealieSummary(full),
            "Imported 3 recipes from Mealie. 40 recipes weren't added: the free app keeps 50 recipes. 2 entries couldn't be read."
        )
    }

    func testNothingNewAndNothingFoundEachHaveTheirOwnSentence() {
        XCTAssertEqual(
            Strings.mealieSummary(MealieSummary(
                imported: 0, alreadyHere: 5, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosLeftOut: 0, freeLimit: nil
            )),
            "Nothing new: every recipe in that file is already here."
        )
        XCTAssertEqual(
            Strings.mealieSummary(MealieSummary(
                imported: 0, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosLeftOut: 0, freeLimit: nil
            )),
            "No recipes could be read from that file."
        )
    }

    func testARefusedFileShowsItsCauseInWords() {
        XCTAssertEqual(Strings.message(for: MealieError.notAMealieFile), "That isn't a Mealie export. Choose the .zip Mealie exported, or a recipe's .json. To bring in a recipe from a Mealie page, share its link.")
        XCTAssertEqual(Strings.message(for: MealieError.damaged), "That file is damaged or cut short. Nothing was imported.")
        XCTAssertEqual(backupStatusText(.mealieFailed(.tooLarge))?.isError, true)
        XCTAssertEqual(backupStatusText(.importingMealie)?.text, "Reading the Mealie file…")
    }
}
