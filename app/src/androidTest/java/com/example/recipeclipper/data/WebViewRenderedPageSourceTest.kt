package com.example.recipeclipper.data

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.remote.BlogRecipeSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rendered fallback against a real WebView on a device: a JVM test can't run one. The page
 * is a `data:` URL, so no network or server is involved, and its recipe JSON-LD exists only
 * after its script runs, added a beat after the load event, which is the case the fallback is
 * for (and why it waits [WebViewRenderedPageSource.SETTLE_MS] before reading the HTML).
 */
@RunWith(AndroidJUnit4::class)
class WebViewRenderedPageSourceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = WebViewRenderedPageSource(context)

    private val recipeJson = """
        {"@context":"https://schema.org","@type":"Recipe","name":"Rendered Soup",
         "recipeIngredient":["2 cups stock","1 onion"],
         "recipeInstructions":["Simmer.","Serve."]}
    """.trimIndent().replace("\n", "")

    private val page = """
        <!doctype html>
        <html><head><title>JS page</title></head>
        <body><p>Loading…</p>
        <script>
          window.addEventListener('load', function () {
            setTimeout(function () {
              var s = document.createElement('script');
              s.type = 'application/ld+json';
              s.text = JSON.stringify($recipeJson);
              document.head.appendChild(s);
            }, 300);
          });
        </script>
        </body></html>
    """.trimIndent()

    private fun dataUrl(html: String) =
        "data:text/html;charset=utf-8;base64," +
            Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)

    @Test
    fun rendersRecipeJsonLdAddedByScriptAfterLoad() = runBlocking {
        // Without JavaScript the page has no recipe data at all.
        assertTrue(BlogRecipeSource.parse(page, "https://example.com/soup") is ParseResult.Error)

        val html = withTimeout(20_000) { source.render(dataUrl(page)) }

        assertNotNull("the WebView returned no HTML", html)
        assertTrue(html!!.contains("<script type=\"application/ld+json\">"))
        assertTrue(html.contains("Rendered Soup"))

        val parsed = BlogRecipeSource.parse(html, "https://example.com/soup")
        assertTrue("rendered HTML should parse: $parsed", parsed is ParseResult.Success)
        val recipe = (parsed as ParseResult.Success).recipe
        assertEquals("Rendered Soup", recipe.name)
        assertEquals(listOf("2 cups stock", "1 onion"), recipe.ingredients)
    }

    /**
     * Cloudflare's check (#220), imitated: a "Just a moment..." page carrying the check's
     * settings, which after 4 s (past two settles) turns into the recipe page, as a check that
     * passes by itself does. The render mustn't hand back the check, must say it saw one, and
     * must read the page it becomes. (A `data:` page can't navigate to another, so the page
     * changes in place; a navigation restarts the wait the same way.)
     */
    @Test
    fun waitsOutACloudflareCheckAndReadsThePageAfterIt() = runBlocking {
        val check = """
            <!doctype html>
            <html><head><title>Just a moment...</title>
            <script>
              window._cf_chl_opt = {cType: 'managed', cRay: '8c1f0e2d4b5a6f70'};
              setTimeout(function () {
                document.title = 'Soup';
                document.head.innerHTML = '<script type="application/ld+json">' +
                  JSON.stringify($recipeJson) + '<\/script>';
                document.body.innerHTML = '<p>Soup</p>';
              }, 4000);
            </script></head>
            <body><p>Verifying you are human. This may take a few seconds.</p></body></html>
        """.trimIndent()
        var challenged = false

        val html = withTimeout(30_000) { source.render(dataUrl(check)) { challenged = true } }

        assertTrue("the check was never reported", challenged)
        assertNotNull("the WebView returned no HTML", html)
        val parsed = BlogRecipeSource.parse(html!!, "https://example.com/soup")
        assertTrue("the page after the check should parse: $parsed", parsed is ParseResult.Success)
        assertEquals("Rendered Soup", (parsed as ParseResult.Success).recipe.name)
    }
}
