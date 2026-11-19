import Foundation
@testable import RecipeClipper

/// Answers an import with whatever the test staged, recording what it was given.
final class FakePaprikaImportRepository: PaprikaImportRepository {
    var result: PaprikaResult = .success(PaprikaSummary(
        imported: 0, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosLeftOut: 0, freeLimit: nil
    ))
    /// When set, `importPaprika` waits for it: lets a test look at the state mid-import.
    var gate: AsyncGate?
    private(set) var imported: [URL] = []
    /// The `filing` each import was asked for (#387).
    private(set) var filings: [Bool] = []

    func importPaprika(_ url: URL, filing: Bool) async -> PaprikaResult {
        imported.append(url)
        filings.append(filing)
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

/// A `PaprikaFiles` that reads fixture bytes by URL through the real zip reader, storing pictures in `photos`.
final class FakePaprikaFiles: PaprikaFiles {
    var files: [URL: Data] = [:]
    var limits = PaprikaLimits()
    var photos: PhotoStore?

    func read(_ url: URL) async -> PaprikaRead {
        files[url].map { data in
            PaprikaZipReader.read(data, limits: limits, storePhoto: photos.map { store in { store.importData($0) } })
        } ?? .failure(.readFailed)
    }
}
