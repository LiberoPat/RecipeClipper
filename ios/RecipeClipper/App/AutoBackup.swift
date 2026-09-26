import Combine
import Foundation

/// Where the automatic copy (#150) goes (Android's `BackupFolder`): on iOS the app's own iCloud
/// Drive folder. Every call may touch the file system or iCloud, so none runs on the main thread.
protocol BackupFolder: AnyObject {
    /// `.ready` while there is somewhere to write, `.unavailable` otherwise.
    func destination() async -> BackupDestination
    /// The names of the files in the folder, or nil if it can't be read.
    func list() async -> [String]?
    /// Writes a zip export (`BackupArchive`) as `name`, replacing a file of that name; the name
    /// it ended up with, or nil if it couldn't. A failed write leaves no partial file.
    func write(name: String, json: String, photos: [String: URL]) async -> String?
    /// Deletes the named file, if it is there.
    func delete(name: String) async
}

/// Where `AutoBackupRecord` is kept (Android's `AutoBackupStore`).
protocol AutoBackupStore: AnyObject {
    var record: AutoBackupRecord { get }
    func update(_ transform: (inout AutoBackupRecord) -> Void)
}

/// What Settings shows: the record, and whether its destination can be written now.
struct AutoBackupState: Equatable {
    var record: AutoBackupRecord
    var destination: BackupDestination
}

enum AutoBackupOutcome: Equatable {
    case written, upToDate, off, noDestination, failed
}

/// The automatic copy (#150; Android's `AutoBackup`): the latest export, photos included, as a
/// zip in the app's iCloud Drive folder ("Recipe Clipper" in Files), keeping the newest three.
/// The rules are `AutoBackupPolicy`'s. Nothing is written anywhere else and nothing is sent to
/// any server: iCloud Drive syncs the folder as it does any document.
@MainActor
final class AutoBackup {
    private let backups: BackupRepository
    private let folder: BackupFolder
    private let store: AutoBackupStore
    private let clock: Clock
    private let subject: CurrentValueSubject<AutoBackupState, Never>
    private var inFlight: Task<AutoBackupOutcome, Never>?

    init(backups: BackupRepository, folder: BackupFolder, store: AutoBackupStore, clock: Clock) {
        self.backups = backups
        self.folder = folder
        self.store = store
        self.clock = clock
        // Unknown until asked (asking may block): shown as unavailable only once iCloud says so.
        subject = CurrentValueSubject(AutoBackupState(record: store.record, destination: .ready))
    }

    /// The record and destination, now and on every change.
    var state: AnyPublisher<AutoBackupState, Never> { subject.eraseToAnyPublisher() }
    var current: AutoBackupState { subject.value }

    /// Asks iCloud again, e.g. when Settings opens. Returns the work so a test can await it.
    @discardableResult
    func refreshDestination() -> Task<Void, Never> {
        Task { [folder] in
            let destination = await folder.destination()
            self.publish(destination: destination)
        }
    }

    func setEnabled(_ enabled: Bool) {
        store.update { $0.enabled = enabled }
        publish()
    }

    /// Writes a copy if `AutoBackupPolicy.isDue` (always for `force`: "Back up now", which works
    /// even with the automatic copy off). Old copies beyond the newest three go afterwards, so
    /// a failed write never leaves fewer. One at a time: a second call joins the first.
    @discardableResult
    func run(force: Bool = false) async -> AutoBackupOutcome {
        if let inFlight { return await inFlight.value }
        let task = Task { await perform(force: force) }
        inFlight = task
        let outcome = await task.value
        inFlight = nil
        return outcome
    }

    private func perform(force: Bool) async -> AutoBackupOutcome {
        let record = store.record
        if !force && !record.enabled { return .off }
        let destination = await folder.destination()
        publish(destination: destination)
        guard destination == .ready else { return .noDestination }
        guard let existing = await folder.list() else { return failed() }
        guard case .success(let exported) = await backups.export() else { return failed() }
        let now = clock.now()
        let fingerprint = AutoBackupPolicy.fingerprint(json: exported.json, photos: Array(exported.photos.keys))
        guard AutoBackupPolicy.isDue(record, fingerprint: fingerprint, existing: existing, now: now, force: force) else {
            return .upToDate
        }
        guard let written = await folder.write(
            name: AutoBackupPolicy.fileName(now: now), json: exported.json, photos: exported.photos
        ) else { return failed() }
        for name in AutoBackupPolicy.toDelete(existing: existing + [written], written: written) {
            await folder.delete(name: name)
        }
        store.update {
            $0.lastBackupAt = now
            $0.lastFingerprint = fingerprint
            $0.lastFile = written
            $0.lastFailed = false
        }
        publish()
        return .written
    }

    private func failed() -> AutoBackupOutcome {
        store.update { $0.lastFailed = true }
        publish()
        return .failed
    }

