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

    func importBackup(_ text: String) async -> Result<ImportSummary, BackupError> {
        importedTexts.append(text)
        return importResult
    }
}

/// An in-memory BackupFiles: `files` maps a URL to its text; writes are recorded.
final class FakeBackupFiles: BackupFiles {
    var files: [URL: String] = [:]
    /// What `writeExport` returns; nil stands for a failed write.
    var writeURL: URL? = URL(fileURLWithPath: "/tmp/recipe-clipper-test.json")
    private(set) var written: [(json: String, exportedAt: Int64)] = []

    func writeExport(json: String, exportedAt: Int64) async -> URL? {
        written.append((json, exportedAt))
        return writeURL
    }

    func readText(_ url: URL) async -> Result<String, BackupError> {
        files[url].map { .success($0) } ?? .failure(.readFailed)
    }
}
