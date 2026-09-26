import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Where "I made this" photos (#116) live (Android's PhotoStore): JPEGs named by the store,
/// never by the user. Behind a protocol so repositories stay free of files and tests use a fake.
protocol PhotoStore: AnyObject {
    /// Turns the picture (PhotosPicker or camera data) upright, downscales it to
    /// `photoMaxEdge` on its long edge and writes it as a JPEG; its new file name, or nil.
    func importPicture(_ data: Data) async -> String?
    /// Copies a file that is already a stored photo (one from a backup) in; its new name, or nil.
    func adopt(_ file: URL) async -> String?
    func path(_ name: String) -> String
    /// Every stored file, with when it was last written (epoch millis).
    func files() async -> [String: Int64]
    func delete(_ names: [String]) async
}

/// The long edge of a stored photo: sharp on any phone screen, a few hundred KB.
let photoMaxEdge = 2048
/// A file this young may belong to an add still being written: the sweep leaves it.
let photoSweepGraceMillis: Int64 = 10 * 60 * 1000

/// `CookedPhotos/` beside the database (the App Group container, backed up with it), written
/// with ImageIO, so neither UIKit nor the main thread is involved.
final class FilePhotoStore: PhotoStore {
    private let directory: URL

    init(directory: URL) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    /// Beside the database file at `databasePath`.
    convenience init(databasePath: String) {
        self.init(directory: URL(fileURLWithPath: databasePath).deletingLastPathComponent().appendingPathComponent("CookedPhotos"))
    }

    func importPicture(_ data: Data) async -> String? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        // The thumbnail API applies the EXIF orientation and never upscales past the original.
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: photoMaxEdge,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else {
            return nil
        }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return write(out as Data)
    }

    func adopt(_ file: URL) async -> String? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        return write(data)
    }

    func path(_ name: String) -> String { directory.appendingPathComponent(name).path }

    func files() async -> [String: Int64] {
        let keys: [URLResourceKey] = [.contentModificationDateKey]
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys)) ?? []
        var result: [String: Int64] = [:]
        for url in urls where url.pathExtension == "jpg" {
            let date = (try? url.resourceValues(forKeys: Set(keys)).contentModificationDate) ?? Date()
            result[url.lastPathComponent] = Int64(date.timeIntervalSince1970 * 1000)
        }
        return result
    }

    func delete(_ names: [String]) async {
        // Only plain names in the store: a name is never a path, so nothing outside it goes.
        for name in names where !name.isEmpty && !name.contains("/") && name != ".." {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
        }
    }

    /// Written atomically, so a failure never leaves half a JPEG.
    private func write(_ data: Data) -> String? {
        let name = UUID().uuidString.lowercased() + ".jpg"
        do {
            try data.write(to: directory.appendingPathComponent(name), options: .atomic)
            return name
        } catch {
            dataLog.error("photo write failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }
}
