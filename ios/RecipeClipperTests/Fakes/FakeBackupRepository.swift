import Foundation
@testable import RecipeClipper

/// Answers export and import with whatever the test staged, recording what it was given.
final class FakeBackupRepository: BackupRepository {
    var exportResult: Result<ExportedBackup, BackupError> =
        .success(ExportedBackup(json: "{}", exportedAt: 1_000, recipeCount: 0))
    var importResult: Result<ImportSummary, BackupError> =
        .success(ImportSummary(recipesAdded: 0, listsAdded: 0, recipesAlreadyHere: 0, recipesSkipped: 0))
    private(set) var exportCalls = 0
    private(set) var importedTexts: [String] = []

    func export() async -> Result<ExportedBackup, BackupError> {
        exportCalls += 1
        return exportResult
    }

    /// The pictures each import was given (#116), by path in the zip.
    private(set) var importedPhotos: [[String: URL]] = []

    func importBackup(_ package: BackupPackage) async -> Result<ImportSummary, BackupError> {
        importedTexts.append(package.json)
        importedPhotos.append(package.photos)
        return importResult
    }
}

/// An in-memory BackupFiles: `files` maps a URL to its text; writes are recorded.
final class FakeBackupFiles: BackupFiles {
    var files: [URL: String] = [:]
    /// What `writeExport` returns; nil stands for a failed write.
    var writeURL: URL? = URL(fileURLWithPath: "/tmp/recipe-clipper-test.json")
    private(set) var written: [(json: String, exportedAt: Int64)] = []

    /// The pictures each `writeExport` was given (#116).
    private(set) var writtenPhotos: [[String: URL]] = []

    func writeExport(json: String, exportedAt: Int64, photos: [String: URL]) async -> URL? {
        written.append((json, exportedAt))
        writtenPhotos.append(photos)
        return writeURL
    }

    func read(_ url: URL) async -> Result<BackupPackage, BackupError> {
        files[url].map { .success(BackupPackage(json: $0)) } ?? .failure(.readFailed)
    }
}