    private func publish(destination: BackupDestination? = nil) {
        subject.send(AutoBackupState(record: store.record, destination: destination ?? subject.value.destination))
    }
}

/// The record in UserDefaults (the App Group suite the settings use), under Android's keys.
/// Unlike Android's, it rides along with iCloud Backup: the folder is the iCloud account's, so a
/// restored iPhone finds its copies where the record says.
final class UserDefaultsAutoBackupStore: AutoBackupStore {
    private let defaults: UserDefaults

    init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    var record: AutoBackupRecord {
        AutoBackupRecord(
            enabled: defaults.object(forKey: Keys.enabled) as? Bool ?? true,
            lastBackupAt: (defaults.object(forKey: Keys.lastBackupAt) as? NSNumber)?.int64Value,
            lastFingerprint: defaults.string(forKey: Keys.lastFingerprint),
            lastFile: defaults.string(forKey: Keys.lastFile),
            lastFailed: defaults.bool(forKey: Keys.lastFailed),
            folderPromptDone: defaults.bool(forKey: Keys.folderPromptDone)
        )
    }

    func update(_ transform: (inout AutoBackupRecord) -> Void) {
        var value = record
        transform(&value)
        defaults.set(value.enabled, forKey: Keys.enabled)
        defaults.set(value.lastBackupAt.map { NSNumber(value: $0) }, forKey: Keys.lastBackupAt)
        defaults.set(value.lastFingerprint, forKey: Keys.lastFingerprint)
        defaults.set(value.lastFile, forKey: Keys.lastFile)
        defaults.set(value.lastFailed, forKey: Keys.lastFailed)
        defaults.set(value.folderPromptDone, forKey: Keys.folderPromptDone)
    }

    private enum Keys {
        static let enabled = "auto_backup_enabled"
        static let lastBackupAt = "auto_backup_last_backup_at"
        static let lastFingerprint = "auto_backup_last_fingerprint"
        static let lastFile = "auto_backup_last_file"
        static let lastFailed = "auto_backup_last_failed"
        static let folderPromptDone = "auto_backup_folder_prompt_done"
    }
}

/// An in-memory record, for tests and the UI-test container.
final class MemoryAutoBackupStore: AutoBackupStore {
    private(set) var record: AutoBackupRecord
    init(_ record: AutoBackupRecord = AutoBackupRecord()) { self.record = record }
    func update(_ transform: (inout AutoBackupRecord) -> Void) { transform(&record) }
}

/// The app's iCloud Drive folder: `Documents/` in its ubiquity container, which Files shows as
/// "Recipe Clipper" (`NSUbiquitousContainers` in Info.plist). Needs the iCloud Documents
/// capability and container on the App ID (docs/release.md); without them, or with iCloud Drive
/// off, the container is nil and the copy says it is unavailable.
final class ICloudBackupFolder: BackupFolder, @unchecked Sendable {
    static let containerIdentifier = "iCloud.com.liberopat.recipeclipper"

    private func directory() -> URL? {
        // Cheap first: nil while no iCloud account is signed in (or iCloud Drive is off).
        guard FileManager.default.ubiquityIdentityToken != nil,
              let container = FileManager.default.url(forUbiquityContainerIdentifier: Self.containerIdentifier)
        else { return nil }
        let documents = container.appendingPathComponent("Documents", isDirectory: true)
        try? FileManager.default.createDirectory(at: documents, withIntermediateDirectories: true)
        return documents
    }

    func destination() async -> BackupDestination {
        directory() == nil ? .unavailable : .ready
    }

    func list() async -> [String]? {
        guard let directory = directory(),
              let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path) else { return nil }
        // A copy not downloaded to this iPhone is listed as ".<name>.icloud".
        return names.map { name in
            name.hasPrefix(".") && name.hasSuffix(".icloud") ? String(name.dropFirst().dropLast(7)) : name
        }
    }

    func write(name: String, json: String, photos: [String: URL]) async -> String? {
        guard let directory = directory() else { return nil }
        let data = BackupArchive.write(json: json, photos: photos.sorted { $0.key < $1.key }.map { (path: $0.key, file: $0.value) })
        var written = false
        var coordinationError: NSError?
        // Atomic: the file is replaced whole or not at all, so a half copy never stands.
        NSFileCoordinator().coordinate(
            writingItemAt: directory.appendingPathComponent(name), options: .forReplacing, error: &coordinationError
        ) { url in
            do {
                try data.write(to: url, options: .atomic)
                written = true
            } catch {
                dataLog.error("auto backup write failed: \(String(describing: error), privacy: .public)")
            }
        }
        return written && coordinationError == nil ? name : nil
    }

    func delete(name: String) async {
        guard let directory = directory() else { return }
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(
            writingItemAt: directory.appendingPathComponent(name), options: .forDeleting, error: &coordinationError
        ) { url in
            try? FileManager.default.removeItem(at: url)
        }
    }
}
