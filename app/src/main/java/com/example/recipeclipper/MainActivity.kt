package com.example.recipeclipper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.example.recipeclipper.data.FirstRunTour
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.reminders.ExpiryNotifications
import com.example.recipeclipper.timers.TimerNotifications
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.ui.navigation.AppShell
import com.example.recipeclipper.ui.navigation.Routes
import com.example.recipeclipper.ui.navigation.Tab
import com.example.recipeclipper.ui.navigation.openRoute
import com.example.recipeclipper.ui.tour.LocalTips
import com.example.recipeclipper.ui.tour.TipsHost
import com.example.recipeclipper.ui.tour.TipsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var featureFlags: FeatureFlags

    @Inject
    lateinit var firstRunTour: FirstRunTour

    // The one-time tips (#151), provided to every screen through LocalTips.
    private val tips: TipsViewModel by viewModels()

    // Routes from intents (a shared link, a tapped timer notification) wait here until the
    // NavHost is composed and can navigate to them.
    private val intentRoutes = Channel<String>(Channel.BUFFERED)

    // Whether the route in the current intent has actually been navigated to. It is saved
    // across recreation, so a rotation *after* navigation doesn't open it a second time (the
    // back stack is restored instead), while a rotation *before* it, when the queued route
    // would otherwise die with the old activity, re-delivers it.
    private var shareHandled = false

    // Whether this start has decided on the welcome (#151). Saved like shareHandled, so a
    // rotation doesn't open it twice, and a flag change that swaps the NavController doesn't either.
    private var welcomeChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is enforced from targetSdk 35 and can't be opted out of from 36, so
        // turn it on everywhere: transparent bars on every API level, and each screen pads
        // its content with the safe-drawing insets. Icon contrast is set by the theme.
        enableEdgeToEdge()
        shareHandled = savedInstanceState?.getBoolean(STATE_SHARE_HANDLED) ?: false
        welcomeChecked = savedInstanceState?.getBoolean(STATE_WELCOME_CHECKED) ?: false
        // A launch that opens something (a shared link, a notification) isn't a plain one: the
        // welcome waits for the next plain launch, so a shared link still opens on the recipe.
        val plainLaunch = routeFor(intent) == null

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
                // Decided before any intent's route opens, so the library it counts is the one
                // this launch found.
                if (!welcomeChecked) {
                    welcomeChecked = true
                    if (firstRunTour.onLaunch(plainLaunch)) navController.navigate(Routes.welcome(again = false))
                }
                for (route in intentRoutes) {
                    // Into the Recipes tab, whichever tab is open (a tab's own route opens that tab).
                    navController.openRoute(route, tabsEnabled)
                    shareHandled = true
                }
            }
            val tipsState by tips.uiState.collectAsStateWithLifecycle()
            val tipsHost = remember(tipsState) { TipsHost(tipsState.shown, tips::onDismiss) }
            CompositionLocalProvider(LocalFlagValues provides flags, LocalTips provides tipsHost) {
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHARE_HANDLED, shareHandled)
        outState.putBoolean(STATE_WELCOME_CHECKED, welcomeChecked)
    }

    /** Where an intent leads: a shared link imports, a timer notification opens cook mode, and
     *  an expiry reminder (#52) opens the Pantry tab. */
    private fun routeFor(intent: Intent?): String? {
        if (intent?.action == ExpiryNotifications.ACTION_OPEN_PANTRY) return Tab.PANTRY.route
        if (intent?.action == TimerNotifications.ACTION_OPEN_COOK) {
            val id = intent.getLongExtra(TimerNotifications.EXTRA_RECIPE_ID, -1)
            return if (id > 0) Routes.cookRecipe(id) else null
        }
        return extractUrl(intent)?.let(Routes::import)
    }

    /** Browsers share a link as EXTRA_TEXT on an ACTION_SEND text/plain intent. */
    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("https?://\\S+").find(text)?.value
    }

    private companion object {
        const val STATE_SHARE_HANDLED = "share_handled"
        const val STATE_WELCOME_CHECKED = "welcome_checked"
    }
}
