package com.example.recipeclipper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.rememberNavController
import com.example.recipeclipper.ui.navigation.AppShell
import com.example.recipeclipper.ui.navigation.openSharedUrl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Shared links wait here until the NavHost is composed and can navigate to them.
    private val sharedUrls = Channel<String>(Channel.BUFFERED)

    // Whether the link in the current intent has actually been navigated to. It is saved
    // across recreation, so a rotation *after* navigation doesn't open the link a second
    // time (the back stack is restored instead), while a rotation *before* it, when the
    // queued link would otherwise die with the old activity, re-delivers it.
    private var shareHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is enforced from targetSdk 35 and can't be opted out of from 36, so
        // turn it on everywhere: transparent bars on every API level, and each screen pads
        // its content with the safe-drawing insets. Icon contrast is set by the theme.
        enableEdgeToEdge()
        shareHandled = savedInstanceState?.getBoolean(STATE_SHARE_HANDLED) ?: false

        setContent {
            val navController = rememberNavController()
            LaunchedEffect(navController) {
                // The first back-stack entry exists once the NavHost has set its graph. With the
                // tab bar on, the NavHost sits inside a Scaffold, which composes it later than
                // this effect may start.
                navController.currentBackStackEntryFlow.first()
                for (url in sharedUrls) {
                    // Always into the Recipes tab, whichever tab is open.
                    navController.openSharedUrl(url)
                    shareHandled = true
                }
            }
            AppShell(navController)
        }

        if (!shareHandled) extractUrl(intent)?.let { sharedUrls.trySend(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask launch mode means a repeat share arrives here instead of onCreate
        setIntent(intent)
        val url = extractUrl(intent) ?: return
        shareHandled = false
        sharedUrls.trySend(url)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHARE_HANDLED, shareHandled)
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
