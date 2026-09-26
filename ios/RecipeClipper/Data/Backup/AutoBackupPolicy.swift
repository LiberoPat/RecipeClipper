import CryptoKit
import Foundation

/// Where the automatic copy (#150) goes, as the platform sees it right now (Android's
/// `BackupDestination`). iOS uses `.ready` (iCloud Drive) and `.unavailable` (no iCloud Drive:
/// signed out, turned off, or no container); `.notChosen` and `.lost` are Android's folder states.
enum BackupDestination: Equatable {
    case notChosen, ready, lost, unavailable
}

/// What the app remembers about the automatic copy (#150). `lastBackupAt` is the last copy
/// written (automatically or by "Back up now"); a manual Export doesn't count, since the app
/// never learns where the share sheet put it. `lastFingerprint` and `lastFile` say what that
/// copy held and what it is called, so an unchanged library isn't written again.
struct AutoBackupRecord: Equatable {
    var enabled = true
    var lastBackupAt: Int64?
    var lastFingerprint: String?
    var lastFile: String?
    var lastFailed = false
    /// Android's one-time folder card; kept for parity, never shown on iOS.
    var folderPromptDone = false
}

/// The automatic copy's rules (Android's `AutoBackupPolicy`), pure so both platforms test them
/// the same way. The platform decides when to ask; this decides whether a copy is worth writing,
/// what it is called, which old copies go, and what Settings says.
enum AutoBackupPolicy {
    /// Copies kept in the folder: the newest three, so one bad write never leaves nothing.
    static let keep = 3
    /// A changed library is copied at most this often, however many times the app is left.
    static let minGapMs: Int64 = 60 * 60 * 1000
    /// An unchanged library is still copied again this often, so the date stays true.
    static let refreshMs: Int64 = 7 * 24 * 60 * 60 * 1000
    /// Settings nudges when the last copy is older than this and nothing is copying.
    static let nudgeAfterMs: Int64 = 30 * 24 * 60 * 60 * 1000

    static let filePrefix = "recipe-clipper-backup-"

    /// The automatic copy is actually being kept: on, and somewhere to put it.
    static func isWorking(_ record: AutoBackupRecord, _ destination: BackupDestination) -> Bool {
        record.enabled && destination == .ready
    }

    /// Whether a copy should be written now. Always for `force` ("Back up now"). Otherwise: never
    /// copied, the last copy is gone from the folder, the library changed and the last copy is at
    /// least `minGapMs` old, or the last copy is `refreshMs` old.
    static func isDue(
        _ record: AutoBackupRecord, fingerprint: String, existing: [String], now: Int64, force: Bool = false
    ) -> Bool {
        if force { return true }
        guard let last = record.lastBackupAt else { return true }
        guard let file = record.lastFile, existing.contains(file) else { return true }
        let age = now - last
        if age >= refreshMs || age < 0 { return true }
        return fingerprint != record.lastFingerprint && age >= minGapMs
    }

    /// A gentle line in Settings: the last copy is over 30 days old and nothing is copying.
    static func needsNudge(_ record: AutoBackupRecord, _ destination: BackupDestination, now: Int64) -> Bool {
        if isWorking(record, destination) { return false }
        guard let last = record.lastBackupAt else { return false }
        return now - last > nudgeAfterMs
    }

    /// Android's one-time Home card; on iOS the destination is never `.notChosen`, so never.
    static func offersFolderPrompt(_ record: AutoBackupRecord, _ destination: BackupDestination, libraryEmpty: Bool) -> Bool {
        !libraryEmpty && record.enabled && !record.folderPromptDone && destination == .notChosen
    }

    /// `recipe-clipper-backup-2026-09-26-1430.zip`, in the phone's time zone: sorts by time.
    static func fileName(now: Int64, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd-HHmm"
        return filePrefix + formatter.string(from: Date(timeIntervalSince1970: TimeInterval(now) / 1000)) + ".zip"
    }

    /// True for a name this feature wrote; nothing else in the folder is ever touched.
    static func isBackupFile(_ name: String) -> Bool {
        name.range(of: #"^recipe-clipper-backup-\d{4}-\d{2}-\d{2}-\d{4}\.zip$"#, options: .regularExpression) != nil
    }

    /// The app's own copies beyond the newest `keep`, never `written` and never anything else.
    static func toDelete(existing: [String], written: String) -> [String] {
        let ours = Array(Set(existing.filter(isBackupFile))).sorted(by: >)
        let kept = Set([written] + ours.filter { $0 != written }.prefix(keep - 1))
        return ours.filter { !kept.contains($0) }
    }

    /// What a copy holds, without the moment it was written: the export's JSON with its
    /// `exportedAt` blanked, and the pictures' names. Equal fingerprints mean nothing changed.
    static func fingerprint(json: String, photos: [String]) -> String {
        let blanked = json.replacingOccurrences(
            of: #""exportedAt"\s*:\s*-?\d+"#, with: "\"exportedAt\":0", options: .regularExpression
        )
        var hasher = SHA256()
        hasher.update(data: Data(blanked.utf8))
        for photo in photos.sorted() {
            hasher.update(data: Data([0]))
            hasher.update(data: Data(photo.utf8))
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
