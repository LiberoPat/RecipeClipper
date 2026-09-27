package com.example.recipeclipper.walkthrough

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.recipeclipper.MainActivity
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.flags.FeatureFlagStore
import com.example.recipeclipper.data.local.TourPreferences
import com.example.recipeclipper.data.local.dao.ListDao
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.NewPantryItem
import com.example.recipeclipper.data.model.Tip
import com.example.recipeclipper.data.model.WelcomeState
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import javax.inject.Inject

/**
 * The walkthrough videos (#106): the real app, seeded with [WalkthroughSeed], driven at a
 * viewer's pace, recording itself with `screenrecord` to [VIDEO] from Home to the end.
 * Only under [WalkthroughRunner] (`-Pwalkthrough`); skipped under the plain runner.
 * `scripts/record-walkthroughs-android.sh` clears the app before each and pulls each video.
 */
@OptIn(ExperimentalTestApi::class)
abstract class WalkthroughBase {
    private val onHilt = ApplicationProvider.getApplicationContext<android.content.Context>() is HiltTestApplication
    private val hilt: HiltAndroidRule? = if (onHilt) HiltAndroidRule(this) else null
    val compose = createEmptyComposeRule()

    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(hilt ?: TestRule { _, _ -> object : Statement() { override fun evaluate() = assumeTrue("walkthrough runner only", false) } })
        .around(compose)

    @Inject lateinit var recipeDao: RecipeDao
    @Inject lateinit var listDao: ListDao
    @Inject lateinit var flagStore: FeatureFlagStore
    @Inject lateinit var tour: TourPreferences
    @Inject lateinit var pantryRepository: PantryRepository
    @Inject lateinit var groceryRepository: GroceryRepository

    var scenario: ActivityScenario<MainActivity>? = null
        private set

    /** The seeded recipes' ids, by title, and the pantry's, by name. */
    val recipeIds = mutableMapOf<String, Long>()
    val pantryIds = mutableMapOf<String, Long>()

