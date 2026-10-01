package com.example.recipeclipper.ui.clip

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.recipeclipper.data.WebViewRenderedPageSource
import com.example.recipeclipper.data.remote.RedditUrls

/** What the page reports, decoded from `clipper.js`'s messages. */
internal sealed class ClipPageEvent {
    data class Selection(val text: String) : ClipPageEvent()
    /** An add's tag on the page: [markId] names the add. */
    data class TagTapped(val markId: String) : ClipPageEvent()
    data class ImageTapped(val src: String) : ClipPageEvent()

    /** While picking a photo, the tap found no image with an address the app can read. */
    object NoImage : ClipPageEvent()

    /** The page as it stands, read while waiting on Cloudflare's check (#220). */
    data class PageLoaded(val html: String) : ClipPageEvent()

    /** The post and its loaded comments, read for the Text view (#213). */
    data class PageText(val html: String) : ClipPageEvent()
}

/** How the page is loaded: the live URL, or a fixed local page in a UI test. */
typealias ClipPageLoader = (WebView, String) -> Unit

internal val LoadLiveUrl: ClipPageLoader = { webView, url -> webView.loadUrl(url) }

/** The Text view (#213): [html] as a page of its own, with no address, so nothing on it loads. */
internal fun loadTextPage(html: String): ClipPageLoader = { webView, _ ->
    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}

/**
 * The page being clipped, in a [WebView] with `clipper.js` injected once it has loaded. The
 * view layer's half of the bridge: it forwards the page's events, heard from its main frame only
 * ([ClipBridge], #235), and pushes [syncState] and [pickingPhoto] into the page whenever they
 * change. Links to other pages are blocked, so the clip always comes from the page it is saved
 * under ([ClipNavigation]).
 *
 * With [readsPage] (waiting on Cloudflare's check, #220), the page's HTML is sent as
 * [ClipPageEvent.PageLoaded] once it settles after each load, and every [READ_EVERY_MS] after,
 * and the check may move the page within its own site (its form posts back to the page with a
 * token in the query).
 *
 * On reddit.com (#213) `reddit-reader.js` is injected too, which shows the post's whole text and
 * hides Reddit's sign-in and app prompts; [readText] turning true reads the post for the Text
 * view, as [ClipPageEvent.PageText].
 *
 * [visible] false keeps the page loaded, at full size, but not drawn and not touched (the Text
 * view behind the page, #237). Shrinking it to no size instead left its last frame over the
 * page, which then looked dead: Text, then Page, showed the text still, frozen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ClipWebPage(
    url: String,
    syncState: String,
    pickingPhoto: Boolean,
    onEvent: (ClipPageEvent) -> Unit,
    loadPage: ClipPageLoader,
    modifier: Modifier = Modifier,
    readsPage: Boolean = false,
    readText: Boolean = false,
    visible: Boolean = true
) {
    // Read by the WebViewClient when a page finishes loading, so the script it injects starts
    // from the current state rather than the state at creation.
    val latest = remember { LatestPageState() }
    latest.sync = syncState
    latest.picking = pickingPhoto
    latest.onEvent = onEvent
    latest.readsPage = readsPage

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                latest.bridgeToken = ClipBridge.attach(this) { latest.onEvent(it) }
                webViewClient = ClipWebViewClient(url, latest)
                loadPage(this, url)
            }
        },
        // Captures the two values, so it is a new lambda, and runs again, when either changes.
        update = { webView ->
            webView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            if (latest.injected) webView.evaluateJavascript(pageScript(syncState, pickingPhoto), null)
            if (readText && !latest.readingText) {
                latest.readingText = true
                webView.evaluateJavascript(READ_TEXT) { result ->
                    latest.onEvent(ClipPageEvent.PageText(WebViewRenderedPageSource.decodeJsString(result).orEmpty()))
                }
            }
            if (!readText) latest.readingText = false
        }
    )
}

private class LatestPageState {
    var sync: String = "{}"
    var picking: Boolean = false
    var onEvent: (ClipPageEvent) -> Unit = {}
    var injected: Boolean = false
    var readsPage: Boolean = false
    var readingText: Boolean = false

    /** The old JavaScript interface's token for the main frame ([ClipBridge]), or null. */
    var bridgeToken: String? = null

    fun script() = pageScript(sync, picking)
}

/** How often the page is read again while waiting on Cloudflare's check (#220), after it first
 *  settles: a check can pass without loading a new page. */
private const val READ_EVERY_MS = 2_000L

/** The quiet after a load before the page is read: the off-screen render's settle. */
private const val SETTLE_MS = 1_500L

/** The post and its comment tree for the Text view, from `reddit-reader.js`, else the page. */
private const val READ_TEXT = "window.RCReddit ? RCReddit.text() : document.documentElement.outerHTML"

