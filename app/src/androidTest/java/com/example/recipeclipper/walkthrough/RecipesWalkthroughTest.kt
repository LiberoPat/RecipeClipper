package com.example.recipeclipper.walkthrough

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.Connectivity
import com.example.recipeclipper.data.DecisionModel
import com.example.recipeclipper.data.MlKitPhotoTextReader
import com.example.recipeclipper.data.PhotoTextReader
import com.example.recipeclipper.data.PhotoTextResult
import com.example.recipeclipper.data.StepShortener
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.BlogRecipeSource
import com.example.recipeclipper.data.remote.PhotoLine
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.data.remote.RedditRecipeParser
import com.example.recipeclipper.data.remote.RedditRecipeSource
import com.example.recipeclipper.data.remote.RoutingRecipeSource
import com.example.recipeclipper.di.OnDeviceModelModule
import com.example.recipeclipper.di.PhotoTextModule
import com.example.recipeclipper.di.SourceModule
import com.example.recipeclipper.fake.FakeStepShortener
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Walkthroughs 06–10 and 27–31 (#106): the Recipes screen, amounts in steps, Chef mode with a stub
 * model (an emulator has none), the free tier, a recipe picked from the page text (seeded as
 * such: no model runs), Reddit posts (#11) and their photos (#198), in German and French too
 * (#208), a scanned recipe card (#226), and a Reddit post clipped by hand (#213, #230). A pasted
 * link opens a canned recipe, as iOS's UI-test source does; a Reddit link is read by the real
 * [RedditRecipeParser] from a `shared/fixtures/reddit/` listing the recording script pushed, in
 * place of reddit.com's `.json` (which refuses an emulator with 403). Only clip 31's posts, which
 * have no fixture, go to reddit.com itself, live: its refusal is what that clip shows.
 */
@HiltAndroidTest
@UninstallModules(OnDeviceModelModule::class, SourceModule::class, PhotoTextModule::class)
class RecipesWalkthroughTest : WalkthroughBase() {

    @BindValue @JvmField
    val source: RecipeSource = RoutingRecipeSource(
        blog = object : RecipeSource {
            override suspend fun fetch(url: String): ParseResult = if ("based.cooking/" in url) {
                liveBlog.fetch(url) // clip 32's page, live
            } else ParseResult.Success(Recipe(
                name = "Stub Chicken Soup", image = null,
                ingredients = listOf("1 whole chicken", "2 carrots", "8 cups water"),
                instructions = listOf("Simmer everything for 1 hour.", "Season and serve."),
                prepTime = null, cookTime = null, totalTime = null, yield = "4", sourceUrl = url
            ))
        },
        // The post's listing, as reddit.com's `.json` would send it, through the real parser.
        reddit = object : RecipeSource {
            override suspend fun fetch(url: String): ParseResult {
                val (id, fixture) = REDDIT_FIXTURES.entries.firstOrNull { (id, _) -> "/comments/$id/" in url }
                    ?: return liveReddit.fetch(url)
                val result = RedditRecipeParser.parse(String(deviceFile("/data/local/tmp/$fixture")), url)
                // An untranscribed card's pictures, swapped for the local card photos (the
                // fixtures' own addresses don't exist), as iOS's RC_UITEST_PHOTO_IMAGES does.
                val pictures = postPictures[id]
                val error = (result as? ParseResult.Error)?.error as? ParseError.NoTranscription
                return if (pictures != null && error != null) {
                    ParseResult.Error(error.copy(imageUrl = pictures.first(), imageUrls = pictures))
                } else {
                    result
                }
            }

            override fun readsRenderedPage(url: String) = false
        }
    )

    private val online = object : Connectivity {
        override fun isOnline() = true
        override val online = flowOf(true)
    }

    /** reddit.com itself, for clip 31's posts: the real source, whose `.json` read Reddit refuses. */
    private val liveReddit = RedditRecipeSource(online)

    /** The real blog source, for clip 32's page, which has no recipe data. */
    private val liveBlog = BlogRecipeSource(online)

    /** Local pictures in place of a post's (by post id), for "Read the photo" (28, 29). */
    private var postPictures: Map<String, List<String>> = emptyMap()

    @BindValue @JvmField
    val decisionModel: DecisionModel = WalkthroughSeed.decisionModel()

    /**
     * "Read the photo" (#198) and "Scan a recipe" (#226): ML Kit itself when the emulator's Play
     * services can read ([realOcr]); else OCR SIMULATED: the first read answers a recipe card's
     * lines ([cardLines]: one the recogniser was unsure of) and every later read answers nothing,
     * for the fallback.
     */
    @BindValue @JvmField
    val photoTextReader: PhotoTextReader = object : PhotoTextReader {
        private var reads = 0
        override suspend fun read(imageUrls: List<String>): PhotoTextResult =
            realReader?.read(imageUrls)
                ?: if (reads++ == 0) PhotoTextResult.Read(cardLines) else PhotoTextResult.Read(emptyList())
    }

    /** ML Kit itself, when the emulator's Play services can read (see [realOcr]). */
    private var realReader: PhotoTextReader? = null

    /** What the first photo read answers: clip 28's English card, clip 29's French one. */
    private var cardLines = CARD_LINES

    @BindValue @JvmField
    val shortener: StepShortener = FakeStepShortener(
        support = ChefSupport.Available(setOf("en")),
        written = mutableMapOf(
            WalkthroughSeed.CHEF_STEP to WalkthroughSeed.CHEF_SHORT,
            WalkthroughSeed.WHISK to WalkthroughSeed.WHISK_SHORT
        )
    )

    private fun field(label: String) = hasSetTextAction() and hasText(label, substring = true)

    /** Home's "Recipes ›" row, below the fold: not the Recipes tab, which is Home itself (#152). */
    private fun openRecipes() = tapScrolling(hasText("Recipes") and hasText("›"))

    private fun typeARecipe() {
        tapDescription("Add a recipe")
        tap("Type a recipe")
        waitFor(field("Name"))
        compose.onAllNodes(field("Name"))[0].performTextInput("Weeknight Stew")
        pause(800)
        compose.onAllNodes(field("Ingredients, one per line"))[0].performTextInput("2 carrots\n1 lb stewing beef")
        pause(800)
        tap("Save", 2000)
    }

    @Test
    fun test06_recipesScreen() {
        start()
        openRecipes()
        menu("Name")
        typeARecipe()
        back()
        tapDescription("Add a recipe")
        tap("Paste a link")
        // The dialog's field, not the Recipes search field behind it.
        waitFor(field("Recipe URL"))
        compose.onAllNodes(field("Recipe URL"))[0].performTextInput("https://example.com/chicken-soup")
        pause(800)
        tap("Go", 3000)
        back()
        // A burst of swipes shares one snackbar, counting in place, and one Undo brings them all
        // back, with no snackbar after it (#268).
        for (title in listOf("Banana Bread", "Beef Stir-Fry", "Buttermilk Pancakes")) {
            waitFor(hasText(title))
            compose.onAllNodes(hasText(title))[0].performTouchInput { swipeLeft(durationMillis = 500) }
            pause(900)
        }
        waitFor(hasText("3 recipes deleted"))
        pause(2000)
        tap("Undo", 3500)
    }

    /**
     * Amounts in steps (#101, #254): the switch, then the Lemon Garlic Salmon, whose butter is on
     * two lines ([WalkthroughSeed.SALMON]): "the butter" is a link that shows both lines, with
     * "Back to step 2"; in cook mode it opens the ingredients bar on them.
     */
    @Test
    fun test07_amountsInSteps() {
        start()
        tapDescription("Settings")
        swipeUp()
        tap("Amounts in steps")
        back()
        openRecipes()
        tapScrolling(WalkthroughSeed.SALMON)
        show("Melt the", 2500)
        tapLink("Melt the", "butter")
        waitFor(hasText("Back to step 2"))
        pause(3000)
        tap("Back to step 2", 2500)
        tap("Start cooking", 2000)
        tap("Done — next step", 2000)
        tapLink("Melt the", "butter")
        waitFor(hasText("Back to step 2"))
        pause(3000)
        tap("Back to step 2", 2500)
    }

    /** A real tap on [word] inside the step that holds [step]: its ingredient link (#254). */
    private fun tapLink(step: String, word: String) {
        val matcher = hasText(step, substring = true)
        waitFor(matcher)
        val node = compose.onAllNodes(matcher, useUnmergedTree = true)[0]
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.first()
        val at = layout.layoutInput.text.text.indexOf(word)
        check(at >= 0) { "no \"$word\" in the step" }
        val box = layout.getBoundingBox(at + word.length / 2)
        node.performTouchInput { click(box.center) }
        pause(1500)
    }

    @Test
    fun test08_chefModeStubModel() {
        start("chefMode")
        tapDescription("Settings")
        swipeUp()
        tap("Chef mode")
        back()
        tap("Sponge Cake")
        swipeUp()
        pause(1000)
        tap(WalkthroughSeed.CHEF_SHORT, 2500) // as written…
        tap(WalkthroughSeed.CHEF_STEP, 2000) // …and short again
        tap("Start cooking", 2000)
        tap("As written", 2500)
    }

    @Test
    fun test09_freeTier() {
        start("freeTier")
        openRecipes()
        waitFor(hasText("20 of 20 recipes"))
        pause(2000)
        typeARecipe()
        waitFor(hasText("Unlock"))
        pause(3000)
        tap("Cancel")
    }

    @Test
    fun test10_pageExtractionLine() {
        start("llmExtraction")
        tap("Grandma's Lentil Soup")
        waitFor(hasText("Picked from the page text", substring = true))
        pause(3000)
        swipeUp()
    }

    /**
     * Reddit posts (#11), read by the real parser from fixtures (see the class comment): a
     * self-post's recipe, an old recipe card written out in a comment, and a food photo whose
     * comments hold no recipe.
     */
    @Test
    fun test27_redditImport() {
        start("reddit")
        for (post in listOf(SELF_POST, CARD_POST, PHOTO_POST)) {
            waitFor(field("Recipe URL"))
            compose.onAllNodes(field("Recipe URL"))[0].performTextInput(post)
            pause(1000)
            tap("Go", 3500)
            if (post != PHOTO_POST) {
                swipeUp()
                pause(1500)
            }
            back()
        }
    }

    /**
     * Walkthrough 28, "Read the photo" (#198): an untranscribed recipe card post, its pictures the
     * local card's front and back, read by ML Kit ([realOcr]; else OCR SIMULATED) into "Check the
     * recipe", the lines to check fixed, saved as a clip; then a blurred photo, which reads
     * nothing, opens the editor to finish by hand.
     */
    @Test
    fun test28_readThePhoto() {
        useRealOcrIfItReads()
        postPictures = mapOf(
            "1f7a2bc" to listOf(scanPage("card-front"), scanPage("card-back")),
            "1f7a3cd" to listOf(scanPage("card-blurred"))
        )
        start("reddit", "photoText")
        openPost(CARD_PHOTO_POST)
        tap("Read the photo", 2500)
        waitForReading()
        pause(2500)
        show("Check these lines", 3000)
        // Fix the lines flagged to check, and the recogniser's other slips (as clip 30 does).
        val box = compose.onAllNodes(field("Ingredients, one per line"))[0]
        val typed = box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        box.performTextReplacement(fixCardLines(typed))
        pause(2000)
        tap("Save", 3000)
        waitFor(hasText("Clipped by you", substring = true))
        pause(2500)
        swipeUp()
        pause(2500)
        back()
        openPost(BLURRY_PHOTO_POST)
        tap("Read the photo", 2500)
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText("Couldn't read a recipe from the photo", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        pause(4000)
    }

    /** Waits for the photo's lines ("Read from the photo…"): a real read can take a while. */
    private fun waitForReading() {
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText("Read from the photo", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The card's lines to check, fixed, and ML Kit's other slips on it (see test30). */
    private fun fixCardLines(typed: String): String =
        typed.lines().filterNot { it.trim().startsWith("Direct") }.joinToString("\n") { line ->
            if ("rasin" in line || "raisi" in line) {
                "1 cup raisins"
            } else {
                line.replace("11/2", "1 1/2").replace("eg9s", "eggs").replace("1 oup", "1 cup").replace("Cups", "cups")
            }
        }

    /** [realReader] is ML Kit if it reads here, else null (OCR SIMULATED); logged for the script. */
    private fun useRealOcrIfItReads() {
        realReader = realOcr(scanPage("card-front"))
        Log.i("Walkthrough", if (realReader != null) "OCR REAL" else "OCR SIMULATED")
    }

    /**
     * Walkthrough 29, reading other languages (#208): a German Reddit post (the `de` language
     * fixture's, through the real parser) sorted under its German headings, scaled and shown in
     * Metric; then a French recipe card (`card-fr.jpg`, the `fr` fixture's photo lines drawn on a
     * card) read by ML Kit ([realOcr]; else OCR SIMULATED, the `fr` fixture's lines), whose glued
     * "2 c. à soupesucre" is fixed and saved.
     */
    @Test
    fun test29_readingOtherLanguages() {
        cardLines = FRENCH_CARD_LINES
        useRealOcrIfItReads()
        postPictures = mapOf("1f8c5fr" to listOf(scanPage("card-fr")))
        start("reddit", "photoText")
        waitFor(field("Recipe URL"))
        compose.onAllNodes(field("Recipe URL"))[0].performTextInput(GERMAN_POST)
        pause(1000)
        tap("Go", 2500)
        waitFor(hasText("Omas Pfannkuchen"))
        // Doubled, 4 to 8: whole amounts, where 5 would show "312 1/2 g Mehl".
        repeat(4) { tap(hasContentDescription("Increase servings"), 400) }
        pause(1000)
        tap("As written", 1000)
        tap("Metric", 2000)
        swipeUp()
        pause(2000)
        back()
        openPost(FRENCH_CARD_POST)
        tap("Read the photo", 2500)
        waitForReading()
        pause(2000)
        swipeUp()
        pause(2500)
        // Fix the line with its unit run into the next word, however the reader spelt it, and
        // ML Kit's slips on this card: "4 cufs" (the line it flags), "1/2l", "pineée", and the
        // sugar line read as "à soupesuore", its "2 c." lost.
        val box = compose.onAllNodes(field("Ingredients, one per line"))[0]
        val typed = box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        box.performTextReplacement(
            typed.lines().joinToString("\n") { line ->
                when {
                    "soupe" in line -> "2 c. à soupe de sucre"
                    "ufs" in line -> "4 œufs"
                    else -> line.replace("1/2l ", "1/2 l ").replace("1/21 ", "1/2 l ").replace("pineée", "pincée")
                }
            }
        )
        pause(2000)
        tap("Save", 2500)
        waitFor(hasText("Clipped by you", substring = true))
        pause(1500)
        swipeUp()
        pause(1000)
    }

    /**
     * Walkthrough 30, "Scan a recipe" (#226): Recipes + → Scan a recipe → Choose from library, the
     * fixture card's two sides handed back as the Photo Picker's answer (the picker can't be
     * driven, as in clip 13), "Check the recipe" with both pages on top, the name typed (none is
     * guessed), the flagged line fixed, Save; then Home's own "Scan a recipe". ML Kit reads the
     * pages when the emulator's Play services can ([realOcr]); otherwise it's OCR SIMULATED (clip
     * 28's card lines), which the test logs and the recording script puts in the clip's name.
     */
    @Test
    fun test30_scanARecipe() {
        val pages = listOf(scanPage("card-front"), scanPage("card-back"))
        useRealOcrIfItReads()
        start("photoText")
        openRecipes()
        tapDescription("Add a recipe")
        tap("Scan a recipe")
        chooseFromLibrary(pages)
        waitFor(hasContentDescription("Page 2 of 2"))
        waitForReading()
        pause(3000)
        // No title is guessed: the cook names it.
        compose.onAllNodes(field("Name"))[0].performTextInput("Aunt June's Oatmeal Cookies")
        pause(1500)
        show("Check these lines", 3000)
        // Fix the lines flagged to check, and ML Kit's other slips on this card (the simulated
        // lines' "1 cup rasins"; the real reader's "2 eg9s", "11/2", "1 oup", and the back's
        // "Directíons" heading, which it read with an accent and so left among the ingredients).
        val box = compose.onAllNodes(field("Ingredients, one per line"))[0]
        val typed = box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        box.performTextReplacement(fixCardLines(typed))
        pause(2500)
        tap("Save", 3000)
        waitFor(hasText("Aunt June's Oatmeal Cookies"))
        pause(2000)
        swipeUp()
        pause(2500)
        back()
        back()
        // Home, still scrolled down to its Recipes row: back up to the link field.
        waitFor(hasScrollAction())
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Scan a recipe"))
        pause(1000)
        tap("Scan a recipe", 2500)
        Espresso.pressBack()
        pause(1000)
    }

    /** "Choose from library", with [pages] handed back as the Photo Picker's answer (it can't be driven). */
    private fun chooseFromLibrary(pages: List<String>) {
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != MediaStore.ACTION_PICK_IMAGES && intent.type?.startsWith("image/") != true) return null
                val uris = pages.map(Uri::parse)
                val data = Intent().apply {
                    clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                return Instrumentation.ActivityResult(Activity.RESULT_OK, data)
            }
        }
        instrumentation.addMonitor(picker)
        try {
            tap("Choose from library", 2500)
        } finally {
            instrumentation.removeMonitor(picker)
        }
    }

    /**
     * Walkthrough 34, fraction glyphs read from a photo (#245, #276): the handwritten fraction card
     * (`card-fractions-hand.jpg`) scanned and read by ML Kit ([realOcr]; else OCR SIMULATED, its
     * measured reading), which gives its fractions as look-alikes ("%", "a", "Ys", "&") and its
     * cup as "oup". "Check these lines" flags them, the look-alikes with the fraction chips: each
     * chip is found by its spoken label and tapped, and tapped again if its line didn't change.
     * Then the slips nothing flags ("mik", "4 tsp salt" for ¾) are typed right, as clip 28 does,
     * so the saved recipe is the card's; Save.
     */
    @Test
    fun test34_fractionChips() {
        val page = scanPage("card-fractions-hand")
        useRealOcrIfItReads()
        cardLines = FRACTION_CARD_LINES
        start("photoText")
        openRecipes()
        tapDescription("Add a recipe")
        tap("Scan a recipe")
        chooseFromLibrary(listOf(page))
        waitForReading()
        pause(2500)
        compose.onAllNodes(field("Name"))[0].performTextInput("Oat Cookies") // none is guessed
        Espresso.closeSoftKeyboard()
        pause(1200)
        show("Check these lines", 3500)
        // Every line with chips has a ½ chip: "Use ½ in <line>".
        val chipLines = compose.onAllNodes(spokenStartingWith("Use ½ in ")).fetchSemanticsNodes().map {
            it.config[SemanticsProperties.ContentDescription].joinToString().removePrefix("Use ½ in ")
        }
        Log.i("Walkthrough", "FRACTION CHIPS ${chipLines.joinToString(" | ")}")
        val box = compose.onAllNodes(field("Ingredients, one per line"))[0]
        fun boxLines() = box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text.lines().map { it.trim() }
        for (line in chipLines) {
            val fraction = FRACTION_CARD.entries.firstOrNull { (key, _) -> key in line }?.value?.first?.takeIf(String::isNotEmpty) ?: continue
            repeat(2) { attempt ->
                if (line !in boxLines()) return@repeat
                if (attempt > 0) Log.i("Walkthrough", "chip $fraction for \"$line\" missed; tapping again")
                val chip = compose.onAllNodes(hasContentDescription("Use $fraction in $line"))
                if (chip.fetchSemanticsNodes().isEmpty()) return@repeat
                chip[0].performScrollTo()
                pause(1200)
                chip[0].performClick()
                pause(2500)
            }
            check(line !in boxLines()) { "the $fraction chip didn't change \"$line\"" }
        }
        // The slips nothing flags, typed right: each ingredient line the card's, by what it holds.
        val typed = box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        val fixed = typed.lines().joinToString("\n") { line ->
            // A line right but for a space ("1¼ cups flour") is left as read.
            FRACTION_CARD.entries.firstOrNull { (key, _) -> key in line }?.value?.second
                ?.takeIf { it.replace(" ", "") != line.replace(" ", "") } ?: line
        }
        Log.i("Walkthrough", "FRACTION TYPED ${typed.lines().filter { it !in fixed.lines() }.joinToString(" | ")}")
        if (fixed != typed) {
            box.performScrollTo()
            pause(1200)
            box.performTextReplacement(fixed)
            pause(2500)
        }
        // And in the steps: ML Kit reads "milk" as "mik" there too.
        val steps = compose.onAllNodes(field("Steps, one per line"))[0]
        val stepsTyped = steps.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        val stepsFixed = stepsTyped.replace(Regex("""\bmik\b"""), "milk")
        if (stepsFixed != stepsTyped) {
            steps.performScrollTo()
            pause(1200)
            steps.performTextReplacement(stepsFixed)
            pause(2500)
        }
        Espresso.closeSoftKeyboard()
        tap("Save", 3000)
        waitFor(hasText("Oat Cookies"))
        pause(2500)
        swipeUp()
        pause(2000)
    }

    private fun spokenStartingWith(prefix: String) = SemanticsMatcher("spoken starts with \"$prefix\"") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(prefix) }
    }

    /**
     * Walkthrough 31, "Clip it yourself" on a Reddit post Reddit won't let the app read (#213,
     * #230), LIVE: real r/recipes posts. Reddit refuses the app's `.json` read (403, and again on
     * the one retry), so the post opens in the clip view with the note and no Try again. The
     * whole body shows (no "Read more" fade, no app or sign-in prompt) and scrolls with the
     * finger. It opens on the guided steps (#251): the name is skipped, then the ingredients and
     * the steps are each selected by script ([confirmPick]: a drag across a web page can't be
     * scripted, as in ClipScreenTest) and added with the step's confirm, which arms the next step;
     * the photo is skipped. Done with no name opens Review, which asks for it; typed, Save opens
     * the recipe. Then a second post in the Text view: its name and ingredients the same way, and
     * its steps (in the poster's comment) selected first ([pickFirst]): Steps disarmed, the text
     * selected, then Steps' button. Saved.
     */
    @Test
    fun test31_redditClipYourself() {
        start("reddit")
        val page = openBlockedPost(CLIP_POST)
        waitFor(hasText("Step 1 of 4 · Tap the recipe's name"))
        pause(2500)
        // The whole post body, scrolled with the finger to its end.
        repeat(5) {
            swipeWeb(page)
            pause(1200)
        }
        pause(1500)
        tap("Skip", 1000)
        waitFor(hasText("Step 2 of 4 · Tap the ingredients"))
        pause(2000)
        confirmPick(page, "2 pounds bone-in", "Black pepper, to taste")
        waitFor(hasText("Step 3 of 4 · Tap the steps"))
        pause(2000)
        confirmPick(page, "Prepare the chicken:", "remaining vinaigrette on the side.")
        waitFor(hasText("Step 4 of 4 · Tap the recipe's photo"))
        pause(2000)
        tap("Skip", 2500)
        // No name yet: Done opens Review, which asks for it.
        tap("Done", 3000)
        waitFor(hasText("Type the recipe's name", substring = true))
        compose.onAllNodes(field("Name"))[0].performTextInput(CLIP_POST_NAME)
        pause(2000)
        tap("Save", 3000)
        waitLong(hasText(CLIP_POST_NAME))
        pause(3000)
        swipeUp()
        pause(2000)
        back()

        // The Text view: the post and its loaded comments as plain text.
        openBlockedPost(TEXT_POST)
        tap("Text", 2500)
        val text = waitForWebView { it.url?.contains("reddit.com") != true }
        waitForPage(text, "typeof window.RC == 'object' && document.body.innerText.indexOf('Marinate the beef') >= 0")
        pause(2500)
        waitFor(hasText("Step 1 of 4 · Tap the recipe's name"))
        pause(1500)
        confirmPick(text, "Beef Bourguignon", null)
        waitFor(hasText("Step 2 of 4 · Tap the ingredients"))
        pause(2000)
        confirmPick(text, "800g beef chuck", "to serve")
        waitFor(hasText("Step 3 of 4 · Tap the steps"))
        pause(2000)
        pickFirst(text, "Steps", "Marinate the beef overnight", "with parsley on top.")
        tap("Done", 3000)
        pause(1500)
        tap("Save", 3000)
        waitLong(hasText("Clipped by you", substring = true))
        pause(3000)
        swipeUp()
        pause(2000)
    }

    /**
     * Walkthrough 32, "Clip it yourself" on a blog page (#37, guided since #251), LIVE: a real
     * based.cooking page with no recipe data, read by the real blog source, so the app says it
     * found none and offers Clip it yourself. It opens on step 1, Name armed, and each confirm
     * arms the next step. Every pick is a real tap through the shell, and the page is scrolled
     * with a finger's swipe: the title; the ingredients list; the steps list; then Steps' button
     * to go back, the paragraph under the list, and Steps' button again to add it (selected
     * first); one Undo, and that paragraph again with its confirm; then the photo. Done, Review,
     * Save.
     */
    @Test
    fun test32_clipItYourself() {
        start()
        waitFor(field("Recipe URL"))
        compose.onAllNodes(field("Recipe URL"))[0].performTextInput(CLIP_PAGE)
        pause(1000)
        tap("Go", 1000)
        waitLong(hasText("Clip it yourself"))
        pause(3000)
        tap("Clip it yourself", 1000)
        val page = waitForWebView { it.url?.contains("based.cooking") == true }
        waitForPage(page, "typeof window.RC == 'object' && !!document.querySelector('article img') && document.querySelector('article img').complete")
        pause(3000)

        waitFor(hasText("Step 1 of 4 · Tap the recipe's name"))
        pause(1000)
        tapOnPage(page, "header h1")
        waitFor(hasText("Use as the name"))
        pause(2000)
        tapTag("clip.confirm", 3000)

        waitFor(hasText("Step 2 of 4 · Tap the ingredients"))
        pause(1500)
        tapOnPage(page, "#ingredients + ul li:nth-child(2)")
        waitFor(hasText("Add 3 lines to Ingredients"))
        pause(2000)
        tapTag("clip.confirm", 3000)

        waitFor(hasText("Step 3 of 4 · Tap the steps"))
        pause(1500)
        tapOnPage(page, "#directions + ol li:nth-child(2)")
        waitFor(hasText("Add 6 lines to Steps"))
        pause(2000)
        tapTag("clip.confirm", 3000)
        waitFor(hasText("Step 4 of 4 · Tap the recipe's photo"))
        pause(1000)
        // A second block adds to the first: back to Steps, the line under the list, selected
        // first, then Steps' button adds it straight away.
        tapTag("clip.field.STEPS", 2000)
        tapOnPage(page, "#directions + ol + p")
        waitFor(hasText("Add 1 line to Steps"))
        pause(1500)
        tapTag("clip.field.STEPS", 3000)
        // Undo takes that add back (Steps again); the paragraph is added again, with its confirm.
        tap("Undo", 3000)
        tapOnPage(page, "#directions + ol + p")
        waitFor(hasText("Add 1 line to Steps"))
        pause(1500)
        tapTag("clip.confirm", 3000)
        waitFor(hasText("Step 4 of 4 · Tap the recipe's photo"))

        tapOnPage(page, "article img")
        waitFor(hasText("Photo added", substring = true))
        pause(3500)

        tap("Done", 3000)
        waitFor(hasText("Review"))
        swipeUp()
        pause(2000)
        tap("Save", 3000)
        waitLong(hasText("Clipped by you", substring = true))
        pause(3000)
        swipeUp()
        pause(2000)
    }

    /**
     * A finger's tap on the page's [selector], after swiping the page until it sits in the
     * middle of the view: the shell's input, so the page hears a real tap.
     */
    private fun tapOnPage(view: WebView, selector: String) {
        val at = IntArray(2)
        var height = 0
        var width = 0
        instrumentation.runOnMainSync {
            view.getLocationOnScreen(at)
            height = view.height
            width = view.width
        }
        val rect = "(function () { var r = document.querySelector(${quote(selector)}).getBoundingClientRect();" +
            " return [r.left + r.width / 2, r.top + Math.min(r.height / 2, 40), window.innerHeight].join(','); })()"
        repeat(8) {
            val (x, y, inner) = js(view, rect).trim('"').split(',').map { it.toDouble() }
            val scale = height / inner
            val screenY = (y * scale).toInt()
            if (screenY in height / 25..height * 5 / 6) {
                instrumentation.uiAutomation.executeShellCommand(
                    "input tap ${at[0] + (x * scale).toInt()} ${at[1] + screenY}"
                ).close()
                pause(1500)
                return
            }
            val x0 = at[0] + width / 2
            val (from, to) = if (screenY > height / 2) height * 3 / 4 to height / 3 else height / 3 to height * 3 / 4
            instrumentation.uiAutomation.executeShellCommand("input swipe $x0 ${at[1] + from} $x0 ${at[1] + to} 600").close()
            pause(1500)
        }
        error("$selector never came into view")
    }

    /**
     * Types [link] into Home's field and waits for Reddit's refusal to open the clip view, and
     * for the post to load in it (past Reddit's own check); returns the page's web view.
     */
    private fun openBlockedPost(link: String): WebView {
        waitFor(field("Recipe URL"))
        compose.onAllNodes(field("Recipe URL"))[0].performTextInput(link)
        pause(1000)
        tap("Go", 1000)
        waitLong(hasText("Reddit didn't let the app read this post", substring = true))
        val page = waitForWebView { it.url?.contains("reddit.com") == true }
        waitForPage(page, "!!document.querySelector('shreddit-post') && typeof window.RC == 'object' && typeof window.RCReddit == 'object'")
        pause(3500)
        return page
    }

    /** Waits up to a minute for [matcher]: a live page, or Reddit's refusal and one retry. */
    private fun waitLong(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.waitUntil(60_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The clip view's shown web views (the page, and the Text view over it) that are [which]. */
    private fun webViews(which: (WebView) -> Boolean): List<WebView> {
        val found = mutableListOf<WebView>()
        scenario!!.onActivity { activity ->
            fun walk(view: View) {
                if (view is WebView && view.isShown && view.width > 0 && which(view)) found += view
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(activity.window.decorView)
        }
        return found
    }

    private fun waitForWebView(which: (WebView) -> Boolean): WebView {
        var view: WebView? = null
        compose.waitUntil(60_000) {
            view = webViews(which).firstOrNull()
            view != null
        }
        return view!!
    }

    /** Runs [script] in [view] and returns its result as JSON ("" if it didn't answer in time). */
    private fun js(view: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { result = it; latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    private fun waitForPage(view: WebView, condition: String) {
        compose.waitUntil(90_000) { js(view, "!!($condition)") == "true" }
    }

    /** A finger's swipe up the web page (the shell's input, so the page scrolls as it would). */
    private fun swipeWeb(view: WebView) {
        val at = IntArray(2)
        var height = 0
        var width = 0
        instrumentation.runOnMainSync {
            view.getLocationOnScreen(at)
            height = view.height
            width = view.width
        }
        val x = at[0] + width / 2
        instrumentation.uiAutomation.executeShellCommand(
            "input swipe $x ${at[1] + height * 3 / 4} $x ${at[1] + height / 4} 700"
        ).close()
        Thread.sleep(900)
    }

    /**
     * Guided (#251): brings the text from [from] to [to] (or [from] alone) into view and selects
     * it, as a finger's drag would (the page reports it through `selectionchange`), then taps the
     * armed step's confirm ("Add 22 lines to Ingredients"): it goes there, marked on the page,
     * and the next step arms.
     */
    private fun confirmPick(view: WebView, from: String, to: String?) {
        select(view, from, to)
        waitFor(hasTestTag("clip.confirm"))
        pause(2500)
        tapTag("clip.confirm", 3000)
    }

    /**
     * Selected first (#251): [field]'s button disarms its step, the text is selected (the hint
     * says "N lines selected · tap the field they go in"), and the button adds it straight away.
     */
    private fun pickFirst(view: WebView, field: String, from: String, to: String?) {
        tapTag("clip.field.${field.uppercase()}", 2000)
        select(view, from, to)
        waitFor(hasText("tap the field they go in", substring = true))
        pause(3000)
        tapTag("clip.field.${field.uppercase()}", 3000)
    }

    private fun select(view: WebView, from: String, to: String?) {
        val script = SELECT_JS + "(${quote(from)}, ${to?.let(::quote) ?: "null"})"
        compose.waitUntil(15_000) { js(view, script) == "true" }
    }

    private fun quote(text: String) = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** A fixture page the recording script pushed, as a picture of the app's own (FileProvider). */
    private fun scanPage(name: String): String {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "camera/walkthrough-$name.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(deviceFile("/data/local/tmp/$name.jpg"))
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
    }

    /**
     * ML Kit's reader if it reads [page] here. Play services may first have to download the model
     * (a read asks it to), so NotReady is retried for up to two minutes. Null: simulate.
     */
    private fun realOcr(page: String): PhotoTextReader? {
        val reader = MlKitPhotoTextReader(instrumentation.targetContext)
        repeat(24) {
            when (val result = runBlocking { reader.read(listOf(page)) }) {
                is PhotoTextResult.Read -> return reader.takeIf { result.lines.isNotEmpty() }
                PhotoTextResult.NotReady -> Thread.sleep(5000)
                PhotoTextResult.Failed -> return null
            }
        }
        return null
    }

    private fun openPost(link: String) {
        waitFor(field("Recipe URL"))
        compose.onAllNodes(field("Recipe URL"))[0].performTextInput(link)
        pause(1000)
        tap("Go", 3500)
        waitFor(hasText("Read the photo"))
        pause(2000)
    }

    companion object {
        const val CARD_PHOTO_POST = "https://www.reddit.com/r/Old_Recipes/comments/1f7a2bc/aunt_junes_oatmeal_cookies_front_and_back_of_the_card/"
        const val BLURRY_PHOTO_POST = "https://www.reddit.com/r/Old_Recipes/comments/1f7a3cd/aunt_junes_oatmeal_cookies_blurry_photo/"

        /** What the card "reads" as: an unsure word on one line, as a recogniser gives it. */
        val CARD_LINES = listOf(
            "Aunt June's Oatmeal Cookies", "Ingredients", "1 cup butter", "1 cup brown sugar", "2 eggs",
            "1 1/2 cups flour", "1 tsp baking soda", "3 cups rolled oats"
        ).map { PhotoLine(it, 0.9f) } + PhotoLine("1 cup rasins", 0.3f) + listOf(
            "Directions", "1. Cream the butter and sugar, then beat in the eggs.",
            "2. Stir in the flour, soda, oats and raisins.", "3. Drop by spoonfuls and bake at 350°F for 10 minutes."
        ).map { PhotoLine(it, 0.9f) }

        /** The handwritten fraction card as ML Kit read it on the emulator (#245's measurements). */
        val FRACTION_CARD_LINES = listOf(
            "Oat Cookies", "INGREDIENTS", "½ oup sugar", "1 a cups flour", "4 tsp salt", "Ys cup mik",
            "2 % cups oats", "& tsp nutmeg", "2 oz 70% chocolate", "METHOD", "Mix the dry ingredients.",
            "Stir in the milk and chocolate.", "Bake 12 minutes at 350°F."
        ).map { PhotoLine(it, 0.8f) }

        /**
         * The fraction card's ingredient lines, by a piece of each that survives the reading
         * ("mi" of "mik"): its fraction, then the line as the card has it.
         */
        val FRACTION_CARD = linkedMapOf(
            "sug" to ("½" to "½ cup sugar"), "flo" to ("¼" to "1 ¼ cups flour"), "salt" to ("¾" to "¾ tsp salt"),
            "oat" to ("⅔" to "2 ⅔ cups oats"), "nut" to ("⅛" to "⅛ tsp nutmeg"), "choc" to ("" to "2 oz 70% chocolate"),
            "mi" to ("⅓" to "⅓ cup milk")
        )

        const val GERMAN_POST = "https://www.reddit.com/r/Kochen/comments/1f8b4de/omas_pfannkuchen/"
        const val FRENCH_CARD_POST = "https://www.reddit.com/r/cuisine/comments/1f8c5fr/la_fiche_de_crepes_de_ma_grandmere/"

        /** The French card's lines, as `shared/fixtures/languages/fr.json`'s photo has them. */
        val FRENCH_CARD_LINES = listOf(
            "Crêpes", "INGRÉDIENTS", "250 g de farine", "4 œufs", "1/2 l de lait", "2 c. à soupesucre", "1 pincée de sel",
            "PRÉPARATION", "Mettre la farine dans un saladier.", "Ajouter les œufs puis le lait petit à petit.",
            "Laisser reposer 1 heure et cuire dans une poêle chaude."
        ).map { PhotoLine(it, 0.9f) }

        const val SELF_POST = "https://www.reddit.com/r/recipes/comments/1f4b2cd/weeknight_lemon_chicken_orzo/"
        const val CARD_POST = "https://www.reddit.com/r/Old_Recipes/comments/1f5c3de/grandmas_date_nut_bread_found_in_her_recipe_tin/"
        const val PHOTO_POST = "https://www.reddit.com/r/food/comments/1f6d4ef/homemade_sunday_lasagna/"

        /** Clip 31's posts, live on reddit.com: a recipe in the post body, then one in the poster's comment. */
        const val CLIP_POST = "https://www.reddit.com/r/recipes/comments/1wpafnm/braised_chicken_with_white_beans_and_zucchini/"
        const val CLIP_POST_NAME = "Braised Chicken with White Beans and Zucchini"
        const val TEXT_POST = "https://www.reddit.com/r/recipes/comments/1wixh7a/beef_bourguignon/"

        /** Clip 32's page, live: a recipe with a photo and no recipe data (no JSON-LD, no microdata). */
        const val CLIP_PAGE = "https://based.cooking/stracciatella-soup/"

        /**
         * Selects from the first `from` in the page's text to the first `to` after it, after
         * scrolling it into view (below Reddit's header). True once selected.
         */
        const val SELECT_JS = """(function (from, to) {
  to = to || from;
  var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT), node, start = null, offset = 0;
  while ((node = walker.nextNode())) {
    if (!start) {
      var i = node.data.indexOf(from);
      if (i < 0) continue;
      start = node; offset = i;
    }
    var j = node.data.indexOf(to, node === start ? offset : 0);
    if (j < 0) continue;
    var range = document.createRange();
    range.setStart(start, offset);
    range.setEnd(node, j + to.length);
    var top = range.getBoundingClientRect().top + window.scrollY - 140;
    window.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
    setTimeout(function () {
      var selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    }, 900);
    return true;
  }
  return false;
})"""

        /** A post's id and the fixture `scripts/record-walkthroughs-android.sh` pushes for it. */
        val REDDIT_FIXTURES = mapOf(
            "1f4b2cd" to "recipes-self-post.json",
            "1f5c3de" to "old-recipes-card-transcription.json",
            "1f6d4ef" to "food-photo-chatter.json",
            "1f7a2bc" to "old-recipes-card-untranscribed.json",
            "1f7a3cd" to "old-recipes-card-untranscribed.json",
            "1f8b4de" to "de-self-post.json",
            "1f8c5fr" to "fr-card-untranscribed.json"
        )
    }
}
