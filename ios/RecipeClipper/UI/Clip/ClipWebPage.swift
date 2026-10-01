import SwiftUI
import WebKit

/// What the page reports, decoded from `clipper.js`'s messages.
enum ClipPageEvent: Equatable {
    case selection(String)
    /// An add's tag on the page: the id names the add.
    case tagTapped(String)
    case imageTapped(String)
    /// While picking a photo, the tap found no image with an address the app can read.
    case noImage
    /// The page as it stands, read while waiting on Cloudflare's check (#220).
    case pageLoaded(String)
    /// The post and its loaded comments, read for the Text view (#213).
    case pageText(String)

    /// A message from `clipper.js`, read only when it came from the page's main frame (#235).
    /// The `rc` handler is there for every frame, so an ad or another site's iframe inside the
    /// page could otherwise post a selection, a tag or a photo into the clip. Android's
    /// `ClipBridge` is the same rule.
    static func accept(_ body: Any, isMainFrame: Bool) -> ClipPageEvent? {
        guard isMainFrame, let json = body as? String else { return nil }
        return decode(json)
    }

    static func decode(_ json: String) -> ClipPageEvent? {
        guard let data = json.data(using: .utf8),
              let message = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = message["type"] as? String
        else { return nil }
        switch type {
        case "selection": return .selection(message["text"] as? String ?? "")
        case "tag": return (message["id"] as? String).flatMap { $0.isEmpty ? nil : .tagTapped($0) }
        // Whether it's a picture the app can use is the ViewModel's call (WebImageUrl).
        case "image": return .imageTapped(message["src"] as? String ?? "")
        case "noImage": return .noImage
        default: return nil
        }
    }
}

/// `shared/web/clipper.js`, bundled as the web/ folder (one copy for both platforms).
enum ClipperScript {
    static let source: String = read("clipper")

    /// `shared/web/reddit-reader.js` (#213): Reddit's page as a reader, and its text. It does
    /// nothing on any other site.
    static let redditReader: String = read("reddit-reader")

    private static func read(_ name: String) -> String {
        guard let url = Bundle.main.url(forResource: name, withExtension: "js", subdirectory: "web"),
              let source = try? String(contentsOf: url, encoding: .utf8)
        else { fatalError("web/\(name).js is missing from the bundle") }
        return source
    }

    /// The post and its comment tree for the Text view, from `reddit-reader.js`, else the page.
    static let readText = "window.RCReddit ? RCReddit.text() : document.documentElement.outerHTML"
}

/// The page being clipped, in a `WKWebView` with `clipper.js` injected at document end. The view
/// layer's half of the bridge (Android's ClipWebPage): it forwards the page's events, heard from
/// its main frame only (#235; the scripts run there only too), and pushes `syncState` and
/// `pickingPhoto` into the page whenever they change. Links to other pages are
/// blocked, so the clip always comes from the page it is saved under. `fixtureHTML` replaces the
/// live page in UI tests.
///
/// With `readsPage` (waiting on Cloudflare's check, #220), the page's HTML is sent as
/// `.pageLoaded` once it settles after each load, and every `readEvery` after, and the check
/// may move the page within its own site (its form posts back to the page with a token).
///
/// On reddit.com (#213) `reddit-reader.js` runs too, from the document's start, which shows the
/// post's whole text and hides Reddit's sign-in and app prompts; `readText` turning true reads
/// the post for the Text view, as `.pageText`. `textHTML` is the Text view's own page, loaded
/// with no address.
struct ClipWebPage: UIViewRepresentable {
    let url: String
    var fixtureHTML: String? = nil
    var textHTML: String? = nil
    let syncState: String
    let pickingPhoto: Bool
    let onEvent: (ClipPageEvent) -> Void
    var readsPage = false
    var readText = false

