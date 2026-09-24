import Foundation
import UniformTypeIdentifiers

/// What a share carried that the import can use.
struct SharedInput: Equatable {
    /// The shared web link, as shared (the repository cleans it).
    var url: String
}

/// Reads the share extension's attachments (iOS's counterpart of Android's ACTION_SEND
/// extras). Separate from the view controller so it can be tested with real item providers.
enum SharedItems {

    /// The first web link among the attachments: a URL item if there is one, else the first
    /// http(s) link inside shared plain text (what many apps send instead). Nil if neither.
    static func read(from providers: [NSItemProvider]) async -> SharedInput? {
        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            if let item = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier),
               let url = (item as? URL)
                    ?? (item as? String).flatMap(URL.init(string:))
                    ?? (item as? Data).flatMap({ URL(dataRepresentation: $0, relativeTo: nil) }),
               let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
                return SharedInput(url: url.absoluteString)
            }
        }
        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            if let item = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier),
               let text = (item as? String) ?? (item as? Data).flatMap({ String(data: $0, encoding: .utf8) }),
               let link = UrlInput.extractSharedUrl(text) {
                return SharedInput(url: link)
            }
        }
        return nil
    }
}
