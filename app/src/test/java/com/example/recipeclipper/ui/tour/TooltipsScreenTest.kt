package com.example.recipeclipper.ui.tour

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.data.model.Tooltips
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTourPreferences
import com.example.recipeclipper.ui.home.HomeScreen
import com.example.recipeclipper.ui.home.HomeViewModel
import com.example.recipeclipper.ui.settings.SettingsScreen
import com.example.recipeclipper.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tooltips (#190) on real screens over fakes, with the app's TooltipsViewModel provided as
 * MainActivity provides it: the first visit shows the first tooltip at its control, once the
 * screen has settled; "Got it" dismisses it for good; the next visit shows the next one.
 * Without [LocalTooltips] (every other screen test) nothing shows.
 */
@RunWith(AndroidJUnit4::class)
class TooltipsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val preferences = FakeTourPreferences()
    private val tooltips = TooltipsViewModel(
        preferences,
        FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)
    )

    /** Home, which a test can take off screen and bring back: a later visit. */
    private var homeShown by mutableStateOf(true)

    private fun showHome() {
        val home = HomeViewModel(FakeRecipeRepository())
        compose.setContent {
            CompositionLocalProvider(LocalTooltips provides tooltips) {
                if (homeShown) {
                    HomeScreen(onOpenUrl = {}, onOpenRecipe = {}, onOpenRecipes = {}, onOpenLists = {}, onOpenSettings = {}, viewModel = home)
                }
            }
        }
    }

    private fun seen() = runBlocking { preferences.seenTooltips.first() }

    @Test
    fun theFirstVisitShowsTheFirstTooltipOnceSettledAndTheNextVisitTheNext() {
        compose.mainClock.autoAdvance = false
        showHome()
        compose.mainClock.advanceTimeBy(Tooltips.SETTLE_MILLIS / 2)
        compose.onNodeWithTag("tooltip-home_link").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(Tooltips.SETTLE_MILLIS)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Share a recipe link to this app, or paste one here.").assertExists()
        // One button for TalkBack, announced as it appears; "Got it" is its visible label.
        compose.onNodeWithTag("tooltip-home_link")
            .assert(SemanticsMatcher("one labelled button") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Dismiss tip" })
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText("Got it").performClick()

        compose.onNodeWithTag("tooltip-home_link").assertDoesNotExist()
        assertEquals(setOf(Tooltip.HOME_LINK), seen())
        compose.mainClock.advanceTimeBy(Tooltips.SETTLE_MILLIS * 2)
        compose.onNodeWithTag("tooltip-home_new_recipe").assertDoesNotExist()

        homeShown = false
        compose.waitForIdle()
        homeShown = true
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTagExists("tooltip-home_new_recipe")
        }
        compose.onNodeWithText("Or type in a recipe of your own.").assertExists()
        compose.onNodeWithTag("tooltip-home_link").assertDoesNotExist()
    }

    @Test
    fun aTooltipShowsOnlyOnceItsControlIsOnScreen() {
        // Settings' second tooltip points at "Show tips again", far down the list.
        preferences.setTooltipSeen(Tooltip.SETTINGS_UNITS, true)
        var replays = 0
        val settings = SettingsViewModel(FakeAppPreferences(), FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo())
        compose.setContent {
            CompositionLocalProvider(LocalTooltips provides tooltips) {
                SettingsScreen(onBack = {}, onShowTips = { replays++; tooltips.onReplay() }, viewModel = settings)
            }
        }
        compose.mainClock.advanceTimeBy(Tooltips.SETTLE_MILLIS * 2)
        compose.onNodeWithTag("tooltip-settings_show_tips").assertDoesNotExist()

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Show tips again"))
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTagExists("tooltip-settings_show_tips") }
        compose.onNodeWithText("See every tip again, one at a time.").performClick()
        assertTrue(Tooltip.SETTINGS_SHOW_TIPS in seen())

        compose.onNodeWithText("Show tips again").performClick()
        assertEquals(1, replays)
        assertEquals(emptySet<Tooltip>(), seen())
    }

    @Test
    fun withNoTooltipsProvidedNothingShows() {
        val home = HomeViewModel(FakeRecipeRepository())
        compose.setContent {
            HomeScreen(onOpenUrl = {}, onOpenRecipe = {}, onOpenRecipes = {}, onOpenLists = {}, onOpenSettings = {}, viewModel = home)
        }
        compose.mainClock.advanceTimeBy(Tooltips.SETTLE_MILLIS * 2)
        compose.onNodeWithTag("tooltip-home_link").assertDoesNotExist()
    }

    /** The bubble sits against its control: centred on it, below when it fits, inside the window. */
    @Test
    fun theBubbleIsPlacedAtItsControl() {
        val density = Density(1f)
        var placed: BubblePlacement? = null
        fun place(anchor: Rect, side: TooltipSide) =
            BubblePositionProvider(anchor, side, density) { placed = it }
                .calculatePosition(IntRect.Zero, IntSize(1000, 2000), LayoutDirection.Ltr, IntSize(300, 100))

        val anchor = Rect(400f, 300f, 600f, 340f)
        assertEquals(IntOffset(350, 340), place(anchor, TooltipSide.AUTO))
        assertEquals(BubblePlacement(below = true, arrowX = 150f), placed)

        assertEquals("above when asked", IntOffset(350, 200), place(anchor, TooltipSide.ABOVE))
        assertEquals(BubblePlacement(below = false, arrowX = 150f), placed)

        val nearTheBottomRight = Rect(900f, 1950f, 980f, 1990f)
        assertEquals("kept inside the window's margin", IntOffset(1000 - 12 - 300, 1850), place(nearTheBottomRight, TooltipSide.AUTO))
        assertEquals(BubblePlacement(below = false, arrowX = 940f - 688f), placed)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagExists(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
