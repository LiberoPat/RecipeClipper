package com.example.recipeclipper.walkthrough

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.hasText
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.DecisionModel
import com.example.recipeclipper.data.StepShortener
import com.example.recipeclipper.di.OnDeviceModelModule
import com.example.recipeclipper.fake.FakeDecisionModel
import com.example.recipeclipper.fake.FakeStepShortener
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Test
import java.time.LocalDate

/**
 * Walkthroughs 38 and 39 (#106, #309): a timer counting down outside the app (the ongoing
 * notification in the shade) and the home-screen widgets. Both leave the app for the system's
 * screens, driven through UiAutomation's node tree.
 */
@HiltAndroidTest
@UninstallModules(OnDeviceModelModule::class)
class HomeScreenWalkthroughTest : WalkthroughBase() {

    @BindValue @JvmField
    val decisionModel: DecisionModel = FakeDecisionModel(languages = emptySet())

    @BindValue @JvmField
    val shortener: StepShortener = FakeStepShortener(support = ChefSupport.Unsupported)

    /** The shade and the launcher are other apps' windows: asks to see every window's tree. */
    private fun seeAllWindows() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    private fun global(action: Int, pauseMs: Long = 2000) {
        instrumentation.uiAutomation.performGlobalAction(action)
        Thread.sleep(pauseMs)
    }

    /** Every node under [node], depth first. */
    private fun descendants(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node == null) return@sequence
        yield(node)
        for (i in 0 until node.childCount) yieldAll(descendants(node.getChild(i)))
    }

    /**
     * The centre of the first node on screen whose text or description holds [text], walking
     * every window's tree (a `uiautomator dump` can't run beside the test's own UiAutomation).
     */
    private fun systemNode(text: String, exact: Boolean = false): Pair<Int, Int>? {
        val automation = instrumentation.uiAutomation
        val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
        fun CharSequence?.holds() = this != null && (if (exact) toString() == text else contains(text))
        val node = roots.asSequence().flatMap { descendants(it) }.firstOrNull { it.text.holds() || it.contentDescription.holds() }
            ?: return null
        val b = Rect().also { node.getBoundsInScreen(it) }
        return b.centerX() to b.centerY()
    }

    /**
     * Taps the system's node holding [text], waiting for it (scrolling the list above the
     * keyboard when [scrollable]); when none is found, taps [fallback] on screen instead.
     */
    private fun systemTap(
        text: String, pauseMs: Long = 2000, scrollable: Boolean = false, fallback: String? = null, exact: Boolean = false
    ) {
        val deadline = System.currentTimeMillis() + (if (fallback != null) 5_000 else 15_000)
        while (System.currentTimeMillis() < deadline) {
            val at = systemNode(text, exact)
            if (at != null) {
                shell("input tap ${at.first} ${at.second}")
                Thread.sleep(pauseMs)
                return
            }
            if (scrollable) shell("input swipe 540 1300 540 700 400")
            Thread.sleep(500)
        }
        if (fallback != null) {
            shell("input tap $fallback")
            Thread.sleep(pauseMs)
            return
        }
        shell("screencap -p /data/local/tmp/rc-walkthrough-miss.png")
        val seen = instrumentation.uiAutomation.windows.mapNotNull { it.root }.flatMap { descendants(it).toList() }
            .mapNotNull { it.text ?: it.contentDescription }.joinToString(" | ")
        Log.i("Walkthrough", "system texts: $seen")
        throw AssertionError("no system node holding \"$text\"")
    }

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).close()

    /**
     * A cook-mode timer, then its countdown in the notification shade (#309); a tap on it brings
     * the cook back with the timer still running, and again once the last step is done, when the
     * reading view shows the timer under its step.
     */
    @Test
    fun test38_timersOutsideTheApp() {
        start("liveTimers")
        seeAllWindows()
        tap("Chicken Adobo")
        tap("Start cooking", 2000)
        tap(hasText("Start", substring = true) and hasText("timer", substring = true), 2500)
        global(AccessibilityService.GLOBAL_ACTION_HOME)
        global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, 3500)
        systemTap("Timer: step", 1000, fallback = "540 760")
        waitFor(hasText("Done — next step"))
        pause(3000)
        repeat(3) { tap("Done — next step", 1200) }
        tap("Done — finish", 3000)
        global(AccessibilityService.GLOBAL_ACTION_HOME)
        global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, 3500)
        systemTap("Timer: step", 1000, fallback = "540 760")
        waitFor(hasText("Start cooking"))
        show("⏱", 4000)
    }

    /**
     * The home-screen widgets (#309): Tonight and Groceries, placed from the launcher's widget
     * picker (a touch and hold on the home screen, Widgets, the app, a widget's Add).
     */
    @Test
    fun test39_widgets() {
        start("widgets", kitchen = true)
        seeAllWindows()
        shell("pm clear com.google.android.apps.nexuslauncher") // the home screen as new: no widget from an earlier take
        tap("Plan")
        val today = LocalDate.now().toEpochDay()
        scrollTo("weekList", "addToDay-$today")
        tapTag("addToDay-$today")
        type("addSearch", "Chicken", submit = false)
        tap("Chicken Adobo")
        global(AccessibilityService.GLOBAL_ACTION_HOME)
        for (name in listOf("Tonight", "Groceries")) {
            shell("input swipe 540 1700 540 1700 1500") // a touch and hold on the home screen
            Thread.sleep(2000)
            systemTap("Widgets")
            systemTap("Search", 1000, fallback = "540 420") // the picker opens on Featured: the app is found by name
            shell("input text Recipe")
            Thread.sleep(1500)
            systemTap("Pith", scrollable = true)
            systemTap(name, 1500, scrollable = true, exact = true) // the preview, not the row's "Tonight, Groceries"
            systemTap("Add $name widget", 3000)
        }
        // Groceries (3 × 3) lands on a second page: a tap drops its resize handles, then back to
        // the first page for Tonight.
        shell("input tap 540 1500")
        Thread.sleep(3000)
        shell("input swipe 200 1200 900 1200 300")
        Thread.sleep(4000)
        // screenrecord writes a frame only on a change, and the app's own redraw at the end is off
        // screen here: a nudge of the page gives the last screen its time.
        shell("input swipe 540 1200 560 1200 300")
        Thread.sleep(1500)
    }
}
