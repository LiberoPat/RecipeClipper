import Foundation
@testable import RecipeClipper

/// A `PhotoStore` over a temporary folder (Android's FakePhotoStore): a picture's bytes stand for
/// the picture (no decoding or downscaling, which is ImageIO's part), bytes starting "bad" aren't an
/// image, and too many are refused as the real store refuses them. Files are real, so a backup round
/// trip carries real bytes. `modified` backdates a file for the sweep's grace.
final class FakePhotoStore: PhotoStore {
    let directory: URL
    private var next = 1

    init(directory: URL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    func importPicture(_ data: Data) async -> String? {
        String(decoding: data, as: UTF8.self).hasPrefix("bad") ? nil : write(data)
    }

    func importData(_ data: Data) -> String? {
        guard !data.isEmpty, data.count <= recipePhotoMaxBytes, !String(decoding: data, as: UTF8.self).hasPrefix("bad") else { return nil }
        return write(data)
    }

    func adopt(_ file: URL) async -> String? { (try? Data(contentsOf: file)).flatMap(write) }

    func path(_ name: String) -> String { directory.appendingPathComponent(name).path }

    func exists(_ name: String) -> Bool { FileManager.default.fileExists(atPath: path(name)) }

    func files() async -> [String: Int64] {
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        return Dictionary(uniqueKeysWithValues: urls.map { url in
            let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
            return (url.lastPathComponent, Int64(date.timeIntervalSince1970 * 1000))
        })
    }

    func delete(_ names: [String]) async {
        for name in names { try? FileManager.default.removeItem(at: directory.appendingPathComponent(name)) }
    }

    func text(_ name: String) -> String? { (try? Data(contentsOf: directory.appendingPathComponent(name))).map { String(decoding: $0, as: UTF8.self) } }

    func modified(_ name: String, at millis: Int64) {
        try? FileManager.default.setAttributes(
            [.modificationDate: Date(timeIntervalSince1970: Double(millis) / 1000)], ofItemAtPath: path(name)
        )
    }

    private func write(_ data: Data) -> String {
        let name = "stored-\(next).jpg"
        next += 1
        try? data.write(to: directory.appendingPathComponent(name))
        return name
    }
}
