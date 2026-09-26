import XCTest
@testable import RecipeClipper

/// Settings → Your recipes' automatic backup copy (#150) and Home's "Restore from a backup
/// file": real ViewModels over an `AutoBackup` and backup repository on fakes.
@MainActor
final class SettingsAutoBackupTests: XCTestCase {

    private func settings(_ f: AutoBackupFixture) -> SettingsViewModel {
        SettingsViewModel(
            preferences: FakeAppPreferences(), backups: f.repository, files: FakeBackupFiles(),
            autoBackup: f.autoBackup, clock: f.clock
        )
    }

    func testNoRowsWithoutAnAutomaticCopy() {
        let vm = SettingsViewModel(preferences: FakeAppPreferences(), backups: FakeBackupRepository(), files: FakeBackupFiles())
        XCTAssertNil(vm.uiState.autoBackup)
    }

    func testOnByDefaultAndNotBackedUpYet() async {
        let f = AutoBackupFixture()
        let vm = settings(f)
        await settleMain()
        let row = vm.uiState.autoBackup
        XCTAssertEqual(row?.enabled, true)
        XCTAssertEqual(row?.destination, .ready)
        XCTAssertNil(row?.lastBackupAt)
        XCTAssertEqual(row?.canBackUpNow, true)
    }

    func testWithoutICloudDriveItSaysSoAndOffersNoBackUpNow() async {
        let f = AutoBackupFixture()
        f.folder.available = false
        let vm = settings(f)
        await settleMain { vm.uiState.autoBackup?.destination == .unavailable }
        XCTAssertEqual(vm.uiState.autoBackup?.destination, .unavailable)
        XCTAssertEqual(vm.uiState.autoBackup?.canBackUpNow, false)
    }

    func testBackUpNowWritesACopyAndShowsItsDate() async {
        let f = AutoBackupFixture()
        let vm = settings(f)
        await vm.onBackUpNow()?.value
        XCTAssertEqual(vm.uiState.autoBackup?.lastBackupAt, f.clock.time)
        XCTAssertEqual(vm.uiState.autoBackup?.running, false)
        XCTAssertEqual(f.folder.files.count, 1)
    }

    func testTurningItOffIsKept() {
        let f = AutoBackupFixture()
        let vm = settings(f)
        vm.onAutoBackupChange(false)
        XCTAssertEqual(vm.uiState.autoBackup?.enabled, false)
        XCTAssertFalse(f.store.record.enabled)
    }

    func testAnOldCopyWithNothingCopyingNudges() async {
        // The record as the store holds it when the app starts (AutoBackup reads it once).
        let now = SettableClock().time
        let f = AutoBackupFixture(AutoBackupRecord(enabled: false, lastBackupAt: now - AutoBackupPolicy.nudgeAfterMs - 1))
        let vm = settings(f)
        XCTAssertEqual(vm.uiState.autoBackup?.nudge, true)

        vm.onAutoBackupChange(true)
        XCTAssertEqual(vm.uiState.autoBackup?.nudge, false, "copying again: no nudge")
    }

    func testAnEmptyLibraryOffersRestoreAndSaysHowItWent() async {
        let files = FakeBackupFiles()
        let url = URL(fileURLWithPath: "/tmp/picked.json")
        files.files[url] = "{}"
        let backups = FakeBackupRepository()
        backups.importResult = .success(ImportSummary(recipesAdded: 3, listsAdded: 1, recipesAlreadyHere: 0, recipesSkipped: 0))
        let vm = HomeViewModel(repository: FakeRecipeRepository(), backups: backups, files: files)
        await settleMain()
        XCTAssertTrue(vm.canRestore)
        XCTAssertTrue(vm.uiState.showsRestore)

        await vm.onRestorePicked(url)?.value
        XCTAssertEqual(backups.importedTexts, ["{}"])
        guard case .imported(let summary) = vm.uiState.restore else { return XCTFail("\(vm.uiState.restore)") }
        XCTAssertEqual(summary.recipesAdded, 3)
    }

    func testALibraryWithRecipesOffersNoRestore() async {
        let repository = FakeRecipeRepository()
        repository.recent.send([testSummary(1)])
        let vm = HomeViewModel(repository: repository, backups: FakeBackupRepository(), files: FakeBackupFiles())
        await settleMain()
        XCTAssertFalse(vm.uiState.showsRestore)
    }

    func testARestoreThatCannotReadTheFileSaysSo() async {
        let vm = HomeViewModel(repository: FakeRecipeRepository(), backups: FakeBackupRepository(), files: FakeBackupFiles())
        await settleMain()
        await vm.onRestorePicked(URL(fileURLWithPath: "/tmp/missing.json"))?.value
        XCTAssertEqual(vm.uiState.restore, .failed(.readFailed))
    }
}
