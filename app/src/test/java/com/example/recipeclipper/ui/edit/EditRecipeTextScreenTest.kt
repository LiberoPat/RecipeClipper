package com.example.recipeclipper.ui.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.SharedText
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Text shared in (#306): the editor as the review, over a real ViewModel. */
@RunWith(AndroidJUnit4::class)
class EditRecipeTextScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()
    private val inbox = SharedTextInbox()

    private fun show(text: String, onSaved: (Long) -> Unit = {}) {
        // As MainActivity leaves it (#334): the reading in the inbox, its key in the route.
        val key = inbox.offer(text, (SharedText.route(text) as SharedText.Route.Editor).reading)
        val viewModel = EditRecipeViewModel(
            SavedStateHandle(mapOf(EditRecipeViewModel.SHARED_TEXT_ARG to key)),
            repository,
            sorting = Dispatchers.Unconfined,
            sharedTexts = inbox
        )
        compose.setContent { EditRecipeScreen(onBack = {}, onSaved = onSaved, viewModel = viewModel) }
    }

    @Test
    fun aRecipeInTheTextIsShownToCheckAndSavedOnlyOnSave() {
        repository.addManualResult = Recipe(
            id = 5L, name = "Scones", image = null, ingredients = listOf("2 cups flour"), instructions = emptyList(),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "manual:abc"
        )
        var saved: Long? = null
        show("Ingredients:\n- 2 cups flour\n- 1 tsp salt\nMethod:\n1. Mix.\n2. Bake.") { saved = it }

        compose.onNodeWithText("Check the recipe").assertIsDisplayed()
        compose.onNodeWithText("Sorted from the text you shared", substring = true).assertIsDisplayed()
        assertEquals(0, repository.addManualCalls.size)

        compose.onNodeWithText("Name").performTextReplacement("Scones")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals("2 cups flour\n1 tsp salt", repository.addManualCalls.single().ingredientsText)
        assertEquals(5L, saved)
    }

    @Test
    fun linesTheSplitLeftOutAreUnderNotesAsWritten() {
        show("Hi Mum, try this.\nIngredients:\n- 2 eggs <room temperature>\nMethod:\n1. Beat.\nTip: chill the bowl.")

        compose.onNodeWithText("Lines that didn't fit anywhere", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2 eggs <room temperature>", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tip: chill the bowl.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hi Mum, try this.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun textThatDoesNotSplitSaysItIsAllUnderSteps() {
        show("Hi Mum,\nMix the flour with the butter and bake it.")

        compose.onNodeWithText("Couldn't find ingredients and steps", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Mix the flour with the butter and bake it.", substring = true)
            .performScrollTo().assertIsDisplayed()
    }
}
