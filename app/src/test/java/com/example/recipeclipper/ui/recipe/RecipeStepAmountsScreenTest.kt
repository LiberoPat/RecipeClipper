package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Amounts in steps" (#101) on the real [RecipeScreen]: the fixture's "Stir in the milk." reads
 * "Stir in 1 cup milk." in the reading view and in cook mode, only with the switch on.
 */
@RunWith(AndroidJUnit4::class)
class RecipeStepAmountsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(switch: Boolean) = RecipeScreenFixture().apply {
        preferences.amountsInSteps = switch
        show(compose)
    }

    @Test fun `the reading view and cook mode show the amount inside the step`() {
        show(switch = true)
        compose.onNodeWithText("Stir in 1 cup milk.").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Start cooking").performClick()
        compose.onNodeWithText("Stir in 1 cup milk.", useUnmergedTree = true).assertExists()
    }

    @Test fun `the step stays as written with the switch off`() {
        show(switch = false)
        compose.onNodeWithText("Stir in the milk.").performScrollTo().assertIsDisplayed()
    }
}
