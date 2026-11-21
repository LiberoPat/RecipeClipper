import Foundation

/// An import from Paprika's outcome (#382; Android's `PaprikaResult`): counts, or the cause. The
/// screen picks the words.
enum PaprikaResult: Equatable {
    case success(PaprikaSummary)
    case failure(PaprikaError)
}

/// Reads a Paprika export (#382; Android's `PaprikaImportRepository`) into the library.
protocol PaprikaImportRepository: AnyObject {
    /// Merges the recipes of the picked `.paprikarecipes` file in (never replaces, never deletes;
    /// see `PaprikaImport`). A file that can't be read writes nothing and says why. With `filing`
    /// (#387) each recipe's Paprika categories put it in lists of those names.
    ///
    /// `source` (#390) says whose file it is: the Recipe Keeper import is the same route with its
    /// own reader and key.
    func importPaprika(_ url: URL, filing: Bool, source: ImportSource) async -> PaprikaResult
}

extension PaprikaImportRepository {
    func importPaprika(_ url: URL, filing: Bool) async -> PaprikaResult {
        await importPaprika(url, filing: filing, source: .paprika)
    }
}

/// Where a picked Paprika file is read (Android's `PaprikaFiles`), so the repository stays free of
/// the file system and its test can use a fake.
protocol PaprikaFiles: AnyObject {
    /// The picked file's recipes, or why not (`.readFailed` if it couldn't be opened). Each recipe's
    /// picture is stored as it is read (#386): the recipe names the stored file.
    ///
    /// `source` (#390) picks the reader: a Paprika file, or a Recipe Keeper zip.
    func read(_ url: URL, source: ImportSource) async -> PaprikaRead
}

extension PaprikaFiles {
    func read(_ url: URL) async -> PaprikaRead { await read(url, source: .paprika) }
}

/// The real `PaprikaImportRepository` (Android's `DefaultPaprikaImportRepository`): the file is
/// read by `PaprikaFiles`, its recipes become a `Backup` with no lists, and
/// `BackupDao.importBackup` merges it in one transaction under the library's limit, as every
/// import does (a free library that is full leaves the rest out and counts them; nothing old is
/// removed). A database failure is logged and becomes `.saveFailed`.
final class DefaultPaprikaImportRepository: PaprikaImportRepository {
    private let files: PaprikaFiles
    private let db: AppDatabase
    private let clock: Clock
    private let library: LibraryLimitSource
    private let photos: PhotoStore?

    init(
        files: PaprikaFiles = FilePaprikaFiles(), db: AppDatabase, clock: Clock,
        library: LibraryLimitSource = FixedLibraryLimit(), photos: PhotoStore? = nil
    ) {
        self.photos = photos
        self.files = files
        self.db = db
        self.clock = clock
        self.library = library
    }

    func importPaprika(_ url: URL, filing: Bool, source: ImportSource) async -> PaprikaResult {
        let contents: PaprikaContents
        switch await files.read(url, source: source) {
        case .failure(let error): return .failure(error)
        case .success(let read): contents = read
        }
        if contents.recipes.isEmpty {
            return .success(PaprikaSummary(
                imported: 0, alreadyHere: 0, skippedForLimit: 0, notRecipes: contents.notRecipes,
                unreadable: contents.unreadable, photosLeftOut: contents.photosLeftOut, freeLimit: nil
            ))
        }
        let now = clock.now()
        let mapped = PaprikaImport.toBackup(contents.recipes, now: now, filing: filing, source: source) { UUID().uuidString.lowercased() }
        let limit = library.current()
        // The pictures are already in the store (named for the merger by their own names). Those no
        // recipe ends up with (a recipe already here, one the limit left out, a failed write) go.
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
            return .success(PaprikaSummary(
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
            dataLog.error("\(source.keyPrefix) import failed: \(String(describing: error), privacy: .public)")
            if let photos { await photos.delete(Array(stored)) }
            return .failure(.saveFailed)
        }
    }
}

/// Reads a picked file with `PaprikaZipReader`, storing each recipe's picture in the photo store as it goes. A file from the file importer is
/// security-scoped: access is opened for the read and closed after it. The file is memory-mapped
/// where the system allows, never copied, and its name is never used for anything.
final class FilePaprikaFiles: PaprikaFiles {
    private let limits: PaprikaLimits
    private let photos: PhotoStore?

    init(limits: PaprikaLimits = PaprikaLimits(), photos: PhotoStore? = nil) {
        self.limits = limits
        self.photos = photos
    }

    func read(_ url: URL, source: ImportSource) async -> PaprikaRead {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            if size > limits.maxFileBytes { return .failure(.tooLarge) }
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            let store = photos.map { store in { store.importData($0) } }
            switch source {
            case .paprika: return PaprikaZipReader.read(data, limits: limits, storePhoto: store)
            case .recipeKeeper: return RecipeKeeperZipReader.read(data, limits: limits, storePhoto: store)
            }
        } catch {
            dataLog.error("read \(source.keyPrefix) file failed: \(String(describing: error), privacy: .public)")
            return .failure(.readFailed)
        }
    }
}
