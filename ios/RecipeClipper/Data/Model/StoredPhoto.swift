import Foundation

/// A recipe's own picture stored on the phone (#386; Android's `StoredPhoto`), as the screens name
/// it to the image loader: `photo://<file name>`, the name of a file in the photo store. A web
/// picture is still its plain link. One string, so every place that shows a recipe's picture
/// passes the same thing whichever it is, and `ImageLoader` turns it into the file. Pure.
enum StoredPhoto {
    static let scheme = "photo://"

    static func model(_ fileName: String) -> String { scheme + fileName }

    /// The file name `model` names, or nil if it is a web link or anything else. A name is a plain
    /// name: one with a path in it names no file.
    static func fileName(_ model: String) -> String? {
        guard model.hasPrefix(scheme) else { return nil }
        let name = String(model.dropFirst(scheme.count))
        guard !name.isEmpty, !name.contains("/"), !name.contains("\\"), name != "..", name != "." else { return nil }
        return name
    }
}
