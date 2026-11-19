package com.example.recipeclipper.ui.askai

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Ask an AI" (#351, #353): the sheet's field, its ready prompts, Copy prompt and Share. */
@RunWith(AndroidJUnit4::class)
class AskAiSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun viewModel(pantry: List<PantryItem> = emptyList()) = AskAiViewModel(
        FakePantryRepository(pantry), FakeListRepository(), FakeRecipeRepository(), FakeAppPreferences(),
        Clock { 1_800_000_000_000L }
    )

    private fun clipboard(): String? {
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        return manager.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test fun `copy waits for words, then puts the prompt on the clipboard`() {
        val vm = viewModel()
        compose.setContent { RecipeClipperTheme { AskAiSheet(onDismiss = {}, viewModel = vm) } }

        compose.onNodeWithText("What do you want to cook?").assertExists()
        compose.onNodeWithTag("askAiCopy").assertIsNotEnabled()
        compose.onNodeWithTag("askAiShare").assertIsNotEnabled()

        compose.onNodeWithTag("askAiField").performTextInput("a lentil soup")
        compose.onNodeWithTag("askAiCopy").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertTrue(clipboard()!!.startsWith("I want to cook: a lentil soup"))
        compose.onNodeWithText("Copied").assertExists()
    }

    @Test fun `what can I make is off with an empty pantry`() {
        val vm = viewModel()
        compose.setContent { RecipeClipperTheme { AskAiSheet(onDismiss = {}, viewModel = vm) } }
        compose.onNodeWithTag("askAiKind-WHAT_CAN_I_MAKE").assertIsNotEnabled()
        compose.onNodeWithText("The pantry is empty").assertExists()
    }

    @Test fun `a ready prompt copies without any words`() {
        val rice = PantryItem(1, "rice", "2 cups", "en", Aisle.OTHER, true, false, null, null)
        val vm = viewModel(listOf(rice))
        compose.setContent { RecipeClipperTheme { AskAiSheet(onDismiss = {}, viewModel = vm) } }

        compose.onNodeWithTag("askAiKind-WHAT_CAN_I_MAKE").performClick()
        compose.onNodeWithText("Anything else? (optional)").assertExists()
        compose.onNodeWithTag("askAiCopy").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertTrue(clipboard()!!.contains("Also in my pantry: rice (2 cups)."))
    }

    @Test fun `change this recipe sends the request with the recipe as JSON`() {
        val recipe = Recipe(
            name = "Adobo", image = null, ingredients = listOf("2 lb chicken"), instructions = listOf("Simmer."),
            prepTime = null, cookTime = null, totalTime = null, yield = "4 servings", sourceUrl = "https://example.com/adobo"
        )
        val vm = viewModel()
        compose.setContent { RecipeClipperTheme { ChangeRecipeSheet(recipe, onDismiss = {}, viewModel = vm) } }

        compose.onNodeWithTag("askAiCopy").assertIsNotEnabled()
        compose.onNodeWithTag("changeRecipeField").performTextInput("make it vegetarian")
        compose.onNodeWithTag("askAiCopy").performClick()
        compose.waitForIdle()

        val prompt = clipboard()!!
        assertTrue(prompt.startsWith("Please change this recipe: make it vegetarian"))
        assertTrue(prompt.contains("\"isBasedOn\": \"https://example.com/adobo\""))
        assertTrue(prompt.contains("```json\n{"))
    }
}
