import Foundation
import UniformTypeIdentifiers

/// What a share carried that the import can use.
struct SharedInput: Equatable {
    /// The shared web link, as shared (the repository cleans it).
    var url: String
    /// Safari's own copy of the page (#35: `document.documentElement.outerHTML`, via the share
    /// extension's JavaScript preprocessing file), when Safari ran it and shared a web page.
    /// Nil from every other app, which shares only the link.
    var page: String? = nil
}

/// Reads the share extension's attachments (iOS's counterpart of Android's ACTION_SEND
/// extras). Separate from the view controller so it can be tested with real item providers.
enum SharedItems {

    /// Safari's preprocessing result first (it carries the rendered page, and the URL
    /// JavaScript actually resolved), else a URL item, else the first http(s) link inside
    /// shared plain text (what many apps send instead). Nil if none of those held a web link.
    static func read(from providers: [NSItemProvider]) async -> SharedInput? {
        if let preprocessed = await preprocessingResult(from: providers) { return preprocessed }
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

    /// The first plain text the share carried (#149: a list sent from another phone, when it held
    /// no link). Nil when there is none.
    static func text(from providers: [NSItemProvider]) async -> String? {
        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            if let item = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier),
               let text = (item as? String) ?? (item as? Data).flatMap({ String(data: $0, encoding: .utf8) }) {
                return text
            }
        }
        return nil
    }

    /// The result of `Preprocessing.js`'s `ExtensionPreprocessingJS.run`, delivered as a
    /// property-list item under `NSExtensionJavaScriptPreprocessingResultsKey` (both the type
    /// identifier to load and, inside the loaded dictionary, the key holding what
    /// `completionFunction` was called with: `{url, html}`). Nil when Safari didn't run the
    /// script (a non-web-page share, or another app entirely), or when it carried no URL.
    private static func preprocessingResult(from providers: [NSItemProvider]) async -> SharedInput? {
        for provider in providers
        where provider.hasItemConformingToTypeIdentifier(NSExtensionJavaScriptPreprocessingResultsKey) {
            guard let item = try? await provider.loadItem(
                forTypeIdentifier: NSExtensionJavaScriptPreprocessingResultsKey, options: nil
            ),
                let outer = item as? [String: Any],
                let results = outer[NSExtensionJavaScriptPreprocessingResultsKey] as? [String: Any],
                let urlString = results["url"] as? String,
                let scheme = URL(string: urlString)?.scheme?.lowercased(), scheme == "http" || scheme == "https"
            else { continue }
            return SharedInput(url: urlString, page: results["html"] as? String)
        }
        return nil
    }
}
