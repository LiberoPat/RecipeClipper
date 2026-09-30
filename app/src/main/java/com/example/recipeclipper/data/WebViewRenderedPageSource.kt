package com.example.recipeclipper.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.recipeclipper.data.remote.CloudflareChallenge
import com.example.recipeclipper.data.remote.RenderedPageSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * [RenderedPageSource] over an off-screen [WebView]: never attached to a window, never shown.
 * Beside [AndroidConnectivity] because, like it, it is a `Context` thing kept behind an
 * interface so the repository never sees one.
 *
 * Each call builds a fresh WebView on the main thread (WebView demands it), loads the page with
 * JavaScript on, waits [SETTLE_MS] after the last `onPageFinished` (a bot check or a script
 * that navigates starts the wait again), then reads `document.documentElement.outerHTML`. The
 * WebView is destroyed on every way out: success, failure or cancellation. The overall cap is
 * the repository's, so a page that never settles is cut off there and cleaned up here.
 *
 * A settled page that is Cloudflare's challenge (#220) isn't taken: the load keeps waiting for
 * the page the check moves on to, and says so through `onChallenge`. The WebView's cookies are
 * the app's one persistent store, so the `cf_clearance` a passed check leaves is kept for the
 * clip view and the next render.
 *
 * The WebView's user agent is left as the system's own: this is a real browser engine, not a
 * user-agent trick (see CLAUDE.md, "Failure handling"). Its cookies are the app's, not the
 * user's browser's, so a paywall still fails.
 */
@Singleton
class WebViewRenderedPageSource @Inject constructor(
    @ApplicationContext private val context: Context
) : RenderedPageSource {

    override suspend fun render(url: String, onChallenge: () -> Unit): String? = withContext(Dispatchers.Main) {
        val webView = try {
            WebView(context)
        } catch (e: Exception) {
            // No WebView provider (being updated, or disabled): nothing to render with.
            return@withContext null
        }
        try {
            load(webView, url, onChallenge)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            // On the main thread still, as destroy() requires, whichever way we left.
            webView.stopLoading()
            webView.destroy()
            // Cloudflare's clearance cookie (#220), and any other the page set, on disk now:
            // the clip view and the next render share this cookie store.
            CookieManager.getInstance().flush()
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // the point: pages whose recipe data needs scripts
    private suspend fun load(webView: WebView, url: String, onChallenge: () -> Unit): String? =
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var pendingCapture: Runnable? = null

            fun finish(html: String?) {
                pendingCapture?.let(handler::removeCallbacks)
                if (continuation.isActive) continuation.resume(html)
            }

            fun capture() {
                webView.evaluateJavascript("document.documentElement.outerHTML") { result ->
                    val html = decodeJsString(result)
                    if (html != null && CloudflareChallenge.isChallengePage(html)) {
                        // Cloudflare's check (#220): not the page yet. It usually loads the page
                        // itself once it passes (a navigation, which restarts the wait); look
                        // again after another settle in case it changes the page in place.
                        onChallenge()
                        pendingCapture = Runnable { capture() }.also { handler.postDelayed(it, SETTLE_MS) }
                    } else {
                        finish(html)
                    }
                }
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true // many sites' scripts fail without localStorage
                blockNetworkImage = true // photos aren't needed to read the recipe data
            }
            // Laid out at the screen's size, so layout-dependent scripts see a phone viewport.
            val metrics = context.resources.displayMetrics
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, metrics.widthPixels, metrics.heightPixels)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    // A new navigation (a redirect, or a bot check passing): wait for it instead.
                    pendingCapture?.let(handler::removeCallbacks)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    pendingCapture?.let(handler::removeCallbacks)
                    pendingCapture = Runnable { capture() }.also { handler.postDelayed(it, SETTLE_MS) }
                }

                // No onReceivedError: a page that fails to load still ends in onPageFinished, on
                // the WebView's error page, which parses as no recipe and so leaves the direct
                // fetch's cause standing. Finishing early on an error would also end the load
                // when a script redirect aborts the first navigation.

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // Returning true keeps a crashed renderer from taking the app down with it.
                    finish(null)
                    return true
                }
            }
            continuation.invokeOnCancellation { pendingCapture?.let(handler::removeCallbacks) }
            webView.loadUrl(url)
        }

    companion object {
        /** How long a page must stay quiet after loading before its HTML is taken: time for
         *  scripts that add the recipe data, or finish a bot check, after the load event. */
        const val SETTLE_MS = 1_500L

        /** `evaluateJavascript` hands back its result JSON-encoded: a quoted string, or `null`. */
        internal fun decodeJsString(json: String?): String? {
            if (json == null) return null
            return try {
                JSONTokener(json).nextValue() as? String
            } catch (e: Exception) {
                null
            }
        }
    }
}