    val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * Seeds, turns [flags] on and opens Home, recording from there. [seeded] false: an empty
     * library. [firstRun]: the first-run tour (#151) as a fresh install has it, opening on the
     * welcome; otherwise it is done, so no welcome or tip appears. [kitchen]: the Pantry and
     * the grocery list of [WalkthroughSeed.pantry] and [WalkthroughSeed.groceries] too.
     */
    fun start(
        vararg flags: String,
        seeded: Boolean = true,
        firstRun: Boolean = false,
        kitchen: Boolean = false
    ) {
        hilt!!.inject()
        // Expiry reminders ask for notifications; granted up front so no system dialog shows.
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS
        )
        runBlocking {
            if (seeded) seed()
            if (kitchen) stockTheKitchen()
        }
        flags.forEach { flagStore.setOverride(it, true) }
        if (!firstRun) {
            tour.welcome = WelcomeState.SEEN
            Tip.entries.forEach { tour.setTipSeen(it, true) }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(hasText(if (firstRun) "Next" else "Recipe URL"))
        // Two-thirds size: the script scales every clip to 1280 high anyway, and a smaller frame
        // keeps the emulator's encoder up with the screen on a busy machine (at full size it fell
        // behind and lost the ends of clips).
        shell("screenrecord --bit-rate 6000000 --size 720x1616 $VIDEO")
        Thread.sleep(1000) // screenrecord takes a moment to start
        pause(1500)
    }

    @After
    fun finish() {
        if (scenario == null) return
        pause(2500)
        shell("pkill -INT screenrecord")
        Thread.sleep(2500) // screenrecord finishes the file
        scenario?.close()
    }

    private suspend fun seed() {
        val minute = 60_000L
        val now = System.currentTimeMillis()
        val lists = listDao.observeLists(ListDao.NO_RECIPE).first().associate { it.name to it.id }
        WalkthroughSeed.recipes.forEachIndexed { i, r ->
            val id = recipeDao.upsert(RecipeEntity(
                sourceUrl = "https://example.com/${r.slug}", title = r.title, imageUrl = null,
                ingredients = r.ingredients, instructions = r.steps,
                prepTime = r.times.first, cookTime = r.times.second, totalTime = r.times.third,
                servings = r.servings, sourceType = "BLOG", lastViewedAt = now - (i + 1) * minute,
                contentOrigin = r.origin
            ), historyLimit = 50)
            recipeIds[r.title] = id
            val names = listOfNotNull("Favorites".takeIf { r.title == "Chicken Adobo" }, WalkthroughSeed.listFor(r.title))
            names.forEach { listDao.addToList(RecipeListCrossRef(id, lists.getValue(it), now - 30 * minute)) }
        }
    }

    private suspend fun stockTheKitchen() {
        WalkthroughSeed.pantry.forEach { (name, quantity, inStock) ->
            val id = pantryRepository.add(NewPantryItem(name, "en", quantity = quantity)) ?: return@forEach
            pantryIds[name] = id
            if (!inStock) pantryRepository.setInStock(listOf(id), false)
        }
        val adobo = recipeIds.getValue("Chicken Adobo")
        groceryRepository.add(WalkthroughSeed.groceries.map { NewGroceryLine(it, "en", recipeId = adobo) })
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    /** A file the recording script pushed to the device, read as the shell (the app can't reach it). */
    fun deviceFile(path: String): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $path"))
            .use { it.readBytes() }

    /** Starts [intent] from the app, as another app opening something in it would. */
    fun startFromApp(intent: Intent) {
        scenario!!.onActivity { it.startActivity(intent.setClass(it, MainActivity::class.java)) }
        pause()
    }

    /** The system Back, for a screen that isn't the app's (the share sheet, a system picker). */
    fun systemBack(pauseMs: Long = 1500) {
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(1000)
        pause(pauseMs)
    }

    // --- Pacing and helpers ---

    fun pause(ms: Long = 1500) {
        compose.waitForIdle()
        Thread.sleep(ms)
    }

    /** Waits for [matcher]; on a miss, logs the tree (tag "Walkthrough") and a screenshot path. */
    fun waitFor(matcher: SemanticsMatcher) {
        try {
            compose.waitUntilAtLeastOneExists(matcher, 10_000)
        } catch (e: Throwable) {
            shell("screencap -p /data/local/tmp/rc-walkthrough-miss.png")
            compose.onAllNodes(isRoot()).printToLog("Walkthrough")
            throw e
        }
    }

    fun tap(matcher: SemanticsMatcher, pauseMs: Long = 1500) {
        waitFor(matcher)
        Espresso.closeSoftKeyboard()
        val node = compose.onAllNodes(matcher)[0]
        runCatching { node.performScrollTo() } // off screen in a scrolling form; no-op elsewhere
        node.performClick()
        pause(pauseMs)
    }

    /** Scrolls [text] (a part of it) into view, in a lazy list or a form that scrolls. */
    fun show(text: String, pauseMs: Long = 1500) {
        val matcher = hasText(text, substring = true)
        reveal(matcher)
        waitFor(matcher)
        runCatching { compose.onAllNodes(matcher)[0].performScrollTo() }
        pause(pauseMs)
    }

    fun tap(text: String, pauseMs: Long = 1500) = tap(hasText(text) and !hasSetTextAction(), pauseMs)
    fun tapDescription(description: String) = tap(hasContentDescription(description))
    fun tapTag(tag: String) = tap(hasTestTag(tag))

    fun type(tag: String, text: String, submit: Boolean = true) {
        waitFor(hasTestTag(tag))
        compose.onAllNodes(hasTestTag(tag))[0].performTextInput(text)
        if (submit) compose.onAllNodes(hasTestTag(tag))[0].performImeAction()
        Espresso.closeSoftKeyboard()
        pause(800)
    }

    /** Swipes up on the screen's last scrollable (the topmost: a sheet's list over the screen's). */
    fun swipeUp() {
        compose.waitForIdle()
        val scrollables = compose.onAllNodes(hasScrollAction())
        val count = scrollables.fetchSemanticsNodes().size
        val target = if (count > 0) scrollables[count - 1] else compose.onAllNodes(isRoot())[0]
        target.performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.3f, durationMillis = 600) }
        pause(1000)
    }

    /** Swipes up until [text] is on screen (a lazy list composes only what shows), then taps it. */
    fun tapScrolling(text: String) = tapScrolling(hasText(text) and !hasSetTextAction())

    fun tapScrolling(matcher: SemanticsMatcher) {
        reveal(matcher)
        tap(matcher)
    }

    /** Swipes up until [matcher] is composed (a lazy list composes only what shows). */
    fun reveal(matcher: SemanticsMatcher, swipes: Int = 8) {
        for (i in 0 until swipes) {
            if (compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) break
            swipeUp()
        }
    }

    /** The screen's own Back (text or icon), as a person would tap it; else the system back. */
    fun back() {
        compose.waitForIdle()
        val button = hasText("Back") or hasContentDescription("Back")
        val found = compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty()
        if (found) compose.onAllNodes(button)[0].performClick() else Espresso.pressBack()
        pause()
    }

    /** Scrolls the lazy list tagged [list] to the node tagged [tag]. */
    fun scrollTo(list: String, tag: String) {
        waitFor(hasTestTag(list))
        compose.onAllNodes(hasTestTag(list))[0].performScrollToNode(hasTestTag(tag))
        pause(800)
    }

    fun menu(item: String) {
        tapDescription("More options")
        tap(item)
    }

    companion object {
        const val VIDEO = "/data/local/tmp/rc-walkthrough.mp4"
    }
}
