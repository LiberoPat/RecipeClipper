import Foundation
import WebKit

/// RenderedPageSource over an off-screen WKWebView: never in a window, never shown (Android's
/// WebViewRenderedPageSource). Beside PathConnectivity because, like it, it is a platform
/// service kept behind a protocol, so the repository never imports WebKit's types.
///
/// Each call builds a fresh web view on the main actor, loads the page with JavaScript on,
/// waits `settle` after the last `didFinish` (a bot check or a script that navigates starts
/// the wait again), then reads `document.documentElement.outerHTML`. The web view is released
/// on every way out: success, failure or cancellation. The overall cap is the repository's,
/// which cancels this when it runs out.
///
/// The user agent is WebKit's own: this is a real browser engine, not a user-agent trick (see
/// CLAUDE.md, "Failure handling"). Its cookies are the app's, not Safari's, so a paywall still
/// fails. Not used inside the share extension (memory, #19).
@MainActor
final class WebViewRenderedPageSource: RenderedPageSource {
    /// How long a page must stay quiet after loading before its HTML is taken: time for scripts
    /// that add the recipe data, or finish a bot check, after the load event. Android's SETTLE_MS.
    static let settle: Duration = .milliseconds(1500)

    func render(url: String) async -> String? {
        // http(s) only, as the direct fetch enforces.
        guard let target = URL(string: url),
              let scheme = target.scheme?.lowercased(), scheme == "http" || scheme == "https"
        else { return nil }
        let loader = PageLoader()
        return await withTaskCancellationHandler {
            await loader.load(target)
        } onCancel: {
            Task { @MainActor in loader.cancel() }
        }
    }
}

/// One page load: owns the web view and the continuation, and resumes it exactly once.
@MainActor
private final class PageLoader: NSObject, WKNavigationDelegate {
    private var webView: WKWebView?
    private var continuation: CheckedContinuation<String?, Never>?
    private var pendingCapture: Task<Void, Never>?
    private var cancelled = false

    func load(_ url: URL) async -> String? {
        await withCheckedContinuation { continuation in
            guard !cancelled else { return continuation.resume(returning: nil) }
            self.continuation = continuation
            let configuration = WKWebViewConfiguration()
            configuration.mediaTypesRequiringUserActionForPlayback = .all
            // A phone-sized frame, so layout-dependent scripts see a phone viewport.
            let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: configuration)
            webView.navigationDelegate = self
            self.webView = webView
            webView.load(URLRequest(url: url))
        }
    }

    func cancel() {
        cancelled = true
        finish(nil)
    }

    private func finish(_ html: String?) {
        pendingCapture?.cancel()
        pendingCapture = nil
        webView?.stopLoading()
        webView?.navigationDelegate = nil
        webView = nil
        continuation?.resume(returning: html)
        continuation = nil
    }

    private func capture() {
        guard let webView else { return }
        webView.evaluateJavaScript("document.documentElement.outerHTML") { [weak self] result, _ in
            // WebKit calls this on the main thread.
            MainActor.assumeIsolated { self?.finish(result as? String) }
        }
    }

    // MARK: WKNavigationDelegate

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        // A new navigation (a redirect, or a bot check passing): wait for it instead.
        pendingCapture?.cancel()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        pendingCapture?.cancel()
        pendingCapture = Task { [weak self] in
            try? await Task.sleep(for: WebViewRenderedPageSource.settle)
            guard !Task.isCancelled else { return }
            self?.capture()
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        failed(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        failed(error)
    }

    private func failed(_ error: Error) {
        // A navigation cut short by the next one (a script redirect) isn't a failure: the next
        // one's didFinish takes over.
        if (error as NSError).domain == NSURLErrorDomain, (error as NSError).code == NSURLErrorCancelled { return }
        finish(nil)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        finish(nil)
    }
}
