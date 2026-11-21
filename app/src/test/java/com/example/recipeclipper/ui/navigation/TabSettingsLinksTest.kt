package com.example.recipeclipper.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.flags.pantrySettingsLink
import com.example.recipeclipper.data.flags.planSettingsLink
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.pantry.PantryScreen
import com.example.recipeclipper.ui.pantry.PantryViewModel
import com.example.recipeclipper.ui.settings.SettingsSection
import com.example.recipeclipper.ui.settings.SettingsSectionScreen
import com.example.recipeclipper.ui.settings.SettingsViewModel
import com.example.recipeclipper.ui.week.WeekScreen
import com.example.recipeclipper.ui.week.WeekViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Each tab's own Settings section from its menu (#412): "Plan settings" opens Settings' Plan page and
 * "Pantry settings" the Pantry's, on that tab's own stack (the bar stays, that tab selected), and Back
 * returns to the tab. The shell is the real one, its two tabs wired as `weekDestinations` and
 * `pantryDestinations` wire them, over real ViewModels on the fakes (the real graphs need Hilt).
 * iOS: `TabSettingsLinksTests`.
 */
@RunWith(AndroidJUnit4::class)
class TabSettingsLinksTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private val flagStore = FakeFeatureFlagStore()
    private val flags = FeatureFlags(flagStore, FlagRegistry.definitions, isDebug = true)

    private val weekVm = WeekViewModel(FakeMealPlanRepository(), FakeRecipeRepository(), FakePlanCalendar())
    private val pantryVm = PantryViewModel(FakePantryRepository(), FakeGroceryRepository(), FakePlanCalendar())
    private val settingsVm = SettingsViewModel(FakeAppPreferences(), FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags)

    private fun NavGraphBuilder.week(nav: NavHostController, link: Boolean) {
        composable(Routes.WEEK) {
            WeekScreen(
                onOpenRecipe = { _, _ -> },
                onOpenMealTypes = {},
                viewModel = weekVm,
                onOpenSettings = if (link) { { nav.navigate(Routes.WEEK_SETTINGS) } } else null
            )
        }
        composable(Routes.WEEK_SETTINGS) {
            SettingsSectionScreen(SettingsSection.WEEK, onBack = { nav.popBackStack() }, viewModel = settingsVm)
        }
    }

    private fun NavGraphBuilder.pantry(nav: NavHostController, link: Boolean) {
        composable(Routes.PANTRY) {
            PantryScreen(
                viewModel = pantryVm,
                onOpenSettings = if (link) { { nav.navigate(Routes.PANTRY_SETTINGS) } } else null
            )
        }
        composable(Routes.PANTRY_SETTINGS) {
            SettingsSectionScreen(SettingsSection.PANTRY, onBack = { nav.popBackStack() }, viewModel = settingsVm)
        }
    }

    private fun show(link: Boolean = true) {
        compose.setContent {
            nav = rememberNavController()
            AppShell(
                nav,
                recipes = { composable(Routes.HOME) { Text("stub:home") } },
                week = { week(it, link) },
                groceries = { composable(Routes.GROCERIES) { Text("stub:groceries") } },
                pantry = { pantry(it, link) },
                libraryFirst = false
            )
        }
        compose.onNodeWithText("stub:home").assertIsDisplayed()
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and isSelectable())
    private fun route() = compose.runOnIdle { nav.currentDestination?.route }
    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
    private fun openMenu() = compose.onNodeWithContentDescription("More options").performClick()
    private fun systemBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun planSettingsOpensThePlanSectionOnThePlanStackAndBackReturnsToTheWeek() {
        show()
        tab("Plan").performClick()
        openMenu()
        compose.onNodeWithTag("planSettings").assertIsDisplayed()
        compose.onNodeWithText("Reminders about the meals you plan").assertIsDisplayed()
        compose.onNodeWithTag("planSettings").performClick()

        assertEquals(Routes.WEEK_SETTINGS, route())
        compose.onNodeWithText("Evening reminder").assertIsDisplayed()
        compose.onNodeWithTag("tabBar").assertIsDisplayed()
        tab("Plan").assertIsSelected()

        systemBack()
        assertEquals(Routes.WEEK, route())
        tab("Plan").assertIsSelected()
    }

    @Test
    fun pantrySettingsOpensThePantrySectionOnThePantryStackAndItsBackReturnsToThePantry() {
        show()
        tab("Pantry").performClick()
        openMenu()
        compose.onNodeWithText("Pantry settings").assertIsDisplayed()
        compose.onNodeWithTag("pantrySettings").performClick()

        assertEquals(Routes.PANTRY_SETTINGS, route())
        compose.onNodeWithText("Expiry reminders").assertIsDisplayed()
        compose.onNodeWithTag("tabBar").assertIsDisplayed()
        tab("Pantry").assertIsSelected()

        // The page's own Back.
        compose.onNodeWithText("‹ Back").performClick()
        assertEquals(Routes.PANTRY, route())
    }

    @Test
    fun theOtherTabsKeepTheirPlaceWhileASettingsPageIsOpen() {
        show()
        tab("Pantry").performClick()
        openMenu()
        compose.onNodeWithTag("pantrySettings").performClick()
        tab("Recipes").performClick()
        compose.onNodeWithText("stub:home").assertIsDisplayed()

        tab("Pantry").performClick()
        assertEquals(Routes.PANTRY_SETTINGS, route())
        // The open tab again goes back to its first screen.
        tab("Pantry").performClick()
        assertEquals(Routes.PANTRY, route())
    }

    @Test
    fun anExpiryRemindersTapOpensThePantryItselfNotItsSettingsPage() {
        show()
        tab("Pantry").performClick()
        openMenu()
        compose.onNodeWithTag("pantrySettings").performClick()
        tab("Recipes").performClick()

        compose.runOnIdle { nav.openRoute(Tab.PANTRY.route) }
        compose.waitForIdle()
        assertEquals(Routes.PANTRY, route())
        tab("Pantry").assertIsSelected()
    }

    @Test
    fun withoutALinkNeitherMenuHasTheItem() {
        show(link = false)
        tab("Plan").performClick()
        openMenu()
        compose.onNodeWithText("Meal types").assertIsDisplayed()
        assertEquals(0, count("planSettings"))
        systemBack()

        tab("Pantry").performClick()
        openMenu()
        compose.onNodeWithText("Sort by expiry").assertIsDisplayed()
        assertEquals(0, count("pantrySettings"))
    }

    @Test
    fun theLinksAreOnByDefaultAndPlanSettingsNeedsTheReminders() = runBlocking {
        for (debug in listOf(true, false)) {
            val values = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = debug).values.first()
            assertTrue(values.isOn(Flag.TAB_SETTINGS_LINKS))
            assertTrue(values.planSettingsLink)
            assertTrue(values.pantrySettingsLink)
        }
        // Settings' Plan section shows only with eveningReminders, so its link goes with it.
        flagStore.setOverride(Flag.EVENING_REMINDERS.key, false)
        assertFalse(flags.values.first().planSettingsLink)
        assertTrue(flags.values.first().pantrySettingsLink)

        flagStore.setOverride(Flag.TAB_SETTINGS_LINKS.key, false)
        flagStore.setOverride(Flag.EVENING_REMINDERS.key, true)
        assertFalse(flags.values.first().planSettingsLink)
        assertFalse(flags.values.first().pantrySettingsLink)
    }
}
