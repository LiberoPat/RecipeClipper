import Foundation
@testable import RecipeClipper

/// Answers an import with whatever the test staged, recording what it was given (`AsyncGate` is in
/// `FakePaprikaImport.swift`).
final class FakeMealieImportRepository: MealieImportRepository {
    var result: MealieResult = .success(MealieSummary(
        imported: 0, alreadyHere: 0, skippedForLimit: 0, notRecipes: 0, unreadable: 0, photosLeftOut: 0, freeLimit: nil
    ))
    /// When set, `importMealie` waits for it: lets a test look at the state mid-import.
    var gate: AsyncGate?
    private(set) var imported: [URL] = []
    /// The `filing` each import was asked for (#387).
    private(set) var filings: [Bool] = []

    func importMealie(_ url: URL, filing: Bool) async -> MealieResult {
        imported.append(url)
        filings.append(filing)
        await gate?.wait()
        return result
    }
}

/// A `MealieFiles` that reads fixture bytes by URL through the real zip reader, storing pictures in `photos`.
final class FakeMealieFiles: MealieFiles {
    var files: [URL: Data] = [:]
    var limits = MealieLimits()
    var photos: PhotoStore?

    func read(_ url: URL) async -> MealieRead {
        files[url].map { data in
            MealieZipReader.read(data, limits: limits, storePhoto: photos.map { store in { store.importData($0) } })
        } ?? .failure(.readFailed)
    }
}
