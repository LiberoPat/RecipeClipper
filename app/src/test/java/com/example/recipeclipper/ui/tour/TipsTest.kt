package com.example.recipeclipper.ui.tour

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.Tip
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeTourPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The one-time tips (#151): shown once, dismissed by a tap, hidden with their flag (iOS: TipsViewModelTests). */
@RunWith(AndroidJUnit4::class)
class TipsTest {

    @get:Rule
    val compose = createComposeRule()

    private val preferences = FakeTourPreferences()
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)

    private fun show(vararg tips: Tip) {
        val viewModel = TipsViewModel(preferences, flags)
        compose.setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalTips provides TipsHost(state.shown, viewModel::onDismiss)) {
                Column { tips.forEach { TipCallout(it) } }
            }
        }
    }

    @Test
    fun aTipShowsUntilTappedAndThenNeverAgain() {
        show(Tip.RECIPE)

        compose.onNodeWithText("The bookmark saves this recipe to a list.", substring = true).assertExists()
        compose.onNodeWithTag("tip-RECIPE")
            .assert(SemanticsMatcher("labelled for TalkBack") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Dismiss tip"
            })
        compose.onNodeWithText("The bookmark saves this recipe to a list.", substring = true).performClick()

        compose.onNodeWithTag("tip-RECIPE").assertDoesNotExist()
        assertEquals(setOf(Tip.RECIPE), runBlocking { preferences.seenTips.first() })
    }

    @Test
    fun aDismissedTipStaysHidden() {
        preferences.setTipSeen(Tip.COOK_MODE, true)
        show(Tip.COOK_MODE, Tip.RECIPE)

        compose.onNodeWithTag("tip-COOK_MODE").assertDoesNotExist()
        compose.onNodeWithTag("tip-RECIPE").assertHasClickAction()
    }

    @Test
    fun theMealPlanTipsHideWhileItsFlagIsOff() {
        flags.set(Flag.MEAL_PLAN, false)
        show(Tip.WEEK, Tip.GROCERIES, Tip.PANTRY)
        compose.onNodeWithTag("tip-WEEK").assertDoesNotExist()
        compose.onNodeWithTag("tip-PANTRY").assertDoesNotExist()

        flags.set(Flag.MEAL_PLAN, true)
        compose.onNodeWithTag("tip-WEEK").assertExists()
        compose.onNodeWithTag("tip-GROCERIES").assertExists()
        compose.onNodeWithTag("tip-PANTRY").assertExists()
    }

    @Test
    fun withNoTipsProvidedNothingShows() {
        compose.setContent { TipCallout(Tip.RECIPE) }
        compose.onNodeWithTag("tip-RECIPE").assertDoesNotExist()
    }
}
