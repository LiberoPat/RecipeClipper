package com.example.recipeclipper.ui.clip

import android.annotation.SuppressLint
import android.net.Uri
import androidx.core.net.toUri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.recipeclipper.data.model.ClipField
import org.json.JSONObject

/** What the page reports, decoded from `clipper.js`'s messages. */
internal sealed class ClipPageEvent {
    data class Selection(val text: String) : ClipPageEvent()
    data class TagTapped(val field: ClipField) : ClipPageEvent()
    data class ImageTapped(val src: String) : ClipPageEvent()
}

/** How the page is loaded: the live URL, or a fixed local page in a UI test. */
typealias ClipPageLoader = (WebView, String) -> Unit

internal val LoadLiveUrl: ClipPageLoader = { webView, url -> webView.loadUrl(url) }

/**
 * The page being clipped, in a [WebView] with `clipper.js` injected once it has loaded. The
 * view layer's half of the bridge: it forwards the page's events and pushes [syncState] and
 * [pickingPhoto] into the page whenever they change. Links to other pages are blocked, so the
 * clip always comes from the page it is saved under.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun ClipWebPage(
    url: String,
    syncState: String,
    pickingPhoto: Boolean,
    onEvent: (ClipPageEvent) -> Unit,
    loadPage: ClipPageLoader,
    modifier: Modifier = Modifier
) {
    // Read by the WebViewClient when a page finishes loading, so the script it injects starts
    // from the current state rather than the state at creation.
    val latest = remember { LatestPageState() }
    latest.sync = syncState
    latest.picking = pickingPhoto
    latest.onEvent = onEvent

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                val main = Handler(Looper.getMainLooper())
                addJavascriptInterface(
                    object {
                        // Called on the JavaBridge thread; the ViewModel is fed on the main one.
                        @JavascriptInterface
                        fun post(json: String) {
                            val event = decode(json) ?: return
                            main.post { latest.onEvent(event) }
                        }
                    },
                    "RCAndroid"
                )
                webViewClient = ClipWebViewClient(url, latest)
                loadPage(this, url)
            }
        },
        // Captures the two values, so it is a new lambda, and runs again, when either changes.
        update = { webView ->
            if (latest.injected) webView.evaluateJavascript(pageScript(syncState, pickingPhoto), null)
        }
    )
}

private class LatestPageState {
    var sync: String = "{}"
    var picking: Boolean = false
    var onEvent: (ClipPageEvent) -> Unit = {}
    var injected: Boolean = false

    fun script() = pageScript(sync, picking)
}

private fun pageScript(sync: String, picking: Boolean) =
    "window.RC && (RC.sync($sync), RC.pickImage($picking));"

private class ClipWebViewClient(
    private val pageUrl: String,
    private val latest: LatestPageState
) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        latest.injected = false
    }

    override fun onPageFinished(view: WebView, url: String?) {
        view.evaluateJavascript(ClipperScript.source, null)
        latest.injected = true
        view.evaluateJavascript(latest.script(), null)
    }

    /** Redirects and fragment jumps load; a link to any other page does not. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame || request.isRedirect) return false
        return !samePage(request.url, (view.url ?: pageUrl).toUri())
    }

    private fun samePage(a: Uri, b: Uri): Boolean =
        a.buildUpon().fragment(null).build() == b.buildUpon().fragment(null).build()
}

/** `shared/web/clipper.js`, packaged as a Java resource alongside the shared tables. */
internal object ClipperScript {
    val source: String by lazy {
        ClipperScript::class.java.getResourceAsStream("/web/clipper.js")!!
            .bufferedReader().use { it.readText() }
    }
}

private fun decode(json: String): ClipPageEvent? = try {
    val message = JSONObject(json)
    when (message.optString("type")) {
        "selection" -> ClipPageEvent.Selection(message.optString("text"))
        "tag" -> ClipField.entries.firstOrNull { it.name == message.optString("field") }
            ?.let { ClipPageEvent.TagTapped(it) }
        "image" -> message.optString("src").takeIf { it.isNotEmpty() }?.let { ClipPageEvent.ImageTapped(it) }
        else -> null
    }
} catch (e: org.json.JSONException) {
    null
}
