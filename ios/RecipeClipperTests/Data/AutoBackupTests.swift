import Combine
import XCTest
@testable import RecipeClipper

/// The automatic copy (#150) against a fake export, iCloud folder and record; Android's
/// AutoBackupTest.
@MainActor
final class AutoBackupTests: XCTestCase {

    func testWithoutICloudDriveNothingIsWritten() async {
        let f = AutoBackupFixture()
        f.folder.available = false
        let outcome = await f.autoBackup.run()
        XCTAssertEqual(outcome, .noDestination)
        XCTAssertEqual(f.repository.exportCalls, 0)
        XCTAssertEqual(f.autoBackup.current.destination, .unavailable)
    }

    func testTheFirstLookWritesACopyAndRecordsIt() async {
        let f = AutoBackupFixture()
        let outcome = await f.autoBackup.run()
        XCTAssertEqual(outcome, .written)
        let name = AutoBackupPolicy.fileName(now: f.clock.time)
        XCTAssertEqual(Array(f.folder.files.keys), [name])
        XCTAssertEqual(f.store.record.lastBackupAt, f.clock.time)
        XCTAssertEqual(f.store.record.lastFile, name)
        XCTAssertEqual(f.autoBackup.current.record.lastBackupAt, f.clock.time, "Settings hears of it")
    }

    func testAnUnchangedLibraryIsNotWrittenAgainAChangedOneIsAfterTheGap() async {
        let f = AutoBackupFixture()
        f.exported(#"{"exportedAt":1,"recipes":[]}"#)
        await f.autoBackup.run()
        f.clock.time += AutoBackupPolicy.minGapMs
        f.exported(#"{"exportedAt":2,"recipes":[]}"#)
        let unchanged = await f.autoBackup.run()
        XCTAssertEqual(unchanged, .upToDate)

        f.exported(#"{"exportedAt":3,"recipes":[{"title":"Soup"}]}"#)
        let changed = await f.autoBackup.run()
        XCTAssertEqual(changed, .written)
        XCTAssertEqual(f.folder.files.count, 2)
    }

    func testOnlyTheNewestThreeCopiesStayAndOtherFilesAreNeverTouched() async {
        let f = AutoBackupFixture()
        f.folder.files["shopping.txt"] = "mine"
        for i in 0 ..< 5 {
            f.exported(#"{"exportedAt":\#(i),"n":\#(i)}"#)
            await f.autoBackup.run(force: true)
            f.clock.time += 24 * 60 * 60 * 1000
        }
        let ours = f.folder.files.keys.filter(AutoBackupPolicy.isBackupFile)
        XCTAssertEqual(ours.count, AutoBackupPolicy.keep)
        XCTAssertNotNil(f.folder.files["shopping.txt"])
        XCTAssertTrue(ours.contains(f.store.record.lastFile ?? ""))
    }

    func testAFailedWriteKeepsTheOlderCopiesAndSaysSo() async {
        let f = AutoBackupFixture()
        await f.autoBackup.run()
        let before = f.store.record.lastBackupAt
        f.folder.writable = false
        let failed = await f.autoBackup.run(force: true)
        XCTAssertEqual(failed, .failed)
        XCTAssertEqual(f.folder.files.count, 1)
        XCTAssertTrue(f.store.record.lastFailed)
        XCTAssertEqual(f.store.record.lastBackupAt, before)

        f.folder.writable = true
        f.clock.time += 60_000
        await f.autoBackup.run(force: true)
        XCTAssertFalse(f.store.record.lastFailed, "a good copy clears it")
    }

    func testOffItWritesNothingByItselfButBackUpNowStillWorks() async {
        let f = AutoBackupFixture()
        f.autoBackup.setEnabled(false)
        let off = await f.autoBackup.run()
        XCTAssertEqual(off, .off)
        let forced = await f.autoBackup.run(force: true)
        XCTAssertEqual(forced, .written)
    }

    func testTheRecordIsKeptUnderAndroidsKeys() {
        let defaults = UserDefaults(suiteName: "AutoBackupTests")!
        defaults.removePersistentDomain(forName: "AutoBackupTests")
        let store = UserDefaultsAutoBackupStore(defaults: defaults)
        XCTAssertEqual(store.record, AutoBackupRecord(), "on by default")
        store.update {
            $0.enabled = false
            $0.lastBackupAt = 42
            $0.lastFile = "copy.zip"
        }
        XCTAssertEqual(UserDefaultsAutoBackupStore(defaults: defaults).record.lastBackupAt, 42)
        XCTAssertEqual(defaults.object(forKey: "auto_backup_enabled") as? Bool, false)
        defaults.removePersistentDomain(forName: "AutoBackupTests")
    }
}
