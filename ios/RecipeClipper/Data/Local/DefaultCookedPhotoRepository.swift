import Combine
import Foundation

/// "I made this" (#116; Android's CookedPhotoRepository): the user's own photos of a recipe,
/// each with a day and a short note. A photo's file outlives its row until the delete stands
/// (so Undo can bring it back); `sweep` removes files no row names.
protocol CookedPhotoRepository: AnyObject {
    func observe(recipeId: Int64) -> AnyPublisher<[CookedPhoto], Never>
    /// Stores each picture downscaled, one entry each, cooked today with no note. The new
    /// entries, in order; one that couldn't be read is left out.
    func add(recipeId: Int64, pictures: [Data]) async -> [CookedPhoto]
    func edit(id: Int64, day: Int64, note: String?) async
    /// Deletes the entry; its file stays until `forget` or `sweep`, so `restore` can undo it.
    func delete(id: Int64) async -> CookedPhoto?
    func restore(_ photo: CookedPhoto) async
    /// The delete stands: removes the files of `photos`.
    func forget(_ photos: [CookedPhoto]) async
    /// Removes stored files no entry names, except very recent ones (an add in flight). Never an
    /// entry: one whose file is missing keeps its day and note.
    func sweep() async
}

final class DefaultCookedPhotoRepository: CookedPhotoRepository {
    private let db: AppDatabase
    private let store: PhotoStore
    private let clock: Clock

    init(db: AppDatabase, store: PhotoStore, clock: Clock) {
        self.db = db
        self.store = store
        self.clock = clock
    }

    func observe(recipeId: Int64) -> AnyPublisher<[CookedPhoto], Never> {
        let store = store
        return db.observe { conn in try CookedPhotoDao(db: conn).photosFor(recipeId).map { $0.domain(store) } }
    }

    func add(recipeId: Int64, pictures: [Data]) async -> [CookedPhoto] {
        var added: [CookedPhoto] = []
        for data in pictures {
            guard let name = await store.importPicture(data) else { continue }
            let now = clock.now()
            var record = CookedPhotoRecord(
                recipeId: recipeId, fileName: name, day: PlanDays.today(millis: now), note: nil, createdAt: now, updatedAt: now
            )
            do {
                record.id = try await db.write { conn in try CookedPhotoDao(db: conn).insert(record) }
                added.append(record.domain(store))
            } catch {
                dataLog.error("addCookedPhoto failed: \(String(describing: error), privacy: .public)")
                await store.delete([name])
            }
        }
        return added
    }

    func edit(id: Int64, day: Int64, note: String?) async {
        let now = clock.now()
        let clean = CookedPhoto.cleanNote(note)
        await perform("editCookedPhoto") { dao in try dao.edit(id, day: day, note: clean, now: now) }
    }

    func delete(id: Int64) async -> CookedPhoto? {
        do {
            let store = store
            return try await db.write { conn -> CookedPhoto? in
                let dao = CookedPhotoDao(db: conn)
                guard let row = try dao.get(id) else { return nil }
                try dao.delete(id)
                return row.domain(store)
            }
        } catch {
            dataLog.error("deleteCookedPhoto failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func restore(_ photo: CookedPhoto) async {
        let record = CookedPhotoRecord(photo)
        await perform("restoreCookedPhoto") { dao in try dao.insert(record) }
    }

    func forget(_ photos: [CookedPhoto]) async {
        if !photos.isEmpty { await store.delete(photos.map(\.fileName)) }
    }

    func sweep() async {
        guard let named = try? await db.read({ conn in try CookedPhotoDao(db: conn).fileNames() }) else { return }
        let cutoff = clock.now() - photoSweepGraceMillis
        let orphans = await store.files().filter { !named.contains($0.key) && $0.value < cutoff }.map(\.key)
        await store.delete(orphans)
    }

    private func perform(_ what: String, _ body: @escaping (CookedPhotoDao) throws -> Void) async {
        do {
            try await db.write { conn in try body(CookedPhotoDao(db: conn)) }
        } catch {
            dataLog.error("\(what, privacy: .public) failed: \(String(describing: error), privacy: .public)")
        }
    }
}

extension CookedPhotoRecord {
    func domain(_ store: PhotoStore) -> CookedPhoto {
        CookedPhoto(
            id: id, recipeId: recipeId, fileName: fileName, path: store.path(fileName), day: day, note: note,
            createdAt: createdAt, updatedAt: updatedAt, uid: uid, hasPicture: store.exists(fileName)
        )
    }

    init(_ photo: CookedPhoto) {
        self.init(
            id: photo.id, recipeId: photo.recipeId, fileName: photo.fileName, day: photo.day, note: photo.note,
            createdAt: photo.createdAt, updatedAt: photo.updatedAt, uid: photo.uid
        )
    }
}
