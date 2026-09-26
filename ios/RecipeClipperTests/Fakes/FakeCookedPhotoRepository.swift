import Combine
import Foundation
@testable import RecipeClipper

/// "I made this" (#116) in memory (Android's FakeCookedPhotoRepository). A picture that is empty
/// data can't be read, as a broken one; every other becomes a photo cooked on `today`.
final class FakeCookedPhotoRepository: CookedPhotoRepository {
    let photos = CurrentValueSubject<[CookedPhoto], Never>([])
    private(set) var forgotten: [String] = []
    private(set) var edits: [(id: Int64, day: Int64, note: String?)] = []
    var today: Int64 = 20_000
    private var nextId: Int64 = 1

    @discardableResult
    func photo(recipeId: Int64, day: Int64? = nil, note: String? = nil) -> CookedPhoto {
        let id = nextId
        nextId += 1
        let photo = CookedPhoto(
            id: id, recipeId: recipeId, fileName: "p\(id).jpg", path: "/photos/p\(id).jpg", day: day ?? today,
            note: note, createdAt: id, updatedAt: id, uid: "uid-\(id)"
        )
        photos.value.append(photo)
        return photo
    }

    func observe(recipeId: Int64) -> AnyPublisher<[CookedPhoto], Never> {
        photos.map { $0.filter { $0.recipeId == recipeId }.sorted { ($0.day, $0.id) > ($1.day, $1.id) } }
            .eraseToAnyPublisher()
    }

    func add(recipeId: Int64, pictures: [Data]) async -> [CookedPhoto] {
        pictures.filter { !$0.isEmpty }.map { _ in photo(recipeId: recipeId) }
    }

    func edit(id: Int64, day: Int64, note: String?) async {
        edits.append((id, day, note))
        photos.value = photos.value.map { p in
            guard p.id == id else { return p }
            var edited = p
            edited.day = day
            edited.note = CookedPhoto.cleanNote(note)
            return edited
        }
    }

    func delete(id: Int64) async -> CookedPhoto? {
        guard let found = photos.value.first(where: { $0.id == id }) else { return nil }
        photos.value.removeAll { $0.id == id }
        return found
    }

    func restore(_ photo: CookedPhoto) async { photos.value.append(photo) }

    func forget(_ photos: [CookedPhoto]) async { forgotten += photos.map(\.fileName) }

    func sweep() async {}
}
