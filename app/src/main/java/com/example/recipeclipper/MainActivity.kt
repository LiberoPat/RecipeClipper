package com.example.recipeclipper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.rememberNavController
import com.example.recipeclipper.timers.TimerNotifications
import com.example.recipeclipper.ui.navigation.RecipeNavHost
import com.example.recipeclipper.ui.navigation.Routes
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
        shareHandled = savedInstanceState?.getBoolean(STATE_SHARE_HANDLED) ?: false

        setContent {
            val navController = rememberNavController()
            LaunchedEffect(navController) {
                for (route in intentRoutes) {
                    navController.navigate(route)
                    shareHandled = true
                }
            }
            RecipeNavHost(navController)
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
    }

    /** Where an intent leads: a shared link imports, a timer notification opens cook mode. */
    private fun routeFor(intent: Intent?): String? {
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
    }
}
