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
    /// see `PaprikaImport`). A file that can't be read writes nothing and says why.
    func importPaprika(_ url: URL) async -> PaprikaResult
}

/// Where a picked Paprika file is read (Android's `PaprikaFiles`), so the repository stays free of
/// the file system and its test can use a fake.
protocol PaprikaFiles: AnyObject {
    /// The picked file's recipes, or why not (`.readFailed` if it couldn't be opened).
    func read(_ url: URL) async -> PaprikaRead
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

    init(files: PaprikaFiles = FilePaprikaFiles(), db: AppDatabase, clock: Clock, library: LibraryLimitSource = FixedLibraryLimit()) {
        self.files = files
        self.db = db
        self.clock = clock
        self.library = library
    }

    func importPaprika(_ url: URL) async -> PaprikaResult {
        let contents: PaprikaContents
        switch await files.read(url) {
        case .failure(let error): return .failure(error)
        case .success(let read): contents = read
        }
        let photos = contents.recipes.contains { $0.hasPhoto }
        if contents.recipes.isEmpty {
            return .success(PaprikaSummary(
                imported: 0, alreadyHere: 0, skippedForLimit: 0, notRecipes: contents.notRecipes,
                unreadable: contents.unreadable, photosNotImported: photos, freeLimit: nil
            ))
        }
        let now = clock.now()
        let mapped = PaprikaImport.toBackup(contents.recipes, now: now) { UUID().uuidString.lowercased() }
        let limit = library.current()
        do {
            let summary = try await db.write { conn in
                try BackupDao(db: conn).importBackup(mapped.backup, limit: limit, today: nil, now: now) {
                    UUID().uuidString.lowercased()
                }
            }
            return .success(PaprikaSummary(
                imported: summary.recipesAdded,
                alreadyHere: summary.recipesAlreadyHere + mapped.repeated,
                skippedForLimit: summary.recipesSkipped,
                notRecipes: contents.notRecipes,
                unreadable: contents.unreadable,
                photosNotImported: photos,
                freeLimit: summary.freeLimit
            ))
        } catch {
            dataLog.error("paprika import failed: \(String(describing: error), privacy: .public)")
            return .failure(.saveFailed)
        }
    }
}

/// Reads a picked file with `PaprikaZipReader`. A file from the file importer is
/// security-scoped: access is opened for the read and closed after it. The file is memory-mapped
/// where the system allows, never copied, and its name is never used for anything.
final class FilePaprikaFiles: PaprikaFiles {
    private let limits: PaprikaLimits

    init(limits: PaprikaLimits = PaprikaLimits()) { self.limits = limits }

    func read(_ url: URL) async -> PaprikaRead {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            if size > limits.maxFileBytes { return .failure(.tooLarge) }
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            return PaprikaZipReader.read(data, limits: limits)
        } catch {
            dataLog.error("read paprika file failed: \(String(describing: error), privacy: .public)")
            return .failure(.readFailed)
        }
    }
}
