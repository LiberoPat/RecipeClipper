import Foundation

/// An import from Mealie's outcome (#391; Android's `MealieResult`): counts, or the cause. The screen
/// picks the words.
enum MealieResult: Equatable {
    case success(MealieSummary)
    case failure(MealieError)
}

/// Reads a Mealie export (#391; Android's `MealieImportRepository`) into the library.
protocol MealieImportRepository: AnyObject {
    /// Merges the recipes of the picked file in (never replaces, never deletes; see `MealieImport`). A file
    /// that can't be read writes nothing and says why. With `filing` (#387) each recipe's categories and
    /// tags put it in lists of those names.
    func importMealie(_ url: URL, filing: Bool) async -> MealieResult
}

/// Where a picked Mealie file is read (Android's `MealieFiles`), so the repository stays free of the file
/// system and its test can use a fake.
protocol MealieFiles: AnyObject {
    /// The picked file's recipes, or why not (`.readFailed` if it couldn't be opened). Each recipe's picture
    /// is stored as it is read (#386): the recipe names the stored file.
    func read(_ url: URL) async -> MealieRead
}

/// The real `MealieImportRepository` (Android's `DefaultMealieImportRepository`, `DefaultPaprikaImportRepository`'s
/// twin): the file is read by `MealieFiles`, its recipes become a `Backup`, and `BackupDao.importBackup`
/// merges it in one transaction under the library's limit, as every import does (a free library that is full
/// leaves the rest out and counts them; nothing old is removed). A database failure is logged and becomes
/// `.saveFailed`.
final class DefaultMealieImportRepository: MealieImportRepository {
    private let files: MealieFiles
    private let db: AppDatabase
    private let clock: Clock
    private let library: LibraryLimitSource
    private let photos: PhotoStore?

    init(
        files: MealieFiles = FileMealieFiles(), db: AppDatabase, clock: Clock,
        library: LibraryLimitSource = FixedLibraryLimit(), photos: PhotoStore? = nil
    ) {
        self.files = files
        self.db = db
        self.clock = clock
        self.library = library
        self.photos = photos
    }

    func importMealie(_ url: URL, filing: Bool) async -> MealieResult {
        let contents: MealieContents
        switch await files.read(url) {
        case .failure(let error): return .failure(error)
        case .success(let read): contents = read
        }
        if contents.recipes.isEmpty {
            return .success(MealieSummary(
                imported: 0, alreadyHere: 0, skippedForLimit: 0, notRecipes: contents.notRecipes,
                unreadable: contents.unreadable, photosLeftOut: contents.photosLeftOut, freeLimit: nil
            ))
        }
        let now = clock.now()
        let mapped = MealieImport.toBackup(contents.recipes, now: now, filing: filing) { UUID().uuidString.lowercased() }
        let limit = library.current()
        // The pictures are already in the store (named for the merger by their own names). Those no recipe
        // ends up with (a recipe already here, one the limit left out, a failed write) go.
        let stored = Set(contents.recipes.compactMap(\.photoFile))
        do {
            let summary = try await db.write { [stored] conn in
                try BackupDao(db: conn).importBackup(
                    mapped.backup, limit: limit, today: nil, storedPhotos: Dictionary(uniqueKeysWithValues: stored.map { ($0, $0) }),
                    now: now, dropEmptyNewLists: true
                ) { UUID().uuidString.lowercased() }
            }
            if !stored.isEmpty, let photos {
                let named = (try? await db.read { conn in try BackupDao(db: conn).recipePhotoFiles() }) ?? stored
                await photos.delete(Array(stored.subtracting(named)))
            }
            return .success(MealieSummary(
                imported: summary.recipesAdded,
                alreadyHere: summary.recipesAlreadyHere + mapped.repeated,
                skippedForLimit: summary.recipesSkipped,
                notRecipes: contents.notRecipes,
                unreadable: contents.unreadable,
                photosLeftOut: contents.photosLeftOut,
                freeLimit: summary.freeLimit,
                listsMade: summary.listsAdded
            ))
        } catch {
            dataLog.error("mealie import failed: \(String(describing: error), privacy: .public)")
            if let photos { await photos.delete(Array(stored)) }
            return .failure(.saveFailed)
        }
    }
}

/// Reads a picked file with `MealieZipReader`, storing each recipe's picture in the photo store as it goes.
/// A file from the file importer is security-scoped: access is opened for the read and closed after it. The
/// file is memory-mapped where the system allows, never copied, and its name is never used for anything.
final class FileMealieFiles: MealieFiles {
    private let limits: MealieLimits
    private let photos: PhotoStore?

    init(limits: MealieLimits = MealieLimits(), photos: PhotoStore? = nil) {
        self.limits = limits
        self.photos = photos
    }

    func read(_ url: URL) async -> MealieRead {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            if size > limits.maxFileBytes { return .failure(.tooLarge) }
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            return MealieZipReader.read(data, limits: limits, storePhoto: photos.map { store in { store.importData($0) } })
        } catch {
            dataLog.error("read mealie file failed: \(String(describing: error), privacy: .public)")
            return .failure(.readFailed)
        }
    }
}