private fun pageScript(sync: String, picking: Boolean) =
    "window.RC && (RC.sync($sync), RC.pickImage($picking));"

// androidx.webkit's check flags every `WebViewClient()` constructor call, and Kotlin's superclass
// call is one, though this class implements onRenderProcessGone (its class check passes).
@SuppressLint("MissingOnRenderProcessGone")
private class ClipWebViewClient(
    private val pageUrl: String,
    private val latest: LatestPageState
) : WebViewClient() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRead: Runnable? = null

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        latest.injected = false
        pendingRead?.let(handler::removeCallbacks)
    }

    /**
     * A crashed or reclaimed renderer leaves the page blank rather than taking the app down with
     * it (true: handled); the cook can still Cancel, or Review what was clipped. Nothing more is
     * sent to the dead page.
     */
    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
        latest.injected = false
        latest.readsPage = false
        pendingRead?.let(handler::removeCallbacks)
        return true
    }

    /** Reddit's prompts are hidden as soon as the page shows, and again once it has loaded. */
    override fun onPageCommitVisible(view: WebView, url: String?) = injectReader(view, url)

    private fun injectReader(view: WebView, url: String?) {
        if (RedditUrls.isReddit(url ?: pageUrl)) view.evaluateJavascript(ClipperScript.redditReader, null)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        injectReader(view, url)
        latest.bridgeToken?.let { view.evaluateJavascript(ClipBridge.tokenScript(it), null) }
        view.evaluateJavascript(ClipperScript.source, null)
        latest.injected = true
        view.evaluateJavascript(latest.script(), null)
        if (latest.readsPage) readLater(view, SETTLE_MS)
    }

    /** While waiting on Cloudflare's check (#220): the page's HTML, now and then again. */
    private fun readLater(view: WebView, delayMs: Long) {
        pendingRead?.let(handler::removeCallbacks)
        pendingRead = Runnable {
            if (!latest.readsPage) return@Runnable
            view.evaluateJavascript("document.documentElement.outerHTML") { result ->
                WebViewRenderedPageSource.decodeJsString(result)?.let { latest.onEvent(ClipPageEvent.PageLoaded(it)) }
            }
            readLater(view, READ_EVERY_MS)
        }.also { handler.postDelayed(it, delayMs) }
    }

    /** [ClipNavigation.loads] decides; `true` here means the navigation is dropped. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        return !ClipNavigation.loads(
            target = request.url.toString(),
            current = view.url ?: pageUrl,
            isRedirect = request.isRedirect,
            tapped = request.hasGesture(),
            withinSite = latest.readsPage
        )
    }
}

/**
 * Which navigations of the page's own frame the clip view follows. Pure, so it is tested on the
 * JVM; iOS's `ClipNavigation` is the same rule.
 */
internal object ClipNavigation {

    /**
     * Whether a navigation to [target] from [current] loads:
     * - never an address the web view can't show (`intent:`, `reddit:`, `market:`): an app's own
     *   link, which would leave an error page, or nothing, in the post's place;
     * - redirects, and fragment jumps, always;
     * - the page sending itself back to its own address with a new query, when no one tapped
     *   ([tapped] false): Reddit's check (#213) does this once its script has run, and blocking
     *   it left the cook on its loading screen for good;
     * - anything on the page's site while waiting on Cloudflare's check ([withinSite], #220);
     * - no link to any other page, so the clip always comes from the page it is saved under.
     */
    fun loads(target: String, current: String, isRedirect: Boolean, tapped: Boolean, withinSite: Boolean): Boolean {
        if (target.substringBefore(':', "").lowercase() !in WEB_SCHEMES) return false
        if (isRedirect) return true
        val to = parse(target) ?: return false
        val from = parse(current) ?: return false
        val sameHost = to.host.equals(from.host, ignoreCase = true)
        if (withinSite && sameHost) return true
        if (!tapped && sameHost && to.scheme.equals(from.scheme, ignoreCase = true) && to.rawPath == from.rawPath) return true
        return withoutFragment(to) == withoutFragment(from)
    }

    private val WEB_SCHEMES = setOf("http", "https")

    private fun parse(url: String): java.net.URI? = try {
        java.net.URI(url)
    } catch (e: java.net.URISyntaxException) {
        null
    }

    private fun withoutFragment(uri: java.net.URI): String = uri.toString().substringBefore('#')
}

/** `shared/web/clipper.js`, packaged as a Java resource alongside the shared tables. */
internal object ClipperScript {
    val source: String by lazy { read("clipper.js") }

    /** `shared/web/reddit-reader.js` (#213): Reddit's page as a reader, and its text. */
    val redditReader: String by lazy { read("reddit-reader.js") }

    private fun read(name: String) =
        ClipperScript::class.java.getResourceAsStream("/web/$name")!!.bufferedReader().use { it.readText() }
}
