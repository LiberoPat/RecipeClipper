package com.example.recipeclipper.ui.clip

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.recipeclipper.data.model.ClipField
import org.json.JSONObject
import java.util.UUID

/**
 * How `clipper.js`'s messages reach the app (#235): from the page's main frame only. Ads and
 * other sites' iframes inside the page could otherwise post a selection, a tag or a photo into
 * the clip. The script posts with `RCBridge.postMessage(json)` either way.
 *
 * - **A message listener** ([WebViewCompat.addWebMessageListener]), on any WebView that has one
 *   (`WEB_MESSAGE_LISTENER`): `RCBridge` is injected with the origin rule `*`, since the page
 *   is any site, and a message from a frame that isn't the main one is dropped.
 * - **Otherwise, the old JavaScript interface**, which every frame of every origin sees and which
 *   can't tell which one called. The app gives the main frame a random token (by
 *   `evaluateJavascript`, which runs there only; [tokenScript]) that `clipper.js` adds to each
 *   message, and drops a message without it. Another site's frame can't read the main frame's
 *   token. A frame of the page's own site could, as it could reach the page's script anyway.
 */
internal object ClipBridge {

    /** The object `clipper.js` posts through. */
    const val NAME = "RCBridge"

    /**
     * Gives [webView] the bridge, before anything loads, feeding [onEvent] on the main thread.
     * Returns the token the main frame must be handed ([tokenScript]) when it is the old
     * interface, or null with a listener.
     */
    @SuppressLint("JavascriptInterface")
    fun attach(webView: WebView, onEvent: (ClipPageEvent) -> Unit): String? {
        if (listenerSupported()) {
            // Called on the main thread. Only a string is read: the data of any other kind of
            // message (an ArrayBuffer) can't be asked for as one.
            WebViewCompat.addWebMessageListener(webView, NAME, setOf("*")) { _, message, _, isMainFrame, _ ->
                val json = if (message.type == WebMessageCompat.TYPE_STRING) message.data else null
                fromListener(json, isMainFrame)?.let(onEvent)
            }
            return null
        }
        val token = UUID.randomUUID().toString()
        val main = Handler(Looper.getMainLooper())
        webView.addJavascriptInterface(
            object {
                // Called on the JavaBridge thread; the ViewModel is fed on the main one.
                @JavascriptInterface
                fun postMessage(json: String) {
                    val event = fromInterface(json, token) ?: return
                    main.post { onEvent(event) }
                }
            },
            NAME
        )
        return token
    }

    /** Hands the main frame the old interface's [token], before `clipper.js` runs. */
    fun tokenScript(token: String) = "window.RCBridgeToken = ${JSONObject.quote(token)};"

    private fun listenerSupported(): Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    } catch (e: RuntimeException) {
        false // no WebView provider to ask (Robolectric has none): the old interface
    }

    /** A message heard by the listener: read only when it came from the main frame. */
    fun fromListener(json: String?, isMainFrame: Boolean): ClipPageEvent? =
        if (isMainFrame && json != null) parse(json)?.let(::decode) else null

    /** A message through the old interface: read only when it carries the main frame's [token]. */
    fun fromInterface(json: String, token: String): ClipPageEvent? {
        val message = parse(json) ?: return null
        return if (message.optString("token") == token) decode(message) else null
    }

    private fun parse(json: String): JSONObject? = try {
        JSONObject(json)
    } catch (e: org.json.JSONException) {
        null
    }

    private fun decode(message: JSONObject): ClipPageEvent? = when (message.optString("type")) {
        "selection" -> ClipPageEvent.Selection(message.optString("text"))
        "tag" -> ClipField.entries.firstOrNull { it.name == message.optString("field") }
            ?.let { ClipPageEvent.TagTapped(it) }
        // Whether it's a picture the app can use is the ViewModel's call (WebImageUrl).
        "image" -> ClipPageEvent.ImageTapped(message.optString("src"))
        "noImage" -> ClipPageEvent.NoImage
        else -> null
    }
}
