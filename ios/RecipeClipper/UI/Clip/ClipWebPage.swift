import SwiftUI
import WebKit

/// What the page reports, decoded from `clipper.js`'s messages.
enum ClipPageEvent: Equatable {
    case selection(String)
    case tagTapped(ClipField)
    case imageTapped(String)

    static func decode(_ json: String) -> ClipPageEvent? {
        guard let data = json.data(using: .utf8),
              let message = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = message["type"] as? String
        else { return nil }
        switch type {
        case "selection": return .selection(message["text"] as? String ?? "")
        case "tag": return (message["field"] as? String).flatMap(ClipField.init(rawValue:)).map { .tagTapped($0) }
        case "image": return (message["src"] as? String).flatMap { $0.isEmpty ? nil : .imageTapped($0) }
        default: return nil
        }
    }
}

/// `shared/web/clipper.js`, bundled as the web/ folder (one copy for both platforms).
enum ClipperScript {
    static let source: String = {
        guard let url = Bundle.main.url(forResource: "clipper", withExtension: "js", subdirectory: "web"),
              let source = try? String(contentsOf: url, encoding: .utf8)
        else { fatalError("web/clipper.js is missing from the bundle") }
        return source
    }()
}

/// The page being clipped, in a `WKWebView` with `clipper.js` injected at document end. The view
/// layer's half of the bridge (Android's ClipWebPage): it forwards the page's events and pushes
/// `syncState` and `pickingPhoto` into the page whenever they change. Links to other pages are
/// blocked, so the clip always comes from the page it is saved under. `fixtureHTML` replaces the
/// live page in UI tests.
struct ClipWebPage: UIViewRepresentable {
    let url: String
    var fixtureHTML: String? = nil
    let syncState: String
    let pickingPhoto: Bool
    let onEvent: (ClipPageEvent) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let coordinator = context.coordinator
        coordinator.onEvent = onEvent
        let config = WKWebViewConfiguration()
        config.userContentController.addUserScript(
            WKUserScript(source: ClipperScript.source, injectionTime: .atDocumentEnd, forMainFrameOnly: true)
        )
        config.userContentController.add(WeakMessageHandler(coordinator), name: "rc")
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = coordinator
        webView.accessibilityIdentifier = "clip.page"
        if let fixtureHTML {
            webView.loadHTMLString(fixtureHTML, baseURL: URL(string: url))
        } else if let pageUrl = URL(string: url) {
            webView.load(URLRequest(url: pageUrl))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let coordinator = context.coordinator
        coordinator.onEvent = onEvent
        coordinator.script = "window.RC && (RC.sync(\(syncState)), RC.pickImage(\(pickingPhoto)));"
        coordinator.push(to: webView)
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "rc")
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var onEvent: (ClipPageEvent) -> Void = { _ in }
        var script = ""
        private var loaded = false
        private var sent: String?

        /// Sends the latest state once the page has the script, and only when it changed.
        func push(to webView: WKWebView) {
            guard loaded, script != sent else { return }
            sent = script
            webView.evaluateJavaScript(script, completionHandler: nil)
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            loaded = false
            sent = nil
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            loaded = true
            sent = nil
            push(to: webView)
        }

        /// Redirects, the first load and fragment jumps load; a tapped link to any other page,
        /// or a form sent to one, does not.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void
        ) {
            let userNavigation = navigationAction.navigationType == .linkActivated
                || navigationAction.navigationType == .formSubmitted
            guard navigationAction.targetFrame?.isMainFrame == true, userNavigation else {
                decisionHandler(.allow)
                return
            }
            decisionHandler(Self.samePage(navigationAction.request.url, webView.url) ? .allow : .cancel)
        }

        private static func samePage(_ a: URL?, _ b: URL?) -> Bool {
            guard let a, let b else { return false }
            var left = URLComponents(url: a, resolvingAgainstBaseURL: true)
            var right = URLComponents(url: b, resolvingAgainstBaseURL: true)
            left?.fragment = nil
            right?.fragment = nil
            return left?.url == right?.url
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let json = message.body as? String, let event = ClipPageEvent.decode(json) else { return }
            onEvent(event)
        }
    }
}

/// The content controller holds its handlers strongly; this keeps it from holding the
/// coordinator (and through it the view) alive.
private final class WeakMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(userContentController, didReceive: message)
    }
}