    /// How often the page is read again while waiting on the check, after it first settles: a
    /// check can pass without loading a new page.
    static let readEvery: Duration = .seconds(2)

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let coordinator = context.coordinator
        coordinator.onEvent = onEvent
        coordinator.readsPage = readsPage
        coordinator.pageUrl = url
        coordinator.localPage = textHTML != nil
        let config = WKWebViewConfiguration()
        config.userContentController.addUserScript(
            WKUserScript(source: ClipperScript.redditReader, injectionTime: .atDocumentStart, forMainFrameOnly: true)
        )
        config.userContentController.addUserScript(
            WKUserScript(source: ClipperScript.source, injectionTime: .atDocumentEnd, forMainFrameOnly: true)
        )
        #if DEBUG
        // Walkthrough 31 only: spots that select by script on a live page (UITestSeeding).
        if let script = UITestSeeding.clipTestScript {
            config.userContentController.addUserScript(
                WKUserScript(source: script, injectionTime: .atDocumentEnd, forMainFrameOnly: true)
            )
        }
        #endif
        config.userContentController.add(WeakMessageHandler(coordinator), name: "rc")
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = coordinator
        webView.accessibilityIdentifier = "clip.page"
        if let textHTML {
            webView.loadHTMLString(textHTML, baseURL: nil)
        } else if let fixtureHTML {
            webView.loadHTMLString(fixtureHTML, baseURL: URL(string: url))
        } else if let pageUrl = URL(string: url) {
            webView.load(URLRequest(url: pageUrl))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let coordinator = context.coordinator
        coordinator.onEvent = onEvent
        coordinator.readsPage = readsPage
        coordinator.script = "window.RC && (RC.sync(\(syncState)), RC.pickImage(\(pickingPhoto)));"
        coordinator.push(to: webView)
        if readText && !coordinator.readingText {
            coordinator.readingText = true
            webView.evaluateJavaScript(ClipperScript.readText) { [weak coordinator] result, _ in
                coordinator?.onEvent(.pageText(result as? String ?? ""))
            }
        }
        if !readText { coordinator.readingText = false }
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        coordinator.pendingRead?.cancel()
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "rc")
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var onEvent: (ClipPageEvent) -> Void = { _ in }
        var script = ""
        var readsPage = false
        var pageUrl = ""
        var readingText = false
        /// The Text view's own page (#213): it loads as about:blank, which no web page may.
        var localPage = false
        var pendingRead: Task<Void, Never>?
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
            pendingRead?.cancel()
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            loaded = true
            sent = nil
            push(to: webView)
            if readsPage { readLater(webView, after: WebViewRenderedPageSource.settle) }
        }

        /// While waiting on Cloudflare's check (#220): the page's HTML, now and then again.
        private func readLater(_ webView: WKWebView, after delay: Duration) {
            pendingRead?.cancel()
            pendingRead = Task { [weak self, weak webView] in
                try? await Task.sleep(for: delay)
                guard !Task.isCancelled, let self, let webView, readsPage else { return }
                if let html = try? await webView.evaluateJavaScript("document.documentElement.outerHTML") as? String {
                    onEvent(.pageLoaded(html))
                }
                readLater(webView, after: ClipWebPage.readEvery)
            }
        }

        /// `ClipNavigation.loads` decides for the page's own frame. WebKit doesn't mark a
        /// redirect, so everything but a link or a form (the first load, redirects, a script's
        /// move) is let through as a redirect is on Android; only a link counts as tapped.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void
        ) {
            guard navigationAction.targetFrame?.isMainFrame == true else {
                decisionHandler(.allow)
                return
            }
            if localPage {
                decisionHandler(navigationAction.request.url?.scheme == "about" ? .allow : .cancel)
                return
            }
            let type = navigationAction.navigationType
            let loads = ClipNavigation.loads(
                navigationAction.request.url?.absoluteString ?? "",
                current: webView.url?.absoluteString ?? pageUrl,
                isRedirect: type != .linkActivated && type != .formSubmitted,
                tapped: type == .linkActivated,
                withinSite: readsPage
            )
            decisionHandler(loads ? .allow : .cancel)
        }

        /// The main frame's messages only (#235): `ClipPageEvent.accept` drops the rest.
        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let event = ClipPageEvent.accept(message.body, isMainFrame: message.frameInfo.isMainFrame) else { return }
            onEvent(event)
        }
    }
}

/// Which navigations of the page's own frame the clip view follows: Android's `ClipNavigation`,
/// the same rule. Pure, so it is unit-tested.
enum ClipNavigation {

    /// Whether a navigation to `target` from `current` loads:
    /// - never an address the web view can't show (`intent:`, `reddit:`, `market:`): an app's own
    ///   link, which would leave an error, or nothing, in the post's place;
    /// - redirects, and fragment jumps, always;
    /// - the page sending itself back to its own address with a new query, when no one tapped
    ///   (`tapped` false): Reddit's check (#213) does this once its script has run, and blocking
    ///   it left the cook on its loading screen for good;
    /// - anything on the page's site while waiting on Cloudflare's check (`withinSite`, #220);
    /// - no link to any other page, so the clip always comes from the page it is saved under.
    static func loads(_ target: String, current: String, isRedirect: Bool, tapped: Bool, withinSite: Bool) -> Bool {
        guard let colon = target.firstIndex(of: ":"),
              webSchemes.contains(target[..<colon].lowercased())
        else { return false }
        if isRedirect { return true }
        guard let to = URLComponents(string: target), let from = URLComponents(string: current) else { return false }
        let sameHost = (to.host ?? "").caseInsensitiveCompare(from.host ?? "") == .orderedSame
        if withinSite && sameHost { return true }
        if !tapped && sameHost && to.scheme?.lowercased() == from.scheme?.lowercased()
            && to.percentEncodedPath == from.percentEncodedPath {
            return true
        }
        return withoutFragment(target) == withoutFragment(current)
    }

    private static let webSchemes: Set<String> = ["http", "https"]

    private static func withoutFragment(_ url: String) -> String {
        url.firstIndex(of: "#").map { String(url[..<$0]) } ?? url
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
