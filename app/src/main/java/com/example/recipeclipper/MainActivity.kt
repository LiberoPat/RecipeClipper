package com.example.recipeclipper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.example.recipeclipper.data.AutoBackup
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.ReceivedList
import com.example.recipeclipper.reminders.ExpiryNotifications
import com.example.recipeclipper.timers.TimerNotifications
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.ui.groceries.ReceivedListInbox
import com.example.recipeclipper.ui.navigation.AppShell
import com.example.recipeclipper.ui.navigation.Routes
import com.example.recipeclipper.ui.navigation.Tab
import com.example.recipeclipper.ui.navigation.openRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var featureFlags: FeatureFlags

    @Inject
    lateinit var receivedLists: ReceivedListInbox

    @Inject
    lateinit var autoBackup: AutoBackup

    // Routes from intents (a shared link, a tapped timer notification) wait here until the
    // NavHost is composed and can navigate to them.
    private val intentRoutes = Channel<String>(Channel.BUFFERED)

    // Whether the route in the current intent has actually been navigated to. It is saved
    // across recreation, so a rotation *after* navigation doesn't open it a second time (the
    // back stack is restored instead), while a rotation *before* it, when the queued route
    // would otherwise die with the old activity, re-delivers it.
    private var shareHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is enforced from targetSdk 35 and can't be opted out of from 36, so
        // turn it on everywhere: transparent bars on every API level, and each screen pads
        // its content with the safe-drawing insets. Icon contrast is set by the theme.
        enableEdgeToEdge()
        shareHandled = savedInstanceState?.getBoolean(STATE_SHARE_HANDLED) ?: false

        setContent {
            // The flags (#87) as they change in Developer settings. Turning the tab shell on or
            // off swaps the whole navigation graph, so it gets a fresh NavController (and opens
            // on Home) rather than restoring a back stack from the other graph.
            val flags by featureFlags.values.collectAsStateWithLifecycle(featureFlags.current)
            val tabsEnabled = flags.isOn(Flag.MEAL_PLAN)
            val navController = key(tabsEnabled) { rememberNavController() }
            LaunchedEffect(navController) {
                // The first back-stack entry exists once the NavHost has set its graph. With the
                // tab bar on, the NavHost sits inside a Scaffold, which composes it later than
                // this effect may start.
                navController.currentBackStackEntryFlow.first()
                for (route in intentRoutes) {
                    // Into the Recipes tab, whichever tab is open (a tab's own route opens that tab).
                    navController.openRoute(route, tabsEnabled)
                    shareHandled = true
                }
            }
            CompositionLocalProvider(LocalFlagValues provides flags) {
                AppShell(navController, tabsEnabled)
            }
        }

        if (!shareHandled) routeFor(intent)?.let { intentRoutes.trySend(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask launch mode means a repeat share (or a notification tap) arrives here
        // instead of onCreate
        setIntent(intent)
        val route = routeFor(intent) ?: return
        shareHandled = false
        intentRoutes.trySend(route)
    }

    override fun onStop() {
        super.onStop()
        // The automatic backup copy (#150): leaving the app is when a changed library is copied.
        if (!isChangingConfigurations) autoBackup.onAppLeft()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHARE_HANDLED, shareHandled)
    }

    /** Where an intent leads: a shared link imports, a timer notification opens cook mode, an
     *  expiry reminder (#52) opens the Pantry tab, and shared text with no link but with lines
     *  (#149) opens Groceries on the "Add this list" sheet (only with the tabs, #47). */
    private fun routeFor(intent: Intent?): String? {
        if (intent?.action == ExpiryNotifications.ACTION_OPEN_PANTRY) return Tab.PANTRY.route
        if (intent?.action == TimerNotifications.ACTION_OPEN_COOK) {
            val id = intent.getLongExtra(TimerNotifications.EXTRA_RECIPE_ID, -1)
            return if (id > 0) Routes.cookRecipe(id) else null
        }
        val text = sharedText(intent) ?: return null
        Regex("https?://\\S+").find(text)?.let { return Routes.import(it.value) }
        if (featureFlags.current.isOn(Flag.MEAL_PLAN) && ReceivedList.lines(text).isNotEmpty()) {
            receivedLists.offer(text)
            return Tab.GROCERIES.route
        }
        return null
    }

    /** Browsers share a link, and messaging apps a message, as EXTRA_TEXT on ACTION_SEND text/plain. */
    private fun sharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)
    }

    private companion object {
        const val STATE_SHARE_HANDLED = "share_handled"
    }
}
