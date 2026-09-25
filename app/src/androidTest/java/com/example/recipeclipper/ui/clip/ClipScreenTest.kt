package com.example.recipeclipper.ui.clip

import android.webkit.WebView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.recipeclipper.data.ClipDraftStore
import com.example.recipeclipper.fake.FakeRecipeRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Clip it yourself" end to end on a real WebView with the real `clipper.js`, over a fixed local
 * page (no network): select, assign, see the counts and marks, clear a field from its tag and
 * undo, pick the photo, review, save. Selections are made by script, as a finger would, and
 * reach the app through the page's `selectionchange`, the same path a person's do.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ClipScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val url = "https://hearthandcrumb.example/cookies"
    private val repository = FakeRecipeRepository()

    @Volatile private var webView: WebView? = null
    private val saved = mutableListOf<Long>()

    private fun show() {
        val viewModel = ClipViewModel(
            SavedStateHandle(mapOf(ClipViewModel.URL_ARG to url)),
            repository,
            ClipDraftStore()
        )
        compose.setContent {
            ClipScreen(
                onCancel = {},
                onSaved = { saved += it },
                viewModel = viewModel,
                loadPage = { view, pageUrl ->
                    webView = view
                    view.loadDataWithBaseURL(pageUrl, PAGE, "text/html", "utf-8", null)
                }
            )
        }
        compose.waitUntil(PAGE_WAIT_MS) { js("typeof window.RC") == "\"object\"" }
    }

    /** Runs [script] in the page and returns its result as JSON. */
    private fun js(script: String): String {
        val view = webView ?: return ""
        val latch = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        latch.await(2, TimeUnit.SECONDS) // a slow answer is "not yet": the caller asks again
        return result
    }

    private fun select(id: String) {
        js(
            "(function(){var r=document.createRange();r.selectNodeContents(document.getElementById('$id'));" +
                "var s=getSelection();s.removeAllRanges();s.addRange(r);})()"
        )
    }

    private fun waitForText(text: String) {
        compose.waitUntil(PAGE_WAIT_MS) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForPage(script: String, expected: String) {
        compose.waitUntil(PAGE_WAIT_MS) { js(script) == expected }
    }

    @Test
    fun clipAPageFromSelectionToSave() {
        show()
        compose.onNodeWithText("Done").assertIsNotEnabled()

        select("title")
        waitForText("1 line selected · each line becomes one item")
        compose.onNodeWithText("Name").performClick()
        waitForText("Name added")

        select("ingredients")
        waitForText("3 lines selected · each line becomes one item")
        compose.onNodeWithText("Ingredients 3").performClick()
        waitForText("3 ingredients added")

        // The page marks each assigned block and tags the field with its count.
        waitForPage("document.querySelectorAll('.rc-marked').length", "4")
        waitForPage("document.querySelector('.rc-tag[data-rc-field=INGREDIENTS]').textContent", "\"Ingredients · 3\"")

        // Tapping the tag clears the field; the snackbar's Undo brings it back.
        js("document.querySelector('.rc-tag[data-rc-field=INGREDIENTS]').click()")
        waitForText("Ingredients cleared")
        waitForPage("document.querySelectorAll('.rc-marked').length", "1")
        compose.onNodeWithText("Undo").performClick()
        waitForText("Name ✓ · 3 ingredients · 0 steps · no photo")
        waitForPage("document.querySelectorAll('.rc-marked').length", "4")

        // Assigning again replaces: two steps, then one.
        select("steps")
        waitForText("2 lines selected · each line becomes one item")
        compose.onNodeWithText("Steps 2").performClick()
        waitForText("2 steps added")
        select("step2")
        waitForText("1 line selected · each line becomes one item")
        compose.onNodeWithText("Steps 1").performClick()
        waitForText("1 step added")

        // The photo is the next image tapped after the Photo button.
        compose.onNodeWithText("Photo").performClick()
        waitForText("Tap the picture to use as the photo.")
        js("document.getElementById('photo').click()")
        waitForText("Photo added")
        waitForText("Name ✓ · 3 ingredients · 1 step · photo")

        compose.onNodeWithText("Done").assertIsEnabled()
        compose.onAllNodesWithText("Review")[0].performClick()
        waitForText("Ingredients · 3")
        compose.onNodeWithText("Brown Butter Oat Cookies").assertExists()
        compose.onNodeWithText("Serves").performTextReplacement("24 cookies")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Save recipe"))
        compose.onNodeWithText("Save recipe").performClick()

        compose.waitUntil(PAGE_WAIT_MS) { saved.isNotEmpty() }
        val recipe = repository.saveClipCalls.single()
        assertEquals("Brown Butter Oat Cookies", recipe.name)
        assertEquals(listOf("1 cup (226 g) unsalted butter", "1 cup packed brown sugar", "3 cups rolled oats"), recipe.ingredients)
        assertEquals(listOf("Bake at 350°F for 11 to 13 minutes."), recipe.instructions)
        assertEquals("https://hearthandcrumb.example/img/cookies.jpg", recipe.image)
        assertEquals("24 cookies", recipe.yield)
        assertEquals(url, recipe.sourceUrl)
    }

    @Test
    fun linksToOtherPagesDoNotLoad() {
        show()
        js("document.getElementById('elsewhere').click()")
        // A blocked navigation leaves the same document, with the script still in it.
        Thread.sleep(500)
        assertEquals("\"$url\"", js("location.href"))
        assertEquals("\"object\"", js("typeof window.RC"))
    }

    private companion object {
        /**
         * Every wait here crosses the WebView's renderer (a script's answer, the page's
         * 120 ms-debounced selection coming back over the bridge), and a busy emulator in a full
         * run can stall that for seconds. The old 5 s waits, with each script call allowed 5 s
         * of its own, could be used up by one slow answer: the flake in #91. One generous
         * budget per wait, and short calls inside it that are simply asked again.
         */
        const val PAGE_WAIT_MS = 15_000L

        const val PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
<style>body{font:16px sans-serif;margin:16px}</style></head><body>
<h1 id="title">Brown Butter Oat Cookies</h1>
<p>The first cold morning of the year always sends me straight to the oven.</p>
<img id="photo" src="/img/cookies.jpg" width="200" height="120" alt="">
<h2>Ingredients</h2>
<ul id="ingredients"><li>1 cup (226 g) unsalted butter</li><li>1 cup packed brown sugar</li><li>3 cups rolled oats</li></ul>
<h2>Method</h2>
<ol id="steps"><li id="step1">Brown the butter until it smells nutty.</li><li id="step2">Bake at 350°F for 11 to 13 minutes.</li></ol>
<p><a id="elsewhere" href="https://hearthandcrumb.example/other">Another recipe</a></p>
</body></html>"""
    }
}
