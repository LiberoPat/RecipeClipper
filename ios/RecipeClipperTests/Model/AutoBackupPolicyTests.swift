import XCTest
@testable import RecipeClipper

/// The automatic copy's rules (#150); Android pins the same cases in AutoBackupPolicyTest.
final class AutoBackupPolicyTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000
    private var copied: AutoBackupRecord {
        AutoBackupRecord(lastBackupAt: now, lastFingerprint: "a", lastFile: "copy.zip")
    }

    func testAFirstCopyIsAlwaysDue() {
        XCTAssertTrue(AutoBackupPolicy.isDue(AutoBackupRecord(), fingerprint: "a", existing: [], now: now))
    }

    func testAnUnchangedLibraryIsNotCopiedAgainWithinTheWeekButIsAfterIt() {
        let week = AutoBackupPolicy.refreshMs
        XCTAssertFalse(AutoBackupPolicy.isDue(copied, fingerprint: "a", existing: ["copy.zip"], now: now + week - 1))
        XCTAssertTrue(AutoBackupPolicy.isDue(copied, fingerprint: "a", existing: ["copy.zip"], now: now + week))
    }

    func testAChangeWaitsForTheGapSinceTheLastCopy() {
        let gap = AutoBackupPolicy.minGapMs
        XCTAssertFalse(AutoBackupPolicy.isDue(copied, fingerprint: "b", existing: ["copy.zip"], now: now + gap - 1))
        XCTAssertTrue(AutoBackupPolicy.isDue(copied, fingerprint: "b", existing: ["copy.zip"], now: now + gap))
    }

    func testACopyGoneFromTheFolderIsWrittenAgainAtOnce() {
        XCTAssertTrue(AutoBackupPolicy.isDue(copied, fingerprint: "a", existing: ["other.zip"], now: now + 1))
    }

    func testBackUpNowAlwaysWritesAndAClockSetBackDoesNotStopTheCopy() {
        XCTAssertTrue(AutoBackupPolicy.isDue(copied, fingerprint: "a", existing: ["copy.zip"], now: now + 1, force: true))
        XCTAssertTrue(AutoBackupPolicy.isDue(copied, fingerprint: "a", existing: ["copy.zip"], now: now - 1))
    }

    func testTheNudgeNeedsAnOldCopyAndNothingCopying() {
        let old = AutoBackupRecord(lastBackupAt: now - AutoBackupPolicy.nudgeAfterMs - 1)
        XCTAssertTrue(AutoBackupPolicy.needsNudge(old, .unavailable, now: now))
        var off = old
        off.enabled = false
        XCTAssertTrue(AutoBackupPolicy.needsNudge(off, .ready, now: now))
        XCTAssertFalse(AutoBackupPolicy.needsNudge(old, .ready, now: now), "copying: no nudge")
        XCTAssertFalse(AutoBackupPolicy.needsNudge(AutoBackupRecord(), .unavailable, now: now), "never copied")
        XCTAssertFalse(AutoBackupPolicy.needsNudge(
            AutoBackupRecord(lastBackupAt: now - AutoBackupPolicy.nudgeAfterMs), .unavailable, now: now
        ), "30 days exactly")
    }

    func testTheFolderCardIsAndroidsOnly() {
        XCTAssertFalse(AutoBackupPolicy.offersFolderPrompt(AutoBackupRecord(), .unavailable, libraryEmpty: false))
        XCTAssertFalse(AutoBackupPolicy.offersFolderPrompt(AutoBackupRecord(), .ready, libraryEmpty: false))
        XCTAssertTrue(AutoBackupPolicy.offersFolderPrompt(AutoBackupRecord(), .notChosen, libraryEmpty: false))
        XCTAssertFalse(AutoBackupPolicy.offersFolderPrompt(AutoBackupRecord(), .notChosen, libraryEmpty: true))
    }

    func testCopiesAreNamedByTheLocalMinute() {
        let name = AutoBackupPolicy.fileName(now: now, timeZone: TimeZone(identifier: "UTC")!)
        XCTAssertEqual(name, "recipe-clipper-backup-2027-01-15-0800.zip")
        XCTAssertTrue(AutoBackupPolicy.isBackupFile(name))
    }

    func testOnlyTheAppsOwnCopiesBeyondTheNewestThreeGo() {
        let ours = (1 ... 5).map { "recipe-clipper-backup-2026-09-0\($0)-1200.zip" }
        let theirs = ["recipe-clipper-2026-09-01.zip", "notes.txt", "recipe-clipper-backup-2026-09-01-1200 (1).zip"]
        let gone = AutoBackupPolicy.toDelete(existing: ours + theirs, written: ours.last!)
        XCTAssertEqual(Set(gone), Set(ours.prefix(5 - AutoBackupPolicy.keep)))
    }

    func testTheCopyJustWrittenIsKeptWhateverItsName() {
        let ours = (1 ... 4).map { "recipe-clipper-backup-2026-09-0\($0)-1200.zip" }
        let gone = AutoBackupPolicy.toDelete(existing: ours + ["renamed.zip"], written: "renamed.zip")
        XCTAssertEqual(Set(gone), Set(ours.prefix(2)))
    }

    func testTheFingerprintIgnoresWhenTheExportWasMadeAndNothingElse() {
        let a = #"{"format":"recipe-clipper-backup","exportedAt":1,"recipes":[]}"#
        let b = #"{"format" : "recipe-clipper-backup", "exportedAt" : 2, "recipes" : []}"#
            .replacingOccurrences(of: " : ", with: ":").replacingOccurrences(of: ", ", with: ",")
        let c = #"{"format":"recipe-clipper-backup","exportedAt":2,"recipes":[{"title":"Soup"}]}"#
        XCTAssertEqual(AutoBackupPolicy.fingerprint(json: a, photos: []), AutoBackupPolicy.fingerprint(json: b, photos: []))
        XCTAssertNotEqual(AutoBackupPolicy.fingerprint(json: b, photos: []), AutoBackupPolicy.fingerprint(json: c, photos: []))
        XCTAssertNotEqual(
            AutoBackupPolicy.fingerprint(json: a, photos: []), AutoBackupPolicy.fingerprint(json: a, photos: ["photos/p.jpg"])
        )
    }
}
