package com.example.recipeclipper.walkthrough

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
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

    /** Every node on screen whose text or description holds [text], across the system's windows. */
    private fun systemNodes(text: String): List<AccessibilityNodeInfo> {
        val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root } +
            listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        return roots.flatMap { it.findAccessibilityNodeInfosByText(text) }
    }

    /** Taps the system's node holding [text] (its nearest clickable ancestor), waiting for it. */
    private fun systemTap(text: String, pauseMs: Long = 2000, scrollable: Boolean = false) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            var node = systemNodes(text).firstOrNull()
            while (node != null && !node.isClickable) node = node.parent
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(pauseMs)
                return
            }
            if (scrollable) shell("input swipe 540 1800 540 900 400")
            Thread.sleep(500)
        }
        shell("screencap -p /data/local/tmp/rc-walkthrough-miss.png")
        Log.i("Walkthrough", "system windows: " + instrumentation.uiAutomation.windows.joinToString { it.toString() })
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
        systemTap("Timer: step", 1000)
        waitFor(hasText("Done — next step"))
        pause(3000)
        repeat(3) { tap("Done — next step", 1200) }
        tap("Done — finish", 3000)
        global(AccessibilityService.GLOBAL_ACTION_HOME)
        global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, 3500)
        systemTap("Timer: step", 1000)
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
        tap("Week")
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
            systemTap("Recipe Clipper", scrollable = true)
            systemTap(name, 1500, scrollable = true)
            systemTap("Add", 3000)
        }
        Thread.sleep(4000)
    }
}
