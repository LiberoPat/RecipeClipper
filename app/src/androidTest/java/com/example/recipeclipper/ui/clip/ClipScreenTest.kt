package com.example.recipeclipper.ui.clip

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
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
 * page (no network), guided (#251, field first since #237): the clip opens on step 1 with Name
 * armed; tap its text on the page, confirm, and the next step arms by itself. A tap
 * on the page is a script's `click()` on the element, which reaches the page's own listener as
 * a finger's tap does; the page's selection comes back through `selectionchange`, the same path
 * a person's takes. Then the page's own handles, the photo, a tag taking back one add, Review
 * and Save, and the owner's Text → Page bug on a Reddit-like page.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ClipScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val url = "https://hearthandcrumb.example/cookies"
    private val repository = FakeRecipeRepository()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Volatile private var webView: WebView? = null
    private val saved = mutableListOf<Long>()

    private fun show(pageUrl: String = url, page: String = PAGE) {
        val viewModel = ClipViewModel(
            SavedStateHandle(mapOf(ClipViewModel.URL_ARG to pageUrl)),
            repository,
            ClipDraftStore()
        )
        compose.setContent {
            ClipScreen(
                onCancel = {},
                onSaved = { saved += it },
                viewModel = viewModel,
                loadPage = { view, address ->
                    webView = view
                    view.loadDataWithBaseURL(address, page, "text/html", "utf-8", null)
                }
            )
        }
        compose.waitUntil(PAGE_WAIT_MS) { js("typeof window.RC") == "\"object\"" }
    }

    /** Runs [script] in [view] (the page by default) and returns its result as JSON. */
    private fun js(script: String, view: WebView? = webView): String {
        view ?: return ""
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            view.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        latch.await(2, TimeUnit.SECONDS) // a slow answer is "not yet": the caller asks again
        return result
    }

    /** A tap on the page's element matching [selector]. */
    private fun tapOnPage(selector: String, view: WebView? = webView) {
        compose.waitUntil(PAGE_WAIT_MS) {
            js("(function(){var e=document.querySelector(${quote(selector)});if(!e)return false;e.click();return true})()", view) == "true"
        }
    }

    /** A field button; for a text field, waits until [view] has heard it is armed. */
    private fun tapField(field: String, view: WebView? = webView) {
        compose.onNodeWithTag("clip.field.$field").performClick()
        if (field != "PHOTO") waitForPage("document.documentElement.getAttribute('data-rc-armed')", "\"$field\"", view)
    }

    /** Text selected first, then [field]'s button: it adds the selection straight away (#251). */
    private fun addSelectionTo(field: String) {
        compose.waitUntil(PAGE_WAIT_MS) { compose.onAllNodes(hasTestTag("clip.confirm")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("clip.field.$field").performClick()
    }

    /** The hint bar's confirm, once the page's selection has reached the app. */
    private fun confirm() {
        compose.waitUntil(PAGE_WAIT_MS) { compose.onAllNodes(hasTestTag("clip.confirm")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("clip.confirm").performClick()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(PAGE_WAIT_MS) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForPage(script: String, expected: String, view: WebView? = webView) {
        compose.waitUntil(PAGE_WAIT_MS) { js(script, view) == expected }
    }

    private fun quote(text: String) = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

    @Test
    fun clipAPageGuidedFromTapsToSave() {
        show()
        // Guided on entry: step 1, Name armed, the page's text outlined.
        waitForText("Step 1 of 4 · Tap the recipe's name")
        waitForPage("document.documentElement.getAttribute('data-rc-armed')", "\"NAME\"")
        waitForPage("document.documentElement.getAttribute('data-rc-outline')", "\"TEXT\"")
        waitForPage("getComputedStyle(document.getElementById('title')).outlineStyle", "\"dotted\"")
        // Done with nothing yet says what's missing rather than doing nothing (#213).
        compose.onNodeWithText("Done").performClick()
        waitForText("To save, tap Name, then Ingredients or Steps, and pick each one on the page.")

        tapOnPage("#title")
        waitForText("Use as the name")
        confirm()
        waitForText("Step 2 of 4 · Tap the ingredients")
        waitForText("Name added.")
        waitForPage("document.documentElement.getAttribute('data-rc-armed')", "\"INGREDIENTS\"")

        // A tap on one item takes the whole list.
        tapOnPage("#ingredients li:nth-child(2)")
        waitForText("Add 3 lines to Ingredients")
        confirm()
        waitForText("Step 3 of 4 · Tap the steps")
        waitForPage("document.querySelectorAll('.rc-marked').length", "4")
        waitForPage("document.querySelector('.rc-tag[data-rc-field=INGREDIENTS]').textContent", "\"Ingredients · 3\"")

        // A tag takes back its own add; the snackbar's Undo brings it back.
        js("document.querySelector('.rc-tag[data-rc-field=INGREDIENTS]').click()")
        waitForText("3 ingredients removed")
        waitForPage("document.querySelectorAll('.rc-marked').length", "1")
        compose.onNodeWithText("Undo").performClick()
        waitForPage("document.querySelectorAll('.rc-marked').length", "4")

        tapOnPage("#step1")
        waitForText("Add 2 lines to Steps")
        confirm()
        // The photo step: the page's images are outlined.
        waitForText("Step 4 of 4 · Tap the recipe's photo")
        waitForPage("document.documentElement.getAttribute('data-rc-outline')", "\"IMAGES\"")

        // Steps add up: Steps' button jumps back to the step; the paragraph after the list,
        // selected first, goes in with one tap on the field, no confirm.
        tapField("STEPS")
        waitForText("Step 3 of 4 · Tap the steps")
        tapOnPage("#cool")
        addSelectionTo("STEPS")
        waitForText("1 step added.")
        waitForText("Step 4 of 4 · Tap the recipe's photo")

        // One tap on the picture.
        js("document.getElementById('photo').click()")
        waitForText("Photo added. Tap Done to review and save.")

        compose.onNodeWithText("Done").assertIsEnabled().performClick()
        waitForText("Ingredients · 3")
        compose.onNodeWithText("Brown Butter Oat Cookies").assertExists()
        compose.onNodeWithText("Serves").performTextReplacement("24 cookies")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Save recipe"))
        compose.onNodeWithText("Save recipe").performClick()

        compose.waitUntil(PAGE_WAIT_MS) { saved.isNotEmpty() }
        val recipe = repository.saveClipCalls.single()
        assertEquals("Brown Butter Oat Cookies", recipe.name)
        assertEquals(listOf("1 cup (226 g) unsalted butter", "1 cup packed brown sugar", "3 cups rolled oats"), recipe.ingredients)
        assertEquals(
            listOf("Brown the butter until it smells nutty.", "Bake at 350°F for 11 to 13 minutes.", "Cool on the tray."),
            recipe.instructions
        )
        assertEquals("https://hearthandcrumb.example/img/cookies.jpg", recipe.image)
        assertEquals("24 cookies", recipe.yield)
        assertEquals(url, recipe.sourceUrl)
    }

    /**
     * #264: a recipe list still comes whole from a tap on one item, but a paragraph inside one
     * comment of a comment thread (a list too) is its own block, not the thread. And an add
     * after an Undo is marked where it was taken from, never on the undone block (its mark id
     * isn't handed out again).
     */
    @Test
    fun aCommentIsItsOwnBlockAndAnUndoneAddsMarkIsNotReused() {
        show(page = COMMENTS_PAGE)
        tapField("INGREDIENTS")
        tapOnPage("#ingredients li:nth-child(2)")
        waitForText("Add 2 lines to Ingredients")
        confirm()
        waitForPage("document.querySelectorAll('#ingredients .rc-marked').length", "2")
        compose.onNodeWithText("Undo").performClick()
        waitForPage("document.querySelectorAll('.rc-marked').length", "0")

        waitForPage("document.documentElement.getAttribute('data-rc-armed')", "\"INGREDIENTS\"")
        tapOnPage("#c1 .comment-content p")
        waitForText("Add 1 line to Ingredients")
        confirm()
        waitForPage("document.querySelector('#c1 .comment-content p').classList.contains('rc-marked')", "true")
        assertEquals("0", js("document.querySelectorAll('#ingredients .rc-marked').length"))
        assertEquals("1", js("document.querySelectorAll('.rc-tag').length"))
    }

    /**
     * The page's own handles on a selection it made: dragging the end one up to the second item
     * leaves the first two. A second tap below the selection stretches it down instead.
     */
    @Test
    fun theHandlesAndASecondTapAdjustTheSelection() {
        show()
        tapField("INGREDIENTS")
        tapOnPage("#ingredients li")
        waitForText("Add 3 lines to Ingredients")
        waitForPage("getComputedStyle(document.getElementById('rc-handles')).display", "\"block\"")

        // The end handle, dragged by script to just under the second item (the knob sits under
        // the finger's point, so the block above it is the one taken).
        js(
            "(function(){var h=document.querySelector('.rc-handle-end');" +
                "var li=document.querySelectorAll('#ingredients li')[1].getBoundingClientRect();" +
                "var x=li.left+10, y=li.top+li.height/2+30;" +
                "h.dispatchEvent(new PointerEvent('pointerdown',{pointerId:7,bubbles:true,clientX:x,clientY:y}));" +
                "h.dispatchEvent(new PointerEvent('pointermove',{pointerId:7,bubbles:true,clientX:x,clientY:y}));" +
                "h.dispatchEvent(new PointerEvent('pointerup',{pointerId:7,bubbles:true,clientX:x,clientY:y}));})()"
        )
        waitForText("Add 2 lines to Ingredients")

        // Clear, then a heading and the list under it in two taps.
        compose.onNodeWithText("Clear").performClick()
        waitForPage("String(getSelection())", "\"\"")
        tapOnPage("#method")
        waitForText("Add 1 line to Ingredients")
        tapOnPage("#step2")
        waitForText("Add 3 lines to Ingredients")
    }

    /**
     * The owner's "stuck in the photo section": a tap while picking that found no readable
     * picture (here a lazy-loading placeholder with no real address) ends the step and says so,
     * the page answers again, and Skip leaves the step without touching the page; either way
     * the next field takes its tap. iOS's ClipUITests taps a heading instead.
     */
    @Test
    fun aPictureThatCantBeReadLeavesThePhotoStepForTheOtherFields() {
        show()
        val picking = "document.documentElement.classList.contains('rc-picking')"

        tapField("PHOTO")
        waitForText("Step 4 of 4 · Tap the recipe's photo")
        waitForPage(picking, "true")
        js("document.getElementById('placeholder').click()")
        waitForText("Couldn't read a picture there. The photo is optional.")
        waitForPage(picking, "false")

        tapField("NAME")
        tapOnPage("#title")
        confirm()
        waitForText("Step 2 of 4 · Tap the ingredients")

        // Skip moves on without touching the page; Skip on Photo ends the steps.
        compose.onNodeWithText("Skip").performClick()
        waitForText("Step 3 of 4 · Tap the steps")
        tapField("PHOTO")
        waitForPage(picking, "true")
        compose.onNodeWithText("Skip").performClick()
        waitForPage(picking, "false")
        compose.onNodeWithText("Skip").assertDoesNotExist()

        tapField("INGREDIENTS")
        tapOnPage("#ingredients li")
        confirm()
        waitForText("Step 3 of 4 · Tap the steps")
    }

    /**
     * #235: a frame inside the page (an ad, another site's widget) has the bridge's object too,
     * but the app hears the main frame only, so a selection posted from the frame never shows.
     * iOS's ClipUITests taps the same frame's button.
     */
    @Test
    fun aFrameInsideThePageCantPostIntoTheClip() {
        show()
        // The frame can reach the bridge, so what follows is the app ignoring it.
        waitForPage("typeof document.getElementById('ad').contentWindow.RCBridge", "\"object\"")
        js("document.getElementById('ad').contentWindow.document.getElementById('post').click()")
        Thread.sleep(1_500)
        compose.onNodeWithText("1 line selected", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Step 1 of 4 · Tap the recipe's name").assertExists()

        // The page itself is still heard.
        tapOnPage("#title")
        waitForText("Use as the name")
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

    /** The armed field's taps don't follow a link either: they select. */
    @Test
    fun anArmedTapOnALinkSelectsIt() {
        show()
        tapField("STEPS")
        tapOnPage("#elsewhere")
        waitForText("Add 1 line to Steps")
        assertEquals("\"$url\"", js("location.href"))
    }

    /**
     * The owner (#237): "when toggling a reddit page from text back to page, page doesnt work".
     * The Text view, hidden by shrinking it to no size, left its last frame on the screen over
     * the page: the page answered underneath, but what showed was the Text view, frozen. Here the
     * page is green and the Text view isn't, so back on the page the screen must show green.
     * Then a real tap (the shell's input, through the view hierarchy) on the page's list selects
     * it.
     */
    @Test
    fun textThenPageLeavesThePageWorking() {
        show(REDDIT_URL, REDDIT_PAGE)
        waitForPage("typeof window.RCReddit", "\"object\"")

        compose.onNodeWithText("Text").performClick()
        compose.waitUntil(PAGE_WAIT_MS) { textView() != null }
        val text = textView()!!
        waitForPage("typeof window.RC == 'object' && document.body.innerText.indexOf('1 crust') >= 0", "true", text)

        // In the Text view: the first ingredient, then the last, stretch to both.
        tapField("INGREDIENTS", text)
        tapOnPage("p:nth-of-type(1)", text)
        waitForText("Add 1 line to Ingredients")
        js("Array.from(document.querySelectorAll('p')).find(function(p){return p.textContent=='1 crust'}).click()", text)
        waitForText("Add 2 lines to Ingredients")
        confirm()
        waitForText("2 ingredients added.")
        waitForText("Step 3 of 4 · Tap the steps")

        compose.onNodeWithText("Page").performClick()
        waitForText("Text")
        instrumentation.runOnMainSync { assertEquals(View.INVISIBLE, text.visibility) }
        waitForPage("document.documentElement.getAttribute('data-rc-armed')", "\"STEPS\"")
        // What the screen shows in the middle of the page is the page (its green), not the
        // Text view's last frame.
        val page = webView!!
        val middle = IntArray(2).also { at -> instrumentation.runOnMainSync { page.getLocationOnScreen(at) } }
        var height = 0
        instrumentation.runOnMainSync { height = page.height }
        val probeX = middle[0] + 4
        val probeY = middle[1] + height / 2
        compose.waitUntil(PAGE_WAIT_MS) {
            val shot = instrumentation.uiAutomation.takeScreenshot()
            val pixel = shot?.getPixel(probeX, probeY)
            shot?.recycle()
            pixel != null && android.graphics.Color.green(pixel) > 150 &&
                android.graphics.Color.red(pixel) < 80 && android.graphics.Color.blue(pixel) < 80
        }

        // A finger's tap on the page's list (Steps armed, carried over), then Ingredients' button
        // adds it there (selected first, #251).
        val (x, y) = screenPoint("#more li")
        instrumentation.uiAutomation.executeShellCommand("input tap $x $y").close()
        waitForText("Add 2 lines to Steps")
        addSelectionTo("INGREDIENTS")
        waitForText("2 ingredients added.")
        waitForPage("document.querySelectorAll('.rc-marked').length", "2")
    }

    /** The Text view's web view: the one that isn't the page. */
    private fun textView(): WebView? {
        var found: WebView? = null
        instrumentation.runOnMainSync {
            fun walk(view: View) {
                if (view is WebView && view !== webView) found = view
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            webView?.rootView?.let(::walk)
        }
        return found
    }

    /** Where on the screen the page's element matching [selector] is (its middle). */
    private fun screenPoint(selector: String): Pair<Int, Int> {
        val view = webView!!
        var rect = ""
        compose.waitUntil(PAGE_WAIT_MS) {
            rect = js(
                "(function(){var r=document.querySelector(${quote(selector)}).getBoundingClientRect();" +
                    "return [r.left+r.width/2, r.top+r.height/2, devicePixelRatio].join(',')})()"
            )
            rect.length > 2
        }
        val (cx, cy, ratio) = rect.trim('"').split(',').map { it.toFloat() }
        val at = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(at) }
        return Pair(at[0] + (cx * ratio).toInt(), at[1] + (cy * ratio).toInt())
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
<img id="placeholder" src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" width="200" height="60" alt="">
<h2>Ingredients</h2>
<ul id="ingredients"><li>1 cup (226 g) unsalted butter</li><li>1 cup packed brown sugar</li><li>3 cups rolled oats</li></ul>
<h2 id="method">Method</h2>
<ol id="steps"><li id="step1">Brown the butter until it smells nutty.</li><li id="step2">Bake at 350°F for 11 to 13 minutes.</li></ol>
<p id="cool">Cool on the tray.</p>
<p><a id="elsewhere" href="https://hearthandcrumb.example/other">Another recipe</a></p>
<iframe id="ad" title="Ad" width="300" height="50" srcdoc="<button id=&quot;post&quot; onclick=&quot;RCBridge.postMessage(JSON.stringify({type:'selection',text:'Buy now'}))&quot;>Ad: post a selection</button>"></iframe>
</body></html>"""

        /** A recipe list, then a WordPress-style comment thread, itself a list. */
        const val COMMENTS_PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
<style>body{font:16px sans-serif;margin:16px}</style></head><body>
<h1 id="title">Lemon Bars</h1>
<ul id="ingredients"><li>1 cup flour</li><li>2 lemons</li></ul>
<h2>Comments</h2>
<ol class="commentlist">
<li class="comment" id="c1"><article><footer><b>Ann</b> <span>June 3</span></footer>
<div class="comment-content"><p>Made these twice.</p><p>So good.</p></div></article>
<ol class="children"><li class="comment" id="c2"><article><footer><b>Bo</b></footer>
<div class="comment-content"><p>Agreed!</p></div></article></li></ol></li>
<li class="comment" id="c3"><article><footer><b>Cy</b></footer><div class="comment-content"><p>Too sour.</p></div></article></li>
</ol>
</body></html>"""

        const val REDDIT_URL = "https://www.reddit.com/r/recipes/comments/1abc01/apple_pie/"

        /** Enough of a Reddit post for `reddit-reader.js` and RedditPageText, on green. */
        const val REDDIT_PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
<style>html,body{background:#00C800}body{font:16px sans-serif;margin:16px;min-height:3000px}</style></head><body>
<shreddit-post post-title="Apple Pie"><div slot="text-body"><div property="schema:articleBody">
<ul id="list"><li><p>3 apples</p></li><li><p>1 crust</p></li></ul><p>Bake.</p>
<ul id="more"><li><p>1 tsp cinnamon</p></li><li><p>1 egg</p></li></ul>
</div></div></shreddit-post>
</body></html>"""
    }
}
