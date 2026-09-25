package com.example.recipeclipper

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end-to-end smoke set (#91): the real app, with its real Hilt graph, database and
 * network stack, launched the ways a person launches it. The screens themselves are tested on
 * the JVM under Robolectric over fakes; what only a device proves is that the whole app starts,
 * and that a link shared from another app reaches the import route through the intent filter,
 * `MainActivity`'s queue and the NavHost.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        scenario?.close()
    }

    @Test
    fun theLauncherOpensHome() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText(context.getString(R.string.home_subtitle)).assertIsDisplayed()
    }

    /**
     * A shared link opens the import screen, which fetches it for real. The host is under the
     * reserved `.invalid` TLD, so the lookup fails without touching any site, and the fetch
     * failure lands on the error screen with Try again (after the repository's one retry).
     */
    @Test
    fun aSharedLinkOpensTheImportScreen() {
        val share = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Look at this https://recipe-clipper-smoke.invalid/soup")
        scenario = ActivityScenario.launch(share)
        compose.waitUntilAtLeastOneExists(hasText(context.getString(R.string.action_try_again)), 30_000)
    }
}
