import Foundation
@testable import RecipeClipper

/// An in-memory iCloud folder (#150): `files` by name to what was written.
final class FakeBackupFolder: BackupFolder {
    var files: [String: String] = [:]
    /// False stands for no iCloud Drive (signed out, or no container).
    var available = true
    /// False makes every write fail.
    var writable = true

    func destination() async -> BackupDestination { available ? .ready : .unavailable }

    func list() async -> [String]? { available ? Array(files.keys) : nil }

    func write(name: String, json: String, photos: [String: URL]) async -> String? {
        guard available, writable else { return nil }
        files[name] = json
        return name
    }

    func delete(name: String) async { files[name] = nil }
}

/// A clock whose time a test sets directly.
final class SettableClock: Clock {
    var time: Int64
    init(_ time: Int64 = 1_000_000_000_000) { self.time = time }
    func now() -> Int64 { time }
}

/// An `AutoBackup` over the fakes above.
@MainActor
final class AutoBackupFixture {
    let repository = FakeBackupRepository()
    let folder = FakeBackupFolder()
    let store: MemoryAutoBackupStore
    let clock = SettableClock()
    let autoBackup: AutoBackup

    init(_ record: AutoBackupRecord = AutoBackupRecord()) {
        store = MemoryAutoBackupStore(record)
        autoBackup = AutoBackup(backups: repository, folder: folder, store: store, clock: clock)
    }

    func exported(_ json: String) {
        repository.exportResult = .success(ExportedBackup(json: json, exportedAt: 1, recipeCount: 1))
    }
}
