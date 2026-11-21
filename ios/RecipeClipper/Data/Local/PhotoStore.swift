import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Where "I made this" photos (#116) live (Android's PhotoStore): JPEGs named by the store,
/// never by the user. Behind a protocol so repositories stay free of files and tests use a fake.
protocol PhotoStore: AnyObject {
    /// Turns the picture (library or camera data) upright, downscales it to
    /// `photoMaxEdge` on its long edge and writes it as a JPEG; its new file name, or nil.
    func importPicture(_ data: Data) async -> String?
    /// `importPicture` for a recipe's own picture whose bytes are in hand (#386: a Paprika file's
    /// `photo_data`), checked to decode as an image and no longer than `recipePhotoMaxBytes`: its new
    /// name (a fresh random one, never a name from the file), or nil, and the caller leaves that
    /// photo out and keeps the recipe. Synchronous: the file reader calls it entry by entry.
    func importData(_ data: Data) -> String?
    /// Copies a file that is already a stored photo (one from a backup) in; its new name, or nil.
    func adopt(_ file: URL) async -> String?
    func path(_ name: String) -> String
    /// Whether the file `name` is here: a phone restored without it has the row only.
    func exists(_ name: String) -> Bool
    /// Every stored file, with when it was last written (epoch millis).
    func files() async -> [String: Int64]
    func delete(_ names: [String]) async
}

/// The long edge of a stored photo: sharp on any phone screen, a few hundred KB.
let photoMaxEdge = 2048
/// The most a recipe's picture may be before it is decoded (#386): far more than a phone photo
/// downscaled for a file, so a hostile one can't make the app decode a huge image.
let recipePhotoMaxBytes = 8 * 1024 * 1024
/// The most pixels a picture may declare (#386): about a 100-megapixel photo.
let photoMaxPixels = 100_000_000
/// A file this young may belong to an add still being written: the sweep leaves it.
let photoSweepGraceMillis: Int64 = 10 * 60 * 1000

/// `CookedPhotos/` beside the database (the App Group container, which iCloud Backup carries
/// with it: never mark it excluded from backup), written with ImageIO, so neither UIKit nor the
/// main thread is involved.
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

    func importPicture(_ data: Data) async -> String? { process(data, maxPixels: nil) }

    func importData(_ data: Data) -> String? {
        guard !data.isEmpty, data.count <= recipePhotoMaxBytes else { return nil }
        return process(data, maxPixels: photoMaxPixels)
    }

    /// Decodes `data`, upright and at most `photoMaxEdge`, and stores it; nil if it declares more
    /// than `maxPixels` (a picture from a stranger's file; the phone's own photos have no cap).
    private func process(_ data: Data, maxPixels: Int?) -> String? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        if let maxPixels, let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
           let width = properties[kCGImagePropertyPixelWidth] as? Int, let height = properties[kCGImagePropertyPixelHeight] as? Int,
           width * height > maxPixels { return nil }
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

    func exists(_ name: String) -> Bool { FileManager.default.fileExists(atPath: path(name)) }

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
