import XCTest
@testable import RecipeClipper

/// Issue #25: recipes, lists and ticked ingredients survive a restore to a new iPhone only
/// because the database sits somewhere iCloud and device backups include. Application Support
/// is, and so is the App Group container (#19) `defaultPath()` prefers when the build is
/// entitled to it, as every simulator build is; Caches and tmp are not, and neither is
/// anything flagged `isExcludedFromBackup`.
final class BackupLocationTests: XCTestCase {

    func testTheDatabaseLivesInApplicationSupportOrTheAppGroupContainer() throws {
        let path = URL(fileURLWithPath: AppDatabase.defaultPath()).standardizedFileURL
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: false
        ).standardizedFileURL
        let group = AppGroup.containerURL?.standardizedFileURL

        XCTAssertEqual(path.lastPathComponent, "recipe_clipper.sqlite")
        let parent = path.deletingLastPathComponent().path
        XCTAssertTrue(
            parent == support.path || parent == group?.path,
            "\(parent) is neither Application Support nor the App Group container"
        )

        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        XCTAssertFalse(path.path.hasPrefix(caches.standardizedFileURL.path))
        XCTAssertFalse(path.path.hasPrefix(URL(fileURLWithPath: NSTemporaryDirectory()).standardizedFileURL.path))
    }

    func testTheDatabaseDirectoryIsNotExcludedFromBackup() throws {
        let directory = URL(fileURLWithPath: AppDatabase.defaultPath()).deletingLastPathComponent()
        let values = try directory.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, false)
    }

    /// Opens a database file beside the real one (a different name, so the test host's data is
    /// never touched) and checks neither it nor its WAL is excluded once SQLite has made them.
    func testADatabaseFileThereAndItsWalAreNotExcludedFromBackup() async throws {
        let directory = URL(fileURLWithPath: AppDatabase.defaultPath()).deletingLastPathComponent()
        let file = directory.appendingPathComponent("rc-backup-test-\(UUID().uuidString).sqlite")
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: file.path + suffix) }
        }
        let db = try AppDatabase(path: file.path)
        _ = try await db.upsert(dataRecipeRecord("https://a.com/backup", viewedAt: 100))

        for suffix in ["", "-wal"] {
            let url = URL(fileURLWithPath: file.path + suffix)
            XCTAssertTrue(FileManager.default.fileExists(atPath: url.path), "missing \(url.lastPathComponent)")
            let values = try url.resourceValues(forKeys: [.isExcludedFromBackupKey])
            XCTAssertEqual(values.isExcludedFromBackup, false, url.lastPathComponent)
        }
    }
}
