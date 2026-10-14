package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.semantics.SemanticsActions
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

    // Two butter lines with different amounts (#254): "butter" is a link to both lines.
    private fun showTwoButters() = RecipeScreenFixture(
        RecipeScreenFixture.testRecipe(listOf("Melt the butter.", "Serve.")).copy(
            ingredients = listOf("200g butter", "100g butter")
        )
    ).apply {
        preferences.amountsInSteps = true
        show(compose)
    }

    // The link's accessibility action, as TalkBack offers it.
    private fun tapLink(label: String) {
        val node = compose.onNodeWithText("Melt the butter.", useUnmergedTree = true).fetchSemanticsNode()
        node.config[SemanticsActions.CustomActions].single { it.label == label }.action()
        compose.waitForIdle()
    }

    @Test fun `a link to two lines goes to them, and back to the step`() {
        showTwoButters()
        tapLink("butter, 2 matching ingredients, show")
        compose.onNodeWithText("Back to step 1").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Back to step 1").assertDoesNotExist()
    }

    @Test fun `in cook mode a link opens the ingredients, and back folds them`() {
        showTwoButters()
        compose.onNodeWithText("Start cooking").performClick()
        compose.onNodeWithText("100g butter").assertDoesNotExist()

        tapLink("butter, 2 matching ingredients, show")
        compose.onNodeWithText("100g butter").assertIsDisplayed()
        compose.onNodeWithText("Back to step 1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("100g butter").assertDoesNotExist()
    }

    @Test fun `the step stays as written with the switch off`() {
        show(switch = false)
        compose.onNodeWithText("Stir in the milk.").performScrollTo().assertIsDisplayed()
    }
}
