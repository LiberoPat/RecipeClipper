import XCTest
@testable import RecipeClipper

/// Settings → Your recipes → Import from Paprika (#382; Android's SettingsPaprikaImportViewModelTest and
/// SettingsPaprikaImportScreenTest): the ViewModel's state, and the words each result is given.
@MainActor
final class SettingsPaprikaImportTests: XCTestCase {
    private let paprika = FakePaprikaImportRepository()
    private let backups = FakeBackupRepository()
    private let picked = URL(fileURLWithPath: "/tmp/export.paprikarecipes")

    private func flags(_ on: Bool = true) -> FeatureFlags {
        FeatureFlags(store: MemoryFeatureFlagStore(["paprikaImport": on]), isDebug: false)
    }

    private func vm(flags: FeatureFlags? = nil, importer: PaprikaImportRepository? = nil, usingDefaults: Bool = true) -> SettingsViewModel {
        SettingsViewModel(
            preferences: FakeAppPreferences(), backups: backups, files: FakeBackupFiles(),
            flags: flags ?? self.flags(), paprika: usingDefaults ? (importer ?? paprika) : importer
        )
    }

    private let summary = PaprikaSummary(
        imported: 12, alreadyHere: 2, skippedForLimit: 0, notRecipes: 1, unreadable: 0, photosNotImported: true, freeLimit: nil
    )

    func testTheRowShowsOnlyWithTheFlagOnAndAnImporter() {
        XCTAssertTrue(vm().showsPaprikaImport)
        XCTAssertFalse(vm(flags: flags(false)).showsPaprikaImport)
        XCTAssertFalse(vm(importer: nil, usingDefaults: false).showsPaprikaImport)
    }

    func testAPickedFileIsReadWithProgressThenItsSummaryShows() async {
        paprika.result = .success(summary)
        let gate = AsyncGate()
        paprika.gate = gate
        let vm = vm()

        let work = vm.onPaprikaPicked(picked)
        XCTAssertEqual(vm.uiState.backup, .importingPaprika)
        XCTAssertTrue(vm.uiState.backup.isBusy)
        await gate.release()
        await work?.value

        XCTAssertEqual(paprika.imported, [picked])
        XCTAssertEqual(vm.uiState.backup, .importedPaprika(summary))
    }

    func testARefusedFileShowsItsCause() async {
        paprika.result = .failure(.damaged)
        let vm = vm()
        await vm.onPaprikaPicked(picked)?.value
        XCTAssertEqual(vm.uiState.backup, .paprikaFailed(.damaged))
    }

    func testOneThingAtATimeSoExportImportAndASecondPaprikaPickWait() async {
        let gate = AsyncGate()
        paprika.gate = gate
        let vm = vm()
        let first = vm.onPaprikaPicked(picked)

        XCTAssertNil(vm.onPaprikaPicked(URL(fileURLWithPath: "/tmp/two")))
        XCTAssertNil(vm.onImportPicked(URL(fileURLWithPath: "/tmp/three")))
        XCTAssertNil(vm.onExport())
        await gate.release()
        await first?.value
        XCTAssertEqual(paprika.imported, [picked])
        XCTAssertEqual(backups.exportCalls, 0)
    }

    // MARK: The words

    func testTheResultSaysEachCountThatIsntZeroAndThePictures() {
        XCTAssertEqual(
            Strings.paprikaSummary(summary),
            "Imported 12 recipes from Paprika. 2 recipes were already here. "
                + "1 entry with no name, or with no ingredients or steps, was left out. "
                + "The pictures stored inside the file weren't imported."
        )
    }

    func testAFullFreeLibrarySaysHowManyWereLeftOut() {
        let full = PaprikaSummary(
            imported: 3, alreadyHere: 0, skippedForLimit: 40, notRecipes: 0, unreadable: 2, photosNotImported: false, freeLimit: 50
        )
        XCTAssertEqual(
            Strings.paprikaSummary(full),
            "Imported 3 recipes from Paprika. 40 recipes weren't added: the free app keeps 50 recipes. 2 entries couldn't be read."
        )
    }

    func testNothingNewAndNothingFoundEachHaveTheirOwnSentence() {
        XCTAssertEqual(
            Strings.paprikaSummary(PaprikaSummary(
                imported: 0, alreadyHere: 5, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosNotImported: true, freeLimit: nil
            )),
            "Nothing new: every recipe in that file is already here."
        )
        XCTAssertEqual(
            Strings.paprikaSummary(PaprikaSummary(
                imported: 0, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosNotImported: false, freeLimit: nil
            )),
            "No recipes could be read from that file."
        )
    }

    func testARefusedFileShowsItsCauseInWords() {
        XCTAssertEqual(Strings.message(for: PaprikaError.notAPaprikaFile), "That isn't a Paprika export. Choose a .paprikarecipes file.")
        XCTAssertEqual(Strings.message(for: PaprikaError.damaged), "That file is damaged or cut short. Nothing was imported.")
        XCTAssertEqual(backupStatusText(.paprikaFailed(.tooLarge))?.isError, true)
        XCTAssertEqual(backupStatusText(.importingPaprika)?.text, "Reading the Paprika file…")
    }
}
