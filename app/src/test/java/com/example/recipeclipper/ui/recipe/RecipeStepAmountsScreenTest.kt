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
 * "Stir in 1 cup milk." in the reading view and in cook mode, only with the flag and the switch on.
 */
@RunWith(AndroidJUnit4::class)
class RecipeStepAmountsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(flag: Boolean, switch: Boolean) = RecipeScreenFixture(amountsInSteps = flag).apply {
        preferences.amountsInSteps = switch
        show(compose)
    }

    @Test fun `the reading view and cook mode show the amount inside the step`() {
        show(flag = true, switch = true)
        compose.onNodeWithText("Stir in 1 cup milk.").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Start cooking").performClick()
        compose.onNodeWithText("Stir in 1 cup milk.", useUnmergedTree = true).assertExists()
    }

    @Test fun `the step stays as written with the switch off`() {
        show(flag = true, switch = false)
        compose.onNodeWithText("Stir in the milk.").performScrollTo().assertIsDisplayed()
    }

    @Test fun `the step stays as written with the flag off, whatever the switch`() {
        show(flag = false, switch = true)
        compose.onNodeWithText("Stir in the milk.").performScrollTo().assertIsDisplayed()
    }
}
