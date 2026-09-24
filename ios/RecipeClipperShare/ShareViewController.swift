import UIKit
import UniformTypeIdentifiers

/// The share target: iOS's counterpart of Android's ACTION_SEND handling. It shows no UI of
/// its own. It finds the shared link, hands it to the app as
/// `recipeclipper://import?url=<percent-encoded>`, and finishes; the app pushes the import
/// screen from `.onOpenURL`, which parses and displays with no save prompt.
///
/// The extension can't link the app's sources, so the few lines that find a link in shared
/// text are duplicated here (the app's `UrlInput.extractSharedUrl` does the same job), as is
/// the deep-link encoding (`DeepLink.importUrl`).
final class ShareViewController: UIViewController {

    private var handled = false

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !handled else { return }
        handled = true
        Task { @MainActor in
            // Whatever happens (no link, an unreadable attachment, the app not opening), the
            // request is always completed, so the share sheet never hangs on this extension.
            if let link = await sharedLink(), let deepLink = Self.deepLink(for: link) {
                openContainingApp(deepLink)
            }
            extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        }
    }

    /// The first web link among the attachments: a URL item if there is one, else the first
    /// http(s) link inside shared plain text (what many apps send instead).
    private func sharedLink() async -> String? {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }

        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            if let item = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier),
               let url = (item as? URL)
                    ?? (item as? String).flatMap(URL.init(string:))
                    ?? (item as? Data).flatMap({ URL(dataRepresentation: $0, relativeTo: nil) }),
               let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
                return url.absoluteString
            }
        }
        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            if let item = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier),
               let text = (item as? String) ?? (item as? Data).flatMap({ String(data: $0, encoding: .utf8) }),
               let link = Self.firstLink(in: text) {
                return link
            }
        }
        return nil
    }

    /// The first `http(s)://…` run of non-whitespace in `text` (Android's `https?://\S+`).
    static func firstLink(in text: String) -> String? {
        guard let range = text.range(of: #"https?://\S+"#, options: [.regularExpression, .caseInsensitive]) else {
            return nil
        }
        return String(text[range])
    }

    static func deepLink(for link: String) -> URL? {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        guard let encoded = link.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
        return URL(string: "recipeclipper://import?url=\(encoded)")
    }

    /// Extensions have no `UIApplication.shared`, but the application is in the responder
    /// chain. On iOS 18 and later its `open(_:options:completionHandler:)` is what works (the
    /// old `openURL:` became a no-op); the deployment target is iOS 17, where only `openURL:`
    /// does, so that is the fallback.
    private func openContainingApp(_ url: URL) {
        var responder: UIResponder? = self
        while let current = responder {
            if let application = current as? UIApplication {
                if #available(iOS 18.0, *) {
                    application.open(url, options: [:], completionHandler: nil)
                } else {
                    let openURL = NSSelectorFromString("openURL:")
                    if application.responds(to: openURL) { _ = application.perform(openURL, with: url) }
                }
                return
            }
            responder = current.next
        }
    }
}
