import Foundation
@testable import RecipeClipper

/// Answers an import with whatever the test staged, recording what it was given.
final class FakePaprikaImportRepository: PaprikaImportRepository {
    var result: PaprikaResult = .success(PaprikaSummary(
        imported: 0, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosNotImported: false, freeLimit: nil
    ))
    /// When set, `importPaprika` waits for it: lets a test look at the state mid-import.
    var gate: AsyncGate?
    private(set) var imported: [URL] = []

    func importPaprika(_ url: URL) async -> PaprikaResult {
        imported.append(url)
        await gate?.wait()
        return result
    }
}

/// Holds an import open until a test opens it.
actor AsyncGate {
    private var open = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if open { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func release() {
        open = true
        waiters.forEach { $0.resume() }
        waiters = []
    }
}

/// A `PaprikaFiles` that reads fixture bytes by URL through the real zip reader.
final class FakePaprikaFiles: PaprikaFiles {
    var files: [URL: Data] = [:]
    var limits = PaprikaLimits()

    func read(_ url: URL) async -> PaprikaRead {
        files[url].map { PaprikaZipReader.read($0, limits: limits) } ?? .failure(.readFailed)
    }
}
